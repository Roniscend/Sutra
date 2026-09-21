package live.itantra.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wire {

    const val MAGIC: Byte = 0x49
    const val VERSION: Byte = 0x01

    const val TYPE_TEMPLATED: Byte = 0x01
    const val TYPE_FREETEXT: Byte = 0x02
    const val TYPE_ACK: Byte = 0x03
    const val TYPE_PTT_STATE: Byte = 0x04

    const val FLAG_ALERT: Int = 0x01
    const val FLAG_RETRANSMIT: Int = 0x02

    private const val HEADER_SIZE = 8
    private const val CRC_SIZE = 2

    private val SLOT_ORDER = listOf("n", "loc")

    class CorruptFrameException(message: String) : Exception(message)

    data class Frame(
        val type: Byte,
        val sequence: Int,
        val flags: Int,
        val messageId: Int? = null,
        val slots: Map<String, Int> = emptyMap(),
        val text: String? = null,
        val language: String? = null,
    ) {
        val isAlert: Boolean get() = flags and FLAG_ALERT != 0
        val isTemplated: Boolean get() = type == TYPE_TEMPLATED
    }

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun encodeTemplated(messageId: Int, slots: Map<String, Int>, sequence: Int, alert: Boolean): ByteArray {
        var mask = 0
        val values = mutableListOf<Int>()
        SLOT_ORDER.forEachIndexed { bit, name ->
            slots[name]?.let { mask = mask or (1 shl bit); values.add(it) }
        }
        val payload = ByteBuffer.allocate(3 + values.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(messageId.toShort())
            put(mask.toByte())
            values.forEach { putShort(it.toShort()) }
        }.array()
        return frame(TYPE_TEMPLATED, if (alert) FLAG_ALERT else 0, sequence, payload)
    }

    fun encodeFreeText(text: String, languageTag: String, sequence: Int, alert: Boolean = false): ByteArray {
        val tag = languageTag.toByteArray(Charsets.US_ASCII).take(15).toByteArray()
        val body = text.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(1 + tag.size + body.size).apply {
            put(tag.size.toByte()); put(tag); put(body)
        }.array()
        return frame(TYPE_FREETEXT, if (alert) FLAG_ALERT else 0, sequence, payload)
    }

    fun encodeAck(sequence: Int): ByteArray = frame(TYPE_ACK, 0, sequence, ByteArray(0))

    private fun frame(type: Byte, flags: Int, sequence: Int, payload: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC); put(VERSION); put(type); put(flags.toByte())
            putShort((sequence and 0xFFFF).toShort())
            putShort(payload.size.toShort())
            put(payload)
        }.array()
        return ByteBuffer.allocate(body.size + CRC_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(body).putShort(crc16(body).toShort()).array()
    }

    fun decode(raw: ByteArray): Frame {
        if (raw.size < HEADER_SIZE + CRC_SIZE) throw CorruptFrameException("frame shorter than header + crc")
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.get()
        val version = buf.get()
        if (magic != MAGIC || version != VERSION) {
            throw CorruptFrameException("bad magic/version: $magic/$version")
        }
        val type = buf.get()
        val flags = buf.get().toInt() and 0xFF
        val sequence = buf.short.toInt() and 0xFFFF
        val length = buf.short.toInt() and 0xFFFF

        val expectedCrc = crc16(raw, raw.size - CRC_SIZE)
        val actualCrc = ByteBuffer.wrap(raw, raw.size - CRC_SIZE, CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        if (expectedCrc != actualCrc) throw CorruptFrameException("crc mismatch")

        val payload = raw.copyOfRange(HEADER_SIZE, raw.size - CRC_SIZE)
        if (payload.size != length) {
            throw CorruptFrameException("length field $length != actual ${payload.size}")
        }

        return when (type) {
            TYPE_TEMPLATED -> {
                val p = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                val messageId = p.short.toInt() and 0xFFFF
                val mask = p.get().toInt() and 0xFF
                val slots = mutableMapOf<String, Int>()
                SLOT_ORDER.forEachIndexed { bit, name ->
                    if (mask and (1 shl bit) != 0) slots[name] = p.short.toInt() and 0xFFFF
                }
                Frame(type, sequence, flags, messageId = messageId, slots = slots)
            }
            TYPE_FREETEXT -> {
                val tagLength = payload[0].toInt() and 0xFF
                Frame(
                    type, sequence, flags,
                    language = String(payload, 1, tagLength, Charsets.US_ASCII),
                    text = String(payload, 1 + tagLength, payload.size - 1 - tagLength, Charsets.UTF_8),
                )
            }
            else -> Frame(type, sequence, flags)
        }
    }
}
