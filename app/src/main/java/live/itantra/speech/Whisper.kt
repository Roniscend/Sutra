package live.itantra.speech

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private object WhisperNative {
    val available: Boolean = runCatching { System.loadLibrary("itantra_whisper") }.isSuccess

    external fun nativeInitFromAsset(assets: AssetManager, path: String): Long
    external fun nativeFree(ptr: Long)
    external fun nativeTranscribe(
        ptr: Long,
        threads: Int,
        audio: FloatArray,
        language: String,
        beamSize: Int,
        statsOut: FloatArray,
        gbnf: String?,
    ): String?

    external fun nativeSystemInfo(): String
}

data class WhisperResult(
    val text: String,
    val avgLogProb: Float,
    val noSpeechProb: Float,
    val inferenceMillis: Long,

    val isDegenerate: Boolean = false,
) {

    val confidence: Float get() = max(0f, min(1f, exp(avgLogProb.toDouble()).toFloat()))
}

class WhisperEngine private constructor(private var ptr: Long) : Closeable {

    fun transcribe(
        audio: FloatArray,
        language: String,
        beamSize: Int = DEFAULT_BEAM,
        threads: Int = DEFAULT_THREADS,
        grammar: String? = null,
    ): WhisperResult? {
        if (ptr == 0L || audio.isEmpty()) return null
        val stats = FloatArray(2)
        val started = System.currentTimeMillis()
        val text = synchronized(this) {
            if (ptr == 0L) null
            else WhisperNative.nativeTranscribe(
                ptr, threads, audio, language, beamSize, stats, grammar,
            )
        } ?: return null
        val cleaned = stripNonSpeechMarkers(text)
        return WhisperResult(
            text = cleaned,
            avgLogProb = stats[0],
            noSpeechProb = stats[1],
            inferenceMillis = System.currentTimeMillis() - started,
            isDegenerate = isRepetitionLoop(cleaned),
        )
    }

    override fun close() {
        synchronized(this) {
            if (ptr != 0L) {
                WhisperNative.nativeFree(ptr)
                ptr = 0L
            }
        }
    }

    companion object {
        const val MODEL_ASSET = "models/ggml-whisper.bin"
        private const val TAG = "iTantraWhisper"
        private const val DEFAULT_BEAM = 5

        private val NON_SPEECH_MARKER = Regex("""[\[(][^\])]*[\])]""")

        fun stripNonSpeechMarkers(raw: String): String =
            NON_SPEECH_MARKER.replace(raw, " ").replace(Regex("""\s+"""), " ").trim()

        fun isRepetitionLoop(text: String): Boolean {
            val tokens = text.split(Regex("""\s+"""))
                .map { token -> token.trim { it in TRIM_CHARS } }
                .filter { it.isNotEmpty() }
            if (tokens.size < MIN_TOKENS_FOR_LOOP) return false

            for (cycle in 1..MAX_CYCLE_LEN) {
                val repeats = longestCycleRun(tokens, cycle)
                if (repeats >= MIN_CYCLE_REPEATS &&
                    repeats * cycle >= tokens.size * MIN_LOOP_COVERAGE
                ) {
                    return true
                }
            }
            return false
        }

        private fun longestCycleRun(tokens: List<String>, cycle: Int): Int {
            if (tokens.size < cycle * 2) return 0
            var best = 0
            for (start in 0..tokens.size - cycle) {
                var repeats = 1
                var next = start + cycle
                while (next + cycle <= tokens.size &&
                    (0 until cycle).all { tokens[next + it] == tokens[start + it] }
                ) {
                    repeats++
                    next += cycle
                }
                if (repeats > best) best = repeats
            }
            return best
        }

        private const val TRIM_CHARS = ",.!?;:।॥\"'"

        private const val MIN_TOKENS_FOR_LOOP = 8
        private const val MAX_CYCLE_LEN = 3
        private const val MIN_CYCLE_REPEATS = 6
        private const val MIN_LOOP_COVERAGE = 0.6

        private val DEFAULT_THREADS =
            max(2, min(4, Runtime.getRuntime().availableProcessors() - 2))

        private const val GRAMMAR_DIR = "grammars"

        private val grammarCache = HashMap<String, String?>()

        fun grammarFor(context: Context, language: String): String? =
            synchronized(grammarCache) {
                grammarCache.getOrPut(language) {
                    runCatching {
                        context.assets.open("$GRAMMAR_DIR/$language.gbnf")
                            .bufferedReader().use { it.readText() }
                    }.getOrNull()
                }
            }

        fun isSupported(context: Context): Boolean =
            WhisperNative.available && runCatching {
                context.assets.open(MODEL_ASSET).close(); true
            }.getOrDefault(false)

        fun load(context: Context): WhisperEngine? {
            if (!WhisperNative.available) {
                Log.w(TAG, "native library unavailable")
                return null
            }
            val ptr = runCatching {
                WhisperNative.nativeInitFromAsset(context.assets, MODEL_ASSET)
            }.getOrElse {
                Log.e(TAG, "model init threw", it)
                0L
            }
            if (ptr == 0L) {
                Log.w(TAG, "model asset $MODEL_ASSET missing or failed to load")
                return null
            }
            Log.i(TAG, "whisper ready: ${runCatching { WhisperNative.nativeSystemInfo() }
                .getOrDefault("?")}")
            return WhisperEngine(ptr)
        }
    }
}
