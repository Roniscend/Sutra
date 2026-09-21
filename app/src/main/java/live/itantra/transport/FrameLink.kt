package live.itantra.transport

import live.itantra.domain.Wire
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FrameLink(
    private val transport: Transport,
    private val timeoutMillis: Long = ACK_TIMEOUT_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "itantra-link").apply { isDaemon = true }
        },
) {

    private class Pending(
        val frame: ByteArray,
        var attempts: Int,
        var timer: ScheduledFuture<*>?,
    )

    private val pending = ConcurrentHashMap<Int, Pending>()

    private var onMessage: ((ByteArray) -> Unit)? = null
    private var onDelivered: ((Int) -> Unit)? = null
    private var onLost: ((Int) -> Unit)? = null

    init {
        transport.onFrame { raw -> handleIncoming(raw) }
    }

    fun onMessage(handler: (ByteArray) -> Unit) { onMessage = handler }

    fun onDelivered(handler: (Int) -> Unit) { onDelivered = handler }

    fun onLost(handler: (Int) -> Unit) { onLost = handler }

    fun send(frame: ByteArray) {
        val sequence = sequenceOf(frame) ?: run { transport.send(frame); return }
        val entry = Pending(frame, attempts = 1, timer = null)
        pending[sequence] = entry
        transport.send(frame)
        entry.timer = schedule(sequence)
    }

    private fun schedule(sequence: Int): ScheduledFuture<*>? = runCatching {
        scheduler.schedule({ retry(sequence) }, timeoutMillis, TimeUnit.MILLISECONDS)
    }.getOrNull()

    private fun retry(sequence: Int) {
        val entry = pending[sequence] ?: return
        if (entry.attempts >= maxAttempts) {
            pending.remove(sequence)
            onLost?.invoke(sequence)
            return
        }
        entry.attempts++

        transport.send(withRetransmitFlag(entry.frame))
        entry.timer = schedule(sequence)
    }

    private fun handleIncoming(raw: ByteArray) {
        val frame = try {
            Wire.decode(raw)
        } catch (e: Wire.CorruptFrameException) {

            return
        }

        if (frame.type == Wire.TYPE_ACK) {
            pending.remove(frame.sequence)?.let { entry ->
                entry.timer?.cancel(false)
                onDelivered?.invoke(frame.sequence)
            }
            return
        }

        transport.send(Wire.encodeAck(frame.sequence))
        onMessage?.invoke(raw)
    }

    fun close() {
        pending.values.forEach { it.timer?.cancel(false) }
        pending.clear()
        scheduler.shutdownNow()
    }

    val outstanding: Int get() = pending.size

    private companion object {
        const val ACK_TIMEOUT_MS = 1500L
        const val MAX_ATTEMPTS = 3

        fun sequenceOf(frame: ByteArray): Int? =
            if (frame.size < 8) null
            else ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)

        fun withRetransmitFlag(frame: ByteArray): ByteArray {
            val copy = frame.copyOf()
            copy[3] = (copy[3].toInt() or Wire.FLAG_RETRANSMIT).toByte()
            val crc = Wire.crc16(copy, copy.size - 2)
            copy[copy.size - 2] = ((crc shr 8) and 0xFF).toByte()
            copy[copy.size - 1] = (crc and 0xFF).toByte()
            return copy
        }
    }
}
