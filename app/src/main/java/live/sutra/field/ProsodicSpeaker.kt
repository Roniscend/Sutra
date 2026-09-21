package live.sutra.field

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import live.itantra.speech.Languages
import live.sutra.codec.Prosody
import live.sutra.codec.Words

class ProsodicSpeaker(context: Context, private val onReady: (Boolean) -> Unit = {}) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var speaking = false

    @Volatile var voices: Map<String, VoiceStatus> = emptyMap()
        private set

    enum class VoiceStatus { AVAILABLE, MISSING_DATA, NOT_SUPPORTED, NO_ENGINE }

    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) surveyVoices()
            onReady(ready)
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = setSpeaking(true)
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.endsWith(LAST_SUFFIX) == true) {
                        abandonFocus()
                        setSpeaking(false)
                    }
                }

                @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
                override fun onError(utteranceId: String?) = setSpeaking(false)
                override fun onError(utteranceId: String?, errorCode: Int) = setSpeaking(false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = setSpeaking(false)
            })
        }
    }

    private fun setSpeaking(value: Boolean) {
        speaking = value
        onSpeakingChanged?.invoke(value)
    }

    private fun surveyVoices() {
        val tts = engine ?: return
        voices = Languages.TAGS.keys.associateWith { language ->
            when (tts.isLanguageAvailable(Languages.locale(language))) {
                TextToSpeech.LANG_MISSING_DATA -> VoiceStatus.MISSING_DATA
                TextToSpeech.LANG_NOT_SUPPORTED -> VoiceStatus.NOT_SUPPORTED
                else -> VoiceStatus.AVAILABLE
            }
        }
        Log.i(TAG, "engine=${tts.defaultEngine} voices=" + voices.entries.joinToString { "${it.key}:${it.value}" })
    }

    fun statusFor(language: String): VoiceStatus =
        if (engine == null || !ready) VoiceStatus.NO_ENGINE
        else voices[language] ?: VoiceStatus.NOT_SUPPORTED

    fun supports(language: String): Boolean {
        val tts = engine ?: return false
        if (!ready) return false
        return tts.isLanguageAvailable(Languages.locale(language)) >= TextToSpeech.LANG_AVAILABLE
    }

    fun speak(text: String, language: String, prosody: Prosody) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return

        val urgent = prosody.isUrgent
        val attributes = AudioAttributes.Builder()
            .setUsage(if (urgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts.setAudioAttributes(attributes)

        requestFocus(attributes, urgent)
        val available = tts.setLanguage(Languages.locale(language))
        if (available < TextToSpeech.LANG_AVAILABLE) {

            Log.w(TAG, "no voice for $language (code $available); the message will not be spoken")
            return
        }

        val words = Words.split(text)
        val levels = if (prosody.words.size == words.size) prosody.words else List(words.size) { Prosody.Word.NEUTRAL }
        val runs = groupIntoRuns(words, levels)
        var queueMode = TextToSpeech.QUEUE_FLUSH
        val id = System.nanoTime()

        runs.forEachIndexed { index, run ->
            val last = index == runs.lastIndex
            tts.setPitch(pitchFor(run.pitch, prosody))
            tts.setSpeechRate(rateFor(run.energy, prosody))
            tts.speak(
                run.text,
                queueMode,
                null,
                "sutra-$id-$index" + if (last) LAST_SUFFIX else "",
            )
            queueMode = TextToSpeech.QUEUE_ADD
            pauseFor(run.timing)?.let { tts.playSilentUtterance(it, TextToSpeech.QUEUE_ADD, "sutra-$id-gap-$index") }
        }
    }

    private fun requestFocus(attributes: AudioAttributes, urgent: Boolean) {
        abandonFocus()
        val gain = if (urgent) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, gain)
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus was refused ($result); the system may mute this message")
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun stop() {
        engine?.stop()
        abandonFocus()
        setSpeaking(false)
    }

    fun shutdown() {
        abandonFocus()
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private data class Run(
        val text: String,
        val pitch: Prosody.Pitch,
        val energy: Prosody.Energy,
        val timing: Prosody.Timing,
    )

    private fun groupIntoRuns(words: List<String>, levels: List<Prosody.Word>): List<Run> {
        val runs = mutableListOf<Run>()
        var buffer = StringBuilder()
        var current: Prosody.Word? = null
        words.forEachIndexed { index, word ->
            val level = levels[index]
            val breaks = current != null &&
                (level.pitch != current!!.pitch || level.energy != current!!.energy)
            if (breaks) {
                runs += Run(buffer.toString().trim(), current!!.pitch, current!!.energy, Prosody.Timing.PLAIN)
                buffer = StringBuilder()
            }
            buffer.append(word).append(' ')
            current = level
            val endsPause = level.timing == Prosody.Timing.SHORT_PAUSE || level.timing == Prosody.Timing.LONG_PAUSE
            if (endsPause || index == words.lastIndex) {
                runs += Run(buffer.toString().trim(), level.pitch, level.energy, level.timing)
                buffer = StringBuilder()
                current = null
            }
        }
        if (buffer.isNotBlank()) {
            val level = levels.lastOrNull() ?: Prosody.Word.NEUTRAL
            runs += Run(buffer.toString().trim(), level.pitch, level.energy, Prosody.Timing.PLAIN)
        }
        return runs.filter { it.text.isNotBlank() }
    }

    private fun pitchFor(pitch: Prosody.Pitch, prosody: Prosody): Float {
        val base = when {
            prosody.pitchBucket == 0 -> 1.0f

            prosody.medianPitchHz!! > 200f -> 1.12f
            prosody.medianPitchHz!! < 120f -> 0.9f
            else -> 1.0f
        }
        val shift = when (pitch) {
            Prosody.Pitch.LOW -> 0.88f
            Prosody.Pitch.MID -> 1.0f
            Prosody.Pitch.HIGH -> 1.15f
            Prosody.Pitch.RISING -> 1.22f
        }
        return (base * shift).coerceIn(0.5f, 2.0f)
    }

    private fun rateFor(energy: Prosody.Energy, prosody: Prosody): Float {

        val base = (prosody.syllablesPerSecond / 4.5f).coerceIn(0.75f, 1.45f)
        val emphasis = when (energy) {
            Prosody.Energy.SOFT -> 0.95f
            Prosody.Energy.NORMAL -> 1.0f
            Prosody.Energy.LOUD -> 1.05f
            Prosody.Energy.SHOUT -> 1.1f
        }
        return (base * emphasis).coerceIn(0.6f, 1.6f)
    }

    private fun pauseFor(timing: Prosody.Timing): Long? = when (timing) {
        Prosody.Timing.SHORT_PAUSE -> 250L
        Prosody.Timing.LONG_PAUSE -> 600L
        else -> null
    }

    private companion object {
        const val TAG = "SutraSpeaker"
        const val LAST_SUFFIX = "-last"
    }
}
