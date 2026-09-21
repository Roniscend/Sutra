package live.sutra.field

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
import live.itantra.speech.AudioCapture
import live.itantra.speech.OnDeviceRecognizer
import live.itantra.speech.WhisperEngine
import live.sutra.codec.Words
import live.sutra.codec.Prosody
import live.sutra.codec.ProsodyAnalyzer
import java.util.concurrent.Executors

class FieldSpeech(private val context: Context) {

    data class Utterance(
        val engine: SpeechRouting.Engine = SpeechRouting.Engine.WHISPER,
        val text: String,
        val prosody: Prosody,
        val confidence: Float,
        val audioSeconds: Double,
        val speechSeconds: Double,
        val recognitionMillis: Long,
        val analysisMillis: Long,
    )

    sealed interface Result {
        data class Ready(val utterance: Utterance) : Result

        data class Rejected(val reason: String) : Result
    }

    private val capture = AudioCapture()
    private val platform = OnDeviceRecognizer(context)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sutra-field-speech").apply { isDaemon = true }
    }

    @Volatile private var engine: WhisperEngine? = null
    @Volatile private var capturing = false
    @Volatile private var platformListening = false
    @Volatile private var platformLanguages: Set<String> = emptySet()

    val isModelAvailable: Boolean get() = WhisperEngine.isSupported(context)

    fun capabilityFor(language: String) =
        SpeechRouting.capabilityFor(language, isModelAvailable, platformLanguages)

    fun survey() = SpeechRouting.survey(isModelAvailable, platformLanguages)

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

    fun warmUp() {
        if (engine != null || !isModelAvailable) return
        worker.execute {
            if (engine == null) engine = WhisperEngine.load(context)
        }
    }

    fun startListening(language: String, onPlatformResult: (Result) -> Unit = {}): Boolean {
        return when (capabilityFor(language).engine) {
            SpeechRouting.Engine.WHISPER -> {
                warmUp()
                capturing = capture.start()
                capturing
            }

            SpeechRouting.Engine.PLATFORM -> {
                platformListening = true
                listenWithPlatform(language, onPlatformResult)
                true
            }
            SpeechRouting.Engine.NONE -> false
        }
    }

    private fun listenWithPlatform(language: String, onResult: (Result) -> Unit) {
        platform.start(
            language,
            onResult = { recognition ->
                platformListening = false
                val text = recognition.text.trim()
                if (text.isBlank()) {
                    onResult(Result.Rejected("message unclear - press and hold again"))
                } else {
                    onResult(
                        Result.Ready(
                            Utterance(
                                engine = SpeechRouting.Engine.PLATFORM,
                                text = text,

                                prosody = Prosody.neutral(Words.split(text).size),
                                confidence = recognition.confidence.takeIf { it >= 0f } ?: 1f,
                                audioSeconds = 0.0,
                                speechSeconds = 0.0,
                                recognitionMillis = recognition.inferenceMillis,
                                analysisMillis = 0,
                            )
                        )
                    )
                }
            },
            onError = { reason ->
                platformListening = false
                onResult(Result.Rejected(reason))
            },
        )
    }

    val micLevel: Float get() = capture.peakLevel

    fun stopAndRecognize(language: String, onResult: (Result) -> Unit) {
        if (platformListening) {

            platform.stop()
            return
        }
        if (!capturing) {
            onResult(Result.Rejected("not listening"))
            return
        }
        capturing = false
        val audio = capture.stopAndTrim()
        if (audio == null) {
            onResult(Result.Rejected("no speech detected — press and hold, then speak"))
            return
        }
        worker.execute {
            val whisper = engine ?: WhisperEngine.load(context)?.also { engine = it }
            if (whisper == null) {
                onResult(Result.Rejected("speech model failed to load"))
                return@execute
            }

            val recognition = whisper.transcribe(audio, language)
            val text = recognition?.text?.trim().orEmpty()
            when {
                recognition == null || text.isBlank() ->
                    onResult(Result.Rejected("message unclear — press and hold again"))

                recognition.isDegenerate ->
                    onResult(Result.Rejected("heard only repetition — press and hold again"))

                recognition.confidence < MIN_CONFIDENCE ->
                    onResult(Result.Rejected("too unclear to send (confidence %.2f)".format(recognition.confidence)))

                else -> {
                    val started = System.nanoTime()
                    val analysis = ProsodyAnalyzer.analyze(audio, text)
                    val analysisMillis = (System.nanoTime() - started) / 1_000_000
                    Log.i(TAG, "utterance '${text.take(40)}' conf=${recognition.confidence} urgency=${analysis.prosody.urgency}")
                    onResult(
                        Result.Ready(
                            Utterance(
                                engine = SpeechRouting.Engine.WHISPER,
                                text = text,
                                prosody = analysis.prosody,
                                confidence = recognition.confidence,
                                audioSeconds = audio.size.toDouble() / ProsodyAnalyzer.SAMPLE_RATE,
                                speechSeconds = analysis.speechSeconds,
                                recognitionMillis = recognition.inferenceMillis,
                                analysisMillis = analysisMillis,
                            )
                        )
                    )
                }
            }
        }
    }

    fun release() {
        capturing = false
        platformListening = false
        platform.release()
        capture.stopAndTrim()
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "SutraFieldSpeech"

        const val MIN_CONFIDENCE = 0.25f
    }
}
