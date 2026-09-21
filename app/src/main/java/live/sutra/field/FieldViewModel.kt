package live.sutra.field

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.androidengineers.agent_quickstart_android.data.ConversationAgoraApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.itantra.speech.Languages
import live.sutra.codec.Chunking
import live.sutra.codec.Prosody
import live.sutra.codec.SutraWire
import live.sutra.link.DeliveryTracker
import live.sutra.link.SutraLink

class FieldViewModel(application: Application) : AndroidViewModel(application) {

    data class Message(
        val sequence: Int,
        val text: String,
        val language: String,
        val outgoing: Boolean,
        val bytes: Int,
        val urgent: Boolean,
        val bitsPerSecond: Double = 0.0,
        val delivery: DeliveryTracker.State? = null,
        val timestampMillis: Long = System.currentTimeMillis(),
    )

    data class UiState(
        val configured: Boolean = QuickstartConfig.isConfigured,
        val configMessage: String? = QuickstartConfig.startupHelpMessage(),
        val channel: String = DEFAULT_CHANNEL,
        val language: String = "hi",
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val listening: Boolean = false,
        val busy: Boolean = false,
        val speaking: Boolean = false,
        val modelReady: Boolean = false,
        val voiceReady: Boolean = false,
        val voiceStatus: ProsodicSpeaker.VoiceStatus = ProsodicSpeaker.VoiceStatus.NO_ENGINE,
        val status: String = "Not connected",
        val error: String? = null,
        val messages: List<Message> = emptyList(),
        val link: SutraLink.Stats = SutraLink.Stats(),
        val simulatedLossPercent: Int = 0,
        val lastRecognitionMillis: Long = 0,
        val lastAnalysisMillis: Long = 0,

        val capabilities: List<SpeechRouting.Capability> = emptyList(),
        val lastEngine: SpeechRouting.Engine? = null,
    )

    private val api = ConversationAgoraApi()
    private val link = SutraLink(application)
    private val speech = FieldSpeech(application)
    private val tracker = DeliveryTracker()
    private var speaker: ProsodicSpeaker? = null
    private var sequence = 1
    private var testIndex = 0

    private val sentAtMillis = LinkedHashMap<Int, Long>()

    @Volatile private var reconnecting = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        speaker = ProsodicSpeaker(application) { ready ->
            _state.value = _state.value.copy(
                voiceReady = ready,
                voiceStatus = speaker?.statusFor(_state.value.language)
                    ?: ProsodicSpeaker.VoiceStatus.NO_ENGINE,
            )
        }.also { it.onSpeakingChanged = { speaking -> _state.value = _state.value.copy(speaking = speaking) } }

        _state.value = _state.value.copy(
            modelReady = speech.isModelAvailable,
            capabilities = speech.survey(),
        )
        speech.warmUp()

        speech.refreshPlatformLanguages {
            _state.value = _state.value.copy(capabilities = speech.survey())
        }

        link.onFrame = { uid, frame -> onFrameReceived(uid, frame) }
        link.onConnectionLost = {

            viewModelScope.launch { rejoin() }
        }
        viewModelScope.launch {
            link.stats.collect { stats -> _state.value = _state.value.copy(link = stats) }
        }
        viewModelScope.launch { retryLoop() }
    }

    fun setChannel(value: String) {
        _state.value = _state.value.copy(channel = value.trim())
    }

    fun setLanguage(value: String) {
        _state.value = _state.value.copy(
            language = value,
            voiceStatus = speaker?.statusFor(value) ?: ProsodicSpeaker.VoiceStatus.NO_ENGINE,
        )
    }

    fun setSimulatedLoss(percent: Int) {
        link.simulatedLossPercent = percent
        _state.value = _state.value.copy(simulatedLossPercent = percent)
    }

    fun connect() {
        if (_state.value.connecting || _state.value.connected) return
        val channel = _state.value.channel.ifBlank { DEFAULT_CHANNEL }
        _state.value = _state.value.copy(connecting = true, error = null, status = "Joining $channel…")
        viewModelScope.launch {
            runCatching {
                val bootstrap = withContext(Dispatchers.IO) { api.requestSessionBootstrap(channel) }
                link.connect(
                    appId = bootstrap.appId,
                    channel = bootstrap.channel,
                    token = bootstrap.rtcToken,
                    uid = bootstrap.uid.toIntOrNull() ?: 0,
                )
            }.onSuccess {
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    status = "On the thin link — no audio, frames only",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    status = "Not connected",
                    error = error.message ?: "could not join the channel",
                )
            }
        }
    }

    private suspend fun rejoin() {
        if (reconnecting) return
        reconnecting = true
        var attempt = 0
        try {
            while (_state.value.connected.not() && attempt < MAX_REJOIN_ATTEMPTS) {
                attempt++
                _state.value = _state.value.copy(
                    status = "Link lost — reconnecting (attempt $attempt)",
                    connected = false,
                )
                delay(REJOIN_BACKOFF_MILLIS * minOf(attempt, 6))
                val channel = _state.value.channel.ifBlank { DEFAULT_CHANNEL }
                val ok = runCatching {
                    val bootstrap = withContext(Dispatchers.IO) { api.requestSessionBootstrap(channel) }
                    link.connect(
                        appId = bootstrap.appId,
                        channel = bootstrap.channel,
                        token = bootstrap.rtcToken,
                        uid = bootstrap.uid.toIntOrNull() ?: 0,
                    )
                }.isSuccess
                if (ok) {

                    val now = System.currentTimeMillis()
                    tracker.due(now + RETRY_TICK_MILLIS).forEach { link.send(it.frame) }
                    _state.value = _state.value.copy(
                        connected = true,
                        status = "Link back — resending anything unacknowledged",
                    )
                    return
                }
            }
            if (!_state.value.connected) {
                _state.value = _state.value.copy(status = "No link. Tap Join link when you have signal.")
            }
        } finally {
            reconnecting = false
        }
    }

    fun disconnect() {
        link.disconnect()
        tracker.clear()
        _state.value = _state.value.copy(connected = false, status = "Not connected")
    }

    fun sendTestUtterance() {
        if (!_state.value.connected) return
        val text = TEST_UTTERANCES[testIndex % TEST_UTTERANCES.size]
        testIndex++
        val words = live.sutra.codec.Words.split(text).size
        val prosody = Prosody.neutral(words).copy(urgency = if (testIndex % 2 == 0) 6 else 2)
        handleRecognition(
            FieldSpeech.Result.Ready(
                FieldSpeech.Utterance(
                    text = text,
                    prosody = prosody,
                    confidence = 1f,
                    audioSeconds = 4.0,
                    speechSeconds = 3.6,
                    recognitionMillis = 0,
                    analysisMillis = 0,
                )
            )
        )
    }

    fun startTalking() {
        if (!_state.value.connected || _state.value.listening || _state.value.busy) return
        speaker?.stop()
        val language = _state.value.language
        val capability = speech.capabilityFor(language)
        if (!capability.canSpeak) {
            _state.value = _state.value.copy(
                error = "No offline recogniser for this language on this device. " +
                    "Hindi uses the bundled model; the others need the device's own speech pack."
            )
            return
        }
        val started = speech.startListening(language) { result ->

            viewModelScope.launch {
                _state.value = _state.value.copy(listening = false, busy = true)
                handleRecognition(result)
            }
        }
        if (!started) {
            _state.value = _state.value.copy(error = "microphone unavailable")
            return
        }
        _state.value = _state.value.copy(listening = true, error = null, status = "Listening…")
    }

    fun stopTalking() {
        if (!_state.value.listening) return
        _state.value = _state.value.copy(listening = false, busy = true, status = "Recognizing on device…")
        speech.stopAndRecognize(_state.value.language) { result ->
            viewModelScope.launch { handleRecognition(result) }
        }
    }

    private fun handleRecognition(result: FieldSpeech.Result) {
        when (result) {
            is FieldSpeech.Result.Rejected ->
                _state.value = _state.value.copy(busy = false, status = "Ready", error = result.reason)

            is FieldSpeech.Result.Ready -> {
                val utterance = result.utterance
                val frames = runCatching {
                    Chunking.frames(
                        text = utterance.text,
                        language = _state.value.language,
                        prosody = utterance.prosody,
                        firstSequence = sequence,
                        flags = if (containsNumber(utterance.text)) SutraWire.FLAG_CRITICAL else 0,
                    )
                }.getOrElse { error ->
                    _state.value = _state.value.copy(busy = false, error = error.message)
                    return
                }

                val perFrameSeconds = utterance.speechSeconds / frames.size.coerceAtLeast(1)
                val appended = _state.value.messages.toMutableList()
                frames.forEach { frame ->
                    val packet = SutraWire.decode(frame)
                    val sentAt = System.currentTimeMillis()
                    link.send(frame, perFrameSeconds)
                    tracker.onSent(packet.sequence, frame, sentAt)
                    sentAtMillis[packet.sequence] = sentAt
                    Log.i(TAG, "sent seq=${packet.sequence} bytes=${frame.size} speech=%.2fs".format(perFrameSeconds))
                    appended += Message(
                        sequence = packet.sequence,
                        text = packet.text.orEmpty(),
                        language = _state.value.language,
                        outgoing = true,
                        bytes = frame.size,
                        urgent = packet.isUrgent,
                        bitsPerSecond = if (perFrameSeconds > 0) frame.size * 8 / perFrameSeconds else 0.0,
                        delivery = DeliveryTracker.State.PENDING,
                    )
                    sequence = (sequence + 1) and 0xFFFF
                }
                _state.value = _state.value.copy(
                    busy = false,
                    lastEngine = utterance.engine,
                    status = if (utterance.speechSeconds > 0)
                        "Sent ${frames.sumOf { it.size }} bytes for %.1f s of speech".format(utterance.speechSeconds)
                    else "Sent ${frames.sumOf { it.size }} bytes (device recogniser, no prosody)",
                    messages = appended.takeLast(MAX_MESSAGES),
                    lastRecognitionMillis = utterance.recognitionMillis,
                    lastAnalysisMillis = utterance.analysisMillis,
                    error = null,
                )
            }
        }
    }

    private fun onFrameReceived(uid: Int, frame: ByteArray) {
        val packet = runCatching { SutraWire.decode(frame) }.getOrElse { error ->

            _state.value = _state.value.copy(error = "dropped a corrupt frame: ${error.message}")
            return
        }
        when (packet.type) {
            SutraWire.TYPE_ACK -> {
                if (tracker.onAck(packet.sequence)) {
                    val rtt = sentAtMillis.remove(packet.sequence)?.let { System.currentTimeMillis() - it }
                    Log.i(TAG, "ack seq=${packet.sequence} rttMs=${rtt ?: -1}")
                    markDelivered(packet.sequence, DeliveryTracker.State.DELIVERED)
                }
            }

            SutraWire.TYPE_UTTERANCE -> {
                val text = packet.text.orEmpty()
                val language = packet.language ?: _state.value.language
                link.send(SutraWire.encodeAck(packet.sequence))
                speaker?.speak(text, language, packet.prosody ?: Prosody.neutral(text.split(" ").size))
                _state.value = _state.value.copy(
                    messages = (_state.value.messages + Message(
                        sequence = packet.sequence,
                        text = text,
                        language = language,
                        outgoing = false,
                        bytes = frame.size,
                        urgent = packet.isUrgent,
                    )).takeLast(MAX_MESSAGES),
                    status = if (packet.isUrgent) "Urgent instruction received" else "Instruction received",
                )
            }
        }
    }

    private fun markDelivered(sequence: Int, state: DeliveryTracker.State) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.sequence == sequence && it.outgoing) it.copy(delivery = state) else it
            }
        )
    }

    private suspend fun retryLoop() {
        while (true) {
            delay(RETRY_TICK_MILLIS)
            if (!_state.value.connected) continue
            val now = System.currentTimeMillis()
            tracker.due(now).forEach { entry ->
                link.send(entry.frame)
                markDelivered(entry.sequence, DeliveryTracker.State.PENDING)
            }
            _state.value.messages.filter { it.outgoing && it.delivery == DeliveryTracker.State.PENDING }
                .forEach { message ->
                    if (tracker.state(message.sequence) == DeliveryTracker.State.LOST) {
                        markDelivered(message.sequence, DeliveryTracker.State.LOST)
                    }
                }
        }
    }

    private fun containsNumber(text: String): Boolean = text.any { it.isDigit() } ||
        NUMBER_WORDS.any { text.contains(it) }

    override fun onCleared() {
        speech.release()
        speaker?.shutdown()
        link.disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SutraField"
        const val DEFAULT_CHANNEL = "sutra-demo-01"

        val LANGUAGES: Map<String, String> =
            SutraWire.LANGUAGES.associateWith { Languages.NAMES[it] ?: it }
        private const val MAX_MESSAGES = 60
        private const val RETRY_TICK_MILLIS = 1_000L
        private const val REJOIN_BACKOFF_MILLIS = 2_000L
        private const val MAX_REJOIN_ATTEMPTS = 20

        val TEST_UTTERANCES = listOf(
            "पानी घर में घुस गया है दो बच्चे छत पर हैं जल्दी आओ",
            "स्कूल के पास पाँच लोग फंसे हैं एक बुजुर्ग को चोट लगी है",
            "सड़क बंद है पुल टूट गया है दूसरे रास्ते से आना",
        )

        private val NUMBER_WORDS = listOf(
            "एक", "दो", "तीन", "चार", "पाँच", "पांच", "छह", "सात", "आठ", "नौ", "दस",
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        )
    }
}
