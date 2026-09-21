package live.itantra.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

object Languages {
    val TAGS = mapOf(
        "hi" to "hi-IN", "bn" to "bn-IN", "gu" to "gu-IN", "or" to "or-IN",
        "mr" to "mr-IN", "ta" to "ta-IN", "te" to "te-IN", "kn" to "kn-IN",
        "ml" to "ml-IN", "en" to "en-IN",
    )

    val NAMES = mapOf(
        "hi" to "हिन्दी", "bn" to "বাংলা", "gu" to "ગુજરાતી", "or" to "ଓଡ଼ିଆ",
        "mr" to "मराठी", "ta" to "தமிழ்", "te" to "తెలుగు", "kn" to "ಕನ್ನಡ",
        "ml" to "മലയാളം", "en" to "English",
    )

    fun locale(code: String): Locale = Locale.forLanguageTag(TAGS[code] ?: "en-IN")
}

class Speaker(context: Context, private val onReady: () -> Unit = {}) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) onReady()
        }
    }

    fun supports(language: String): Boolean {
        val tts = engine ?: return false
        if (!ready) return false
        val result = tts.isLanguageAvailable(Languages.locale(language))
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    fun speak(text: String, language: String, alert: Boolean) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return

        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(
                    if (alert) AudioAttributes.USAGE_ALARM
                    else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.language = Languages.locale(language)
        tts.setSpeechRate(if (alert) 0.9f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "itantra-${System.nanoTime()}")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

data class Recognition(
    val text: String,
    val confidence: Float,
    val noSpeechProb: Float = 0f,

    val isDegenerate: Boolean = false,
    val engine: String = "platform",
    val inferenceMillis: Long = 0,
)

interface Recognizer {
    val isAvailable: Boolean
    fun start(language: String, onResult: (Recognition) -> Unit, onError: (String) -> Unit)
    fun stop()
    fun release()
}

class OnDeviceRecognizer(private val context: Context) : Recognizer {

    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(
        language: String,
        onResult: (Recognition) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isAvailable) {
            onError("on-device recognition unavailable on this device")
            return
        }
        stop()
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val text = texts?.firstOrNull().orEmpty()
                val confidence = scores?.firstOrNull() ?: -1f
                if (text.isBlank()) onError("no speech recognised")
                else onResult(Recognition(text, confidence))
            }

            override fun onError(error: Int) = onError(describe(error))
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Languages.TAGS[language] ?: "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        sr.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_NO_MATCH -> "message unclear — press and hold again"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language pack not installed on this device"
        else -> "recognition error ($error)"
    }
}
