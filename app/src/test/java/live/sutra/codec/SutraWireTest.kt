package live.sutra.codec

import live.itantra.domain.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SutraWireTest {

    private val text = "पानी घर में घुस गया है दो बच्चे छत पर हैं जल्दी आओ"

    private fun shouted(wordCount: Int) = Prosody(
        pitchBucket = 9, rateBucket = 11, urgency = 6, loudness = 7,
        words = List(wordCount) { i ->
            Prosody.Word(
                Prosody.Pitch.entries[i % 4],
                Prosody.Energy.entries[(i + 1) % 4],
                Prosody.Timing.entries[(i + 2) % 4],
            )
        },
    )

    @Test
    fun utterance_round_trips_text_prosody_and_header() {
        val prosody = shouted(Words.split(text).size)
        val frame = SutraWire.encodeUtterance(text, "hi", 0xBEEF, prosody, SutraWire.FLAG_CRITICAL)
        val packet = SutraWire.decode(frame)

        assertEquals(SutraWire.TYPE_UTTERANCE, packet.type)
        assertEquals(0xBEEF, packet.sequence)
        assertEquals("hi", packet.language)
        assertEquals(text, packet.text)
        assertEquals(prosody, packet.prosody)
        assertTrue(packet.isCritical)
        assertTrue("urgency 6 must set the urgent flag", packet.isUrgent)
        assertFalse(packet.isInterrupt)
    }

    @Test
    fun every_language_index_round_trips() {
        for (language in SutraWire.LANGUAGES) {
            val packet = SutraWire.decode(SutraWire.encodeUtterance("test utterance", language, 1))
            assertEquals(language, packet.language)
        }
    }

    @Test
    fun a_single_flipped_bit_anywhere_is_caught() {
        val frame = SutraWire.encodeUtterance(text, "hi", 7, shouted(Words.split(text).size))
        for (byte in frame.indices) for (bit in 0 until 8) {
            val corrupt = frame.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
            try {
                val packet = SutraWire.decode(corrupt)
                fail("bit $bit of byte $byte decoded silently as '${packet.text}'")
            } catch (_: SutraWire.CorruptFrameException) {
            }
        }
    }

    @Test
    fun truncation_at_every_length_is_rejected() {
        val frame = SutraWire.encodeUtterance(text, "hi", 3)
        for (length in 0 until frame.size) {
            try {
                SutraWire.decode(frame.copyOf(length))
                fail("frame truncated to $length bytes decoded")
            } catch (_: SutraWire.CorruptFrameException) {
            }
        }
    }

    @Test
    fun an_itantra_frame_is_not_mistaken_for_sutra() {
        val itantra = Wire.encodeFreeText("यहाँ बाढ़ आई है", "hi", 1)
        try {
            SutraWire.decode(itantra)
            fail("iTantra frame accepted as Sutra")
        } catch (_: SutraWire.CorruptFrameException) {
        }
    }

    @Test
    fun ack_and_control_frames_are_nine_and_ten_bytes() {
        val ack = SutraWire.encodeAck(42)
        assertEquals(9, ack.size)
        assertEquals(SutraWire.TYPE_ACK, SutraWire.decode(ack).type)

        val control = SutraWire.encodeControl(SutraWire.CONTROL_READBACK_CONFIRMED, 42, SutraWire.FLAG_INTERRUPT)
        assertEquals(10, control.size)
        val packet = SutraWire.decode(control)
        assertEquals(SutraWire.CONTROL_READBACK_CONFIRMED, packet.control)
        assertTrue(packet.isInterrupt)
    }

    @Test
    fun oversized_speech_is_refused_rather_than_truncated() {
        val paragraph = List(40) { text }.joinToString(" ")
        try {
            SutraWire.encodeUtterance(paragraph, "hi", 1)
            fail("a ${paragraph.length}-char paragraph fit in one frame")
        } catch (e: SutraWire.PayloadTooLargeException) {
            assertTrue(e.bytes > SutraWire.MAX_PAYLOAD)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun prosody_that_does_not_match_the_words_is_refused() {
        SutraWire.encodeUtterance(text, "hi", 1, Prosody.neutral(2))
    }

    @Test
    fun a_prosody_word_count_that_disagrees_with_the_text_is_corruption() {

        val honest = SutraWire.encodeUtterance("एक दो", "hi", 1)
        val payload = honest.copyOfRange(SutraWire.HEADER_BYTES, honest.size - SutraWire.CRC_BYTES)
        payload[0] = 3
        val forged = honest.copyOf()
        payload.copyInto(forged, SutraWire.HEADER_BYTES)
        val crc = Wire.crc16(forged, forged.size - 2)
        forged[forged.size - 2] = (crc ushr 8).toByte()
        forged[forged.size - 1] = crc.toByte()
        try {
            SutraWire.decode(forged)
            fail("lying word count accepted")
        } catch (_: SutraWire.CorruptFrameException) {
        }
    }
}
