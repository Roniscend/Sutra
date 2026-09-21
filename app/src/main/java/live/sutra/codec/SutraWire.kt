package live.sutra.codec

import live.itantra.domain.Wire
import java.io.ByteArrayOutputStream

object SutraWire {

    const val MAGIC: Byte = 0x53
    const val VERSION = 1

    const val TYPE_UTTERANCE = 1
    const val TYPE_ACK = 2
    const val TYPE_CONTROL = 3

    const val FLAG_URGENT = 0x01
    const val FLAG_RETRANSMIT = 0x02

    const val FLAG_CRITICAL = 0x04

    const val FLAG_INTERRUPT = 0x08

    const val CONTROL_END_TURN = 1
    const val CONTROL_READBACK_CONFIRMED = 2
    const val CONTROL_READBACK_REJECTED = 3

    const val HEADER_BYTES = 7
    const val CRC_BYTES = 2
    const val MAX_PAYLOAD = 255

    val LANGUAGES = listOf("hi", "bn", "gu", "or", "mr", "ta", "te", "kn", "ml", "en")

    class CorruptFrameException(message: String) : Exception(message)
    class PayloadTooLargeException(val bytes: Int) :
        Exception("utterance payload is $bytes bytes, over $MAX_PAYLOAD; split it at word boundaries")

    data class Packet(
        val type: Int,
        val flags: Int,
        val sequence: Int,
        val language: String? = null,
        val text: String? = null,
        val prosody: Prosody? = null,
        val control: Int? = null,
    ) {
        val isUrgent: Boolean get() = flags and FLAG_URGENT != 0
        val isCritical: Boolean get() = flags and FLAG_CRITICAL != 0
        val isInterrupt: Boolean get() = flags and FLAG_INTERRUPT != 0
    }

    fun encodeUtterance(
        text: String,
        language: String,
        sequence: Int,
        prosody: Prosody? = null,
        flags: Int = 0,
    ): ByteArray {
        val languageIndex = LANGUAGES.indexOf(language)
        require(languageIndex >= 0) { "unsupported language '$language'" }
        val words = Words.split(text)
        val levels = prosody ?: Prosody.neutral(words.size)
        require(levels.words.size == words.size) {
            "prosody has ${levels.words.size} words but the text has ${words.size}"
        }

        val payload = ByteArrayOutputStream().apply {
            TextCodec.writeVarint(this, words.size)
            write(levels.pack())
            write(TextCodec.encode(text))
        }.toByteArray()
        if (payload.size > MAX_PAYLOAD) throw PayloadTooLargeException(payload.size)

        val urgent = if (levels.isUrgent) FLAG_URGENT else 0
        return frame(TYPE_UTTERANCE, flags or urgent, sequence, languageIndex, payload)
    }

    fun encodeAck(sequence: Int): ByteArray = frame(TYPE_ACK, 0, sequence, 0, ByteArray(0))

    fun encodeControl(code: Int, sequence: Int, flags: Int = 0): ByteArray =
        frame(TYPE_CONTROL, flags, sequence, 0, byteArrayOf(code.toByte()))

    private fun frame(type: Int, flags: Int, sequence: Int, language: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size + CRC_BYTES)
        out[0] = MAGIC
        out[1] = ((VERSION shl 5) or type).toByte()
        out[2] = flags.toByte()
        out[3] = (sequence ushr 8).toByte()
        out[4] = sequence.toByte()
        out[5] = language.toByte()
        out[6] = payload.size.toByte()
        payload.copyInto(out, HEADER_BYTES)
        val crc = Wire.crc16(out, HEADER_BYTES + payload.size)
        out[out.size - 2] = (crc ushr 8).toByte()
        out[out.size - 1] = crc.toByte()
        return out
    }

    fun decode(raw: ByteArray): Packet {
        if (raw.size < HEADER_BYTES + CRC_BYTES) throw CorruptFrameException("frame shorter than header + crc")
        if (raw[0] != MAGIC) throw CorruptFrameException("not a Sutra frame (magic ${raw[0]})")
        val version = (raw[1].toInt() and 0xFF) ushr 5
        if (version != VERSION) throw CorruptFrameException("unsupported version $version")

        val length = raw[6].toInt() and 0xFF
        if (raw.size != HEADER_BYTES + length + CRC_BYTES) {
            throw CorruptFrameException("length field $length does not match frame of ${raw.size} bytes")
        }
        val expected = Wire.crc16(raw, raw.size - CRC_BYTES)
        val actual = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        if (expected != actual) throw CorruptFrameException("crc mismatch")

        val type = raw[1].toInt() and 0x1F
        val flags = raw[2].toInt() and 0xFF
        val sequence = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
        val languageIndex = raw[5].toInt() and 0xFF
        val payloadEnd = HEADER_BYTES + length

        return try {
            when (type) {
                TYPE_UTTERANCE -> {
                    val language = LANGUAGES.getOrNull(languageIndex)
                        ?: throw CorruptFrameException("unknown language index $languageIndex")
                    val (wordCount, prosodyStart) = TextCodec.readVarint(raw, HEADER_BYTES, payloadEnd)
                    val prosody = Prosody.unpack(raw, prosodyStart, wordCount)
                    val textStart = prosodyStart + prosody.encodedSize()
                    if (textStart > payloadEnd) throw CorruptFrameException("prosody overruns payload")
                    val text = TextCodec.decode(raw, textStart, payloadEnd)
                    if (Words.split(text).size != wordCount) {
                        throw CorruptFrameException("word count $wordCount does not match decoded text")
                    }
                    Packet(type, flags, sequence, language, text, prosody)
                }
                TYPE_ACK -> Packet(type, flags, sequence)
                TYPE_CONTROL -> {
                    if (length != 1) throw CorruptFrameException("control payload must be 1 byte")
                    Packet(type, flags, sequence, control = raw[HEADER_BYTES].toInt() and 0xFF)
                }
                else -> throw CorruptFrameException("unknown frame type $type")
            }
        } catch (e: TextCodec.CorruptTextException) {
            throw CorruptFrameException("text field: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw CorruptFrameException(e.message ?: "malformed payload")
        }
    }

    fun bitrate(frameBytes: Int, speechSeconds: Double): Double =
        if (speechSeconds <= 0) 0.0 else frameBytes * 8 / speechSeconds
}
