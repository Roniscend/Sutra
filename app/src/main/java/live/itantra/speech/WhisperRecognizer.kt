package live.itantra.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

class WhisperRecognizer(private val context: Context) : Recognizer {

    private val capture = AudioCapture()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whisper-inference").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var engine: WhisperEngine? = null
    @Volatile private var loading = false
    @Volatile private var listening = false
    private var language: String = "hi"
    private var onResult: ((Recognition) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override val isAvailable: Boolean get() = WhisperEngine.isSupported(context)

    fun warmUp() {
        if (engine != null || loading || !isAvailable) return
        loading = true
        worker.execute {
            engine = WhisperEngine.load(context)
            loading = false
            if (engine == null) Log.w(TAG, "model failed to load; falling back")
        }
    }

    override fun start(
        language: String,
        onResult: (Recognition) -> Unit,
        onError: (String) -> Unit,
    ) {
        this.language = language
        this.onResult = onResult
        this.onError = onError

        if (!isAvailable) {
            onError("bundled speech model unavailable")
            return
        }
        warmUp()
        if (!capture.start()) {
            onError("microphone unavailable")
            return
        }
        listening = true
    }

    override fun stop() {
        if (!listening) return
        listening = false
        val audio = capture.stopAndTrim()
        val resultCallback = onResult
        val errorCallback = onError

        if (audio == null) {
            main.post { errorCallback?.invoke("no speech detected — press and hold, then speak") }
            return
        }

        worker.execute {
            val whisper = engine ?: WhisperEngine.load(context)?.also { engine = it }
            if (whisper == null) {
                main.post { errorCallback?.invoke("speech model failed to load") }
                return@execute
            }

            val result = whisper.transcribe(
                audio,
                language,
                grammar = WhisperEngine.grammarFor(context, language),
            )
            main.post {
                when {
                    result == null || result.text.isBlank() ->
                        errorCallback?.invoke("message unclear — press and hold again")

                    else -> resultCallback?.invoke(
                        Recognition(
                            text = result.text,
                            confidence = result.confidence,
                            noSpeechProb = result.noSpeechProb,
                            isDegenerate = result.isDegenerate,
                            engine = "whisper.cpp",
                            inferenceMillis = result.inferenceMillis,
                        )
                    )
                }
            }
        }
    }

    override fun release() {
        listening = false
        capture.stopAndTrim()
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "iTantraWhisper"
    }
}
