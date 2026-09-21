package live.itantra.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class HybridRecognizer(
    private val context: Context,
    private val whisper: WhisperRecognizer,
    private val platform: OnDeviceRecognizer,
) : Recognizer {

    @Volatile private var platformLanguages: Set<String> = emptySet()
    @Volatile private var active: Recognizer? = null

    override val isAvailable: Boolean
        get() = whisper.isAvailable || platform.isAvailable

    fun covers(language: String): Boolean = engineFor(language) != null

    fun engineName(language: String): String = when (engineFor(language)) {
        whisper -> "whisper.cpp"
        platform -> "platform"
        else -> "none"
    }

    private fun engineFor(language: String): Recognizer? = when {
        whisper.isAvailable && language == BUNDLED_LANGUAGE -> whisper
        platform.isAvailable && language in platformLanguages -> platform
        else -> null
    }

    fun warmUp() = whisper.warmUp()

    fun refreshPlatformLanguages(onDone: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !platform.isAvailable) {
            onDone()
            return
        }
        Handler(Looper.getMainLooper()).post {
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull() ?: return@post onDone()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            runCatching {
                recognizer.checkRecognitionSupport(
                    intent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            platformLanguages = support.installedOnDeviceLanguages
                                .mapNotNull { it.take(2).lowercase().takeIf(String::isNotBlank) }
                                .toSet()
                            Log.i(TAG, "platform offline languages: $platformLanguages")
                            runCatching { recognizer.destroy() }
                            onDone()
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "platform language query failed: $error")
                            runCatching { recognizer.destroy() }
                            onDone()
                        }
                    },
                )
            }.onFailure {
                runCatching { recognizer.destroy() }
                onDone()
            }
        }
    }

    override fun start(
        language: String,
        onResult: (Recognition) -> Unit,
        onError: (String) -> Unit,
    ) {
        val engine = engineFor(language)
        if (engine == null) {
            onError(
                "no offline speech model for ${Languages.NAMES[language] ?: language} on " +
                    "this device — type the message instead"
            )
            return
        }
        active = engine
        engine.start(language, onResult, onError)
    }

    override fun stop() {
        active?.stop()
    }

    override fun release() {
        whisper.release()
        platform.release()
        active = null
    }

    private companion object {
        const val TAG = "iTantraSpeech"

        const val BUNDLED_LANGUAGE = "hi"
    }
}
