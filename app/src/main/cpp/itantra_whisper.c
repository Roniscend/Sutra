#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include "whisper.h"
#include "itantra_grammar.h"

#define TAG "iTantraWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UNUSED(x) (void)(x)

#define GRAMMAR_PENALTY 100.0f

JNIEXPORT jlong JNICALL
Java_live_itantra_speech_WhisperNative_nativeInitFromAsset(
        JNIEnv *env, jclass clazz, jobject asset_manager, jstring asset_path) {
    UNUSED(clazz);

    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    if (manager == NULL) {
        LOGE("could not obtain AAssetManager");
        return 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, asset_path, NULL);
    AAsset *asset = AAssetManager_open(manager, path, AASSET_MODE_BUFFER);
    if (asset == NULL) {
        LOGE("model asset not found: %s", path);
        (*env)->ReleaseStringUTFChars(env, asset_path, path);
        return 0;
    }

    const void *buffer = AAsset_getBuffer(asset);
    const off_t size = AAsset_getLength(asset);
    if (buffer == NULL || size <= 0) {
        LOGE("could not map model asset %s", path);
        AAsset_close(asset);
        (*env)->ReleaseStringUTFChars(env, asset_path, path);
        return 0;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx =
            whisper_init_from_buffer_with_params((void *) buffer, (size_t) size, cparams);

    AAsset_close(asset);
    (*env)->ReleaseStringUTFChars(env, asset_path, path);

    if (ctx == NULL) {
        LOGE("whisper_init_from_buffer failed");
        return 0;
    }
    LOGI("model loaded (%ld bytes)", (long) size);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_live_itantra_speech_WhisperNative_nativeFree(JNIEnv *env, jclass clazz, jlong ptr) {
    UNUSED(env);
    UNUSED(clazz);
    if (ptr != 0) {
        whisper_free((struct whisper_context *) ptr);
    }
}

static size_t sanitize_modified_utf8(char *s, size_t len) {
    size_t in = 0, out = 0;
    while (in < len) {
        const unsigned char c = (unsigned char) s[in];
        size_t need;

        if (c < 0x80) {

            if (c != 0x00) s[out++] = (char) c;
            in++;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            need = 2;
        } else if ((c & 0xF0) == 0xE0) {
            need = 3;
        } else {
            in++;
            continue;
        }

        if (in + need > len) break;
        int ok = 1;
        for (size_t k = 1; k < need; k++) {
            if (((unsigned char) s[in + k] & 0xC0) != 0x80) { ok = 0; break; }
        }
        if (!ok) { in++; continue; }

        for (size_t k = 0; k < need; k++) s[out++] = s[in + k];
        in += need;
    }
    s[out] = '\0';
    return out;
}

JNIEXPORT jstring JNICALL
Java_live_itantra_speech_WhisperNative_nativeTranscribe(
        JNIEnv *env, jclass clazz, jlong ptr, jint threads, jfloatArray audio,
        jstring language, jint beam_size, jfloatArray stats_out, jstring gbnf) {
    UNUSED(clazz);

    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx == NULL) return NULL;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio);

    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);

    struct whisper_full_params params =
            whisper_full_default_params(beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH
                                                      : WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.detect_language = false;
    params.language = lang;
    params.n_threads = threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    if (beam_size > 1) {
        params.beam_search.beam_size = beam_size;
    }

    itantra_grammar *grammar = NULL;
    const char *gbnf_text = NULL;
    if (gbnf != NULL) {
        gbnf_text = (*env)->GetStringUTFChars(env, gbnf, NULL);
        grammar = itantra_grammar_parse(gbnf_text);
        if (grammar != NULL) {
            params.grammar_rules = itantra_grammar_rules(grammar);
            params.n_grammar_rules = itantra_grammar_n_rules(grammar);
            params.i_start_rule = itantra_grammar_start_rule(grammar);

            params.grammar_penalty = GRAMMAR_PENALTY;
        } else {
            LOGE("grammar rejected; decoding unconstrained");
        }
    }

    LOGI("decode: lang=%s beam=%d threads=%d samples=%d (%.2fs) grammar=%s",
         params.language ? params.language : "(null)",
         beam_size, threads, (int) n_samples, n_samples / 16000.0,
         grammar ? "yes" : "no");

    const int rc = whisper_full(ctx, params, samples, n_samples);

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (grammar != NULL) itantra_grammar_free(grammar);
    if (gbnf_text != NULL) (*env)->ReleaseStringUTFChars(env, gbnf, gbnf_text);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return NULL;
    }

    const int n_segments = whisper_full_n_segments(ctx);

    size_t capacity = 512;
    size_t used = 0;
    char *text = (char *) malloc(capacity);
    if (text == NULL) return NULL;
    text[0] = '\0';

    double logprob_sum = 0.0;
    int token_count = 0;
    float worst_no_speech = 0.0f;

    for (int i = 0; i < n_segments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        const size_t len = strlen(segment);
        if (used + len + 1 > capacity) {
            while (used + len + 1 > capacity) capacity *= 2;
            char *grown = (char *) realloc(text, capacity);
            if (grown == NULL) {
                free(text);
                return NULL;
            }
            text = grown;
        }
        memcpy(text + used, segment, len);
        used += len;
        text[used] = '\0';

        const float no_speech = whisper_full_get_segment_no_speech_prob(ctx, i);
        if (no_speech > worst_no_speech) worst_no_speech = no_speech;

        const int n_tokens = whisper_full_n_tokens(ctx, i);
        for (int t = 0; t < n_tokens; t++) {
            const float p = whisper_full_get_token_p(ctx, i, t);
            if (p > 0.0f) {
                logprob_sum += log((double) p);
                token_count++;
            }
        }
    }

    if (stats_out != NULL && (*env)->GetArrayLength(env, stats_out) >= 2) {
        jfloat stats[2];
        stats[0] = token_count > 0 ? (jfloat) (logprob_sum / token_count) : -10.0f;
        stats[1] = worst_no_speech;
        (*env)->SetFloatArrayRegion(env, stats_out, 0, 2, stats);
    }

    const size_t clean = sanitize_modified_utf8(text, used);
    if (clean != used) {
        LOGI("dropped %d malformed byte(s) from transcript", (int) (used - clean));
    }

    jstring result = (*env)->NewStringUTF(env, text);
    free(text);
    return result;
}

JNIEXPORT jstring JNICALL
Java_live_itantra_speech_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
