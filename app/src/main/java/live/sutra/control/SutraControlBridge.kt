package live.sutra.control

import android.util.Log
import com.androidengineers.agent_quickstart_android.data.ConversationRepository
import com.androidengineers.agent_quickstart_android.rtc.AgoraConversationSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.sutra.codec.Chunking
import live.sutra.codec.Prosody
import live.sutra.codec.SutraWire
import live.sutra.data.SutraApi

class SutraControlBridge(
    private val session: AgoraConversationSessionManager,
    private val repository: ConversationRepository = ConversationRepository(),
    private val api: SutraApi = SutraApi(),
    private val scope: CoroutineScope,
) {

    data class Report(
        val sequence: Int,
        val fromUid: Int,
        val original: String,
        val translated: String,
        val language: String,
        val urgency: Int,
        val frameBytes: Int,
        val translationVendor: String? = null,
        val translationMillis: Int? = null,
        val spoken: Boolean = false,
    ) {
        val isUrgent: Boolean get() = urgency >= Prosody.URGENT_THRESHOLD
    }

    data class Outgoing(
        val id: Int,
        val text: String,
        val language: String,
        val urgent: Boolean,
        val frameBytes: Int,
    )

    data class State(
        val channel: String? = null,
        val agentId: String? = null,
        val reports: List<Report> = emptyList(),
        val sent: List<Outgoing> = emptyList(),
        val framesIn: Int = 0,
        val framesOut: Int = 0,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var lastOutboundId = 0
    private var sequence = 40_000

    fun start(channel: String, agentId: String?) {
        stop()
        _state.value = State(channel = channel, agentId = agentId)
        session.onSutraFrame = { uid, frame -> onFrame(uid, frame) }
        pollJob = scope.launch { pollOutbound(channel) }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        session.onSutraFrame = null
        lastOutboundId = 0
    }

    fun updateAgentId(agentId: String?) {
        _state.value = _state.value.copy(agentId = agentId)
    }

    private fun onFrame(uid: Int, frame: ByteArray) {
        val packet = runCatching { SutraWire.decode(frame) }.getOrElse { error ->
            _state.value = _state.value.copy(lastError = "dropped a corrupt field frame: ${error.message}")
            return
        }
        if (packet.type != SutraWire.TYPE_UTTERANCE) return
        val channel = _state.value.channel ?: return

        session.sendSutraFrame(SutraWire.encodeAck(packet.sequence))

        val text = packet.text.orEmpty()
        val prosody = packet.prosody
        val report = Report(
            sequence = packet.sequence,
            fromUid = uid,
            original = text,
            translated = text,
            language = packet.language ?: "hi",
            urgency = prosody?.urgency ?: 0,
            frameBytes = frame.size,
        )
        _state.value = _state.value.copy(
            reports = (_state.value.reports + report).takeLast(MAX_REPORTS),
            framesIn = _state.value.framesIn + 1,
            bytesIn = _state.value.bytesIn + frame.size,
            lastError = null,
        )

        scope.launch(Dispatchers.IO) {

            val translation = runCatching {
                api.reportFieldMessage(
                    channelName = channel,
                    fromUid = uid,
                    language = report.language,
                    text = text,
                    urgency = report.urgency,
                )
            }.onSuccess { result ->
                updateReport(packet.sequence) {
                    it.copy(
                        translated = result.translated,
                        translationVendor = result.vendor,
                        translationMillis = result.latencyMs,
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "translation failed; the field's own words stand", error)
                _state.value = _state.value.copy(lastError = "translation failed: ${error.message}")
            }.getOrNull()

            val agentId = _state.value.agentId ?: return@launch

            runCatching {
                repository.sendText(
                    agentId = agentId,
                    channelName = channel,
                    text = translation?.translated ?: text,
                    speak = true,
                    append = !report.isUrgent,
                )
            }.onSuccess {
                updateReport(packet.sequence) { it.copy(spoken = true) }
            }.onFailure { error ->
                Log.w(TAG, "the agent could not speak the report", error)
                _state.value = _state.value.copy(lastError = "agent could not speak it: ${error.message}")
            }
        }
    }

    private suspend fun pollOutbound(channel: String) {
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            runCatching { api.pendingOutbound(channel, lastOutboundId) }
                .onSuccess { messages ->
                    messages.forEach { message ->
                        lastOutboundId = maxOf(lastOutboundId, message.id)
                        sendToField(message)
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(lastError = error.message ?: "outbound poll failed")
                }
        }
    }

    private fun sendToField(message: SutraApi.OutboundMessage) {
        val urgency = if (message.urgent) 7 else 0
        val frames = runCatching {
            Chunking.frames(
                text = message.text,
                language = message.language,

                prosody = Prosody.neutral(live.sutra.codec.Words.split(message.text).size)
                    .copy(urgency = urgency),
                firstSequence = sequence,
                flags = if (message.urgent) SutraWire.FLAG_URGENT else 0,
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(lastError = "could not encode the instruction: ${error.message}")
            return
        }

        frames.forEach { frame ->
            if (!session.sendSutraFrame(frame)) {
                _state.value = _state.value.copy(lastError = "the data stream refused a frame")
                return@forEach
            }
            sequence = (sequence + 1) and 0xFFFF
            _state.value = _state.value.copy(
                framesOut = _state.value.framesOut + 1,
                bytesOut = _state.value.bytesOut + frame.size,
                sent = (_state.value.sent + Outgoing(
                    id = message.id,
                    text = message.text,
                    language = message.language,
                    urgent = message.urgent,
                    frameBytes = frame.size,
                )).takeLast(MAX_REPORTS),
            )
        }
    }

    private fun updateReport(sequence: Int, transform: (Report) -> Report) {
        _state.value = _state.value.copy(
            reports = _state.value.reports.map { if (it.sequence == sequence) transform(it) else it }
        )
    }

    private companion object {
        const val TAG = "SutraControl"
        const val MAX_REPORTS = 50

        const val POLL_INTERVAL_MILLIS = 2_500L
    }
}
