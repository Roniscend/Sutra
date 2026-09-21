package live.itantra.transport

enum class LinkState { OFFLINE, LISTENING, CONNECTING, CONNECTED, FAILED }

data class LinkStatus(
    val state: LinkState = LinkState.OFFLINE,

    val detail: String = "",
) {
    val isConnected: Boolean get() = state == LinkState.CONNECTED
}

interface Transport {
    val name: String
    fun status(): LinkStatus

    fun observe(listener: (LinkStatus) -> Unit)

    fun onFrame(handler: (ByteArray) -> Unit)
    fun send(frame: ByteArray)
    fun close()
}

class LoopbackTransport : Transport {

    private var handler: ((ByteArray) -> Unit)? = null
    private var listener: ((LinkStatus) -> Unit)? = null
    private val status = LinkStatus(LinkState.CONNECTED, "loopback (this device)")

    override val name: String = "loopback"
    override fun status(): LinkStatus = status

    override fun observe(listener: (LinkStatus) -> Unit) {
        this.listener = listener
        listener(status)
    }

    override fun onFrame(handler: (ByteArray) -> Unit) {
        this.handler = handler
    }

    override fun send(frame: ByteArray) {
        handler?.invoke(frame)
    }

    override fun close() {
        handler = null
        listener = null
    }
}
