package live.sutra.link

import android.content.Context
import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.random.Random

class SutraLink(context: Context) {

    enum class Connection { IDLE, CONNECTING, CONNECTED, RECONNECTING, LOST }

    data class Stats(
        val connected: Boolean = false,
        val connection: Connection = Connection.IDLE,
        val channel: String? = null,
        val localUid: Int = 0,
        val peerUids: List<Int> = emptyList(),

        val channelTxKbps: Int = 0,
        val channelRxKbps: Int = 0,

        val framesSent: Int = 0,
        val framesReceived: Int = 0,
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        val lastFrameBytes: Int = 0,
        val lastFrameBitsPerSecond: Double = 0.0,
        val droppedBySimulation: Int = 0,
        val lastError: String? = null,
    )

    private val appContext = context.applicationContext
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var engine: RtcEngine? = null
    private var dataStreamId: Int = -1
    private var joinDeferred: CompletableDeferred<Int>? = null
    private var lastJoin: Join? = null

    private data class Join(val appId: String, val channel: String, val token: String, val uid: Int)

    var onConnectionLost: (() -> Unit)? = null

    var onFrame: ((uid: Int, frame: ByteArray) -> Unit)? = null

    @Volatile var simulatedLossPercent: Int = 0

    suspend fun connect(appId: String, channel: String, token: String, uid: Int) {
        disconnect()
        lastJoin = Join(appId, channel, token, uid)
        val config = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = appId
            mEventHandler = eventHandler
            mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
        }
        val created = RtcEngine.create(config)
            ?: throw IllegalStateException("Agora RTC engine failed to initialize")

        created.disableAudio()
        engine = created

        val deferred = CompletableDeferred<Int>()
        joinDeferred = deferred
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = false
            publishCameraTrack = false
            autoSubscribeAudio = false
            autoSubscribeVideo = false
            enableAudioRecordingOrPlayout = false
        }
        val result = created.joinChannel(token, channel, uid, options)
        if (result != Constants.ERR_OK) {
            joinDeferred = null
            throw IOException("RTC join failed (${RtcEngine.getErrorDescription(result)})")
        }
        try {
            withTimeout(JOIN_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            joinDeferred = null
        }

        dataStreamId = created.createDataStream(
            DataStreamConfig().apply {

                ordered = true
                syncWithAudio = false
            }
        )
        if (dataStreamId < 0) {
            throw IOException("could not open a data stream (${RtcEngine.getErrorDescription(dataStreamId)})")
        }
        _stats.value = _stats.value.copy(
            connected = true,
            connection = Connection.CONNECTED,
            channel = channel,
            localUid = uid,
        )
    }

    fun send(frame: ByteArray, speechSeconds: Double = 0.0): Boolean {
        val active = engine ?: return false
        if (dataStreamId < 0) return false
        if (simulatedLossPercent > 0 && Random.nextInt(100) < simulatedLossPercent) {
            _stats.value = _stats.value.copy(droppedBySimulation = _stats.value.droppedBySimulation + 1)
            Log.i(TAG, "simulated loss dropped a ${frame.size}-byte frame")
            return true
        }
        val result = active.sendStreamMessage(dataStreamId, frame)
        if (result != Constants.ERR_OK) {
            _stats.value = _stats.value.copy(lastError = RtcEngine.getErrorDescription(result))
            return false
        }
        val bits = if (speechSeconds > 0) frame.size * 8 / speechSeconds else 0.0
        _stats.value = _stats.value.copy(
            framesSent = _stats.value.framesSent + 1,
            bytesSent = _stats.value.bytesSent + frame.size,
            lastFrameBytes = frame.size,
            lastFrameBitsPerSecond = if (bits > 0) bits else _stats.value.lastFrameBitsPerSecond,
            lastError = null,
        )
        return true
    }

    fun disconnect() {
        engine?.let { active ->
            runCatching { active.leaveChannel() }
        }
        engine = null
        dataStreamId = -1
        runCatching { RtcEngine.destroy() }
        _stats.value = _stats.value.copy(
            connected = false,
            connection = Connection.IDLE,
            peerUids = emptyList(),
        )
    }

    private val eventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            joinDeferred?.complete(uid)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            _stats.value = _stats.value.copy(peerUids = (_stats.value.peerUids + uid).distinct())
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            _stats.value = _stats.value.copy(peerUids = _stats.value.peerUids - uid)
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            val frame = data ?: return
            _stats.value = _stats.value.copy(
                framesReceived = _stats.value.framesReceived + 1,
                bytesReceived = _stats.value.bytesReceived + frame.size,
            )
            onFrame?.invoke(uid, frame)
        }

        override fun onStreamMessageError(uid: Int, streamId: Int, error: Int, missed: Int, cached: Int) {
            _stats.value = _stats.value.copy(
                lastError = "stream error ${RtcEngine.getErrorDescription(error)} (missed $missed)"
            )
        }

        override fun onRtcStats(stats: RtcStats?) {
            val measured = stats ?: return
            _stats.value = _stats.value.copy(
                channelTxKbps = measured.txKBitRate,
                channelRxKbps = measured.rxKBitRate,
            )
        }

        override fun onError(err: Int) {
            _stats.value = _stats.value.copy(lastError = RtcEngine.getErrorDescription(err))
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            val connection = when (state) {
                Constants.CONNECTION_STATE_CONNECTED -> Connection.CONNECTED
                Constants.CONNECTION_STATE_CONNECTING -> Connection.CONNECTING
                Constants.CONNECTION_STATE_RECONNECTING -> Connection.RECONNECTING
                Constants.CONNECTION_STATE_FAILED -> Connection.LOST
                else -> Connection.IDLE
            }
            Log.i(TAG, "connection state=$connection reason=$reason")
            _stats.value = _stats.value.copy(
                connection = connection,
                connected = connection == Connection.CONNECTED,
            )
            if (connection == Connection.LOST) onConnectionLost?.invoke()
        }

        override fun onConnectionLost() {
            Log.w(TAG, "connection lost")
            _stats.value = _stats.value.copy(connected = false, connection = Connection.LOST)
            onConnectionLost?.invoke()
        }
    }

    private companion object {
        const val TAG = "SutraLink"
        const val JOIN_TIMEOUT_MILLIS = 15_000L
    }
}
