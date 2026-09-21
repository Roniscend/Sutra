package live.sutra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkingTest {

    private val sentence = "पानी घर में घुस गया है दो बच्चे छत पर हैं जल्दी आओ"

    private fun prosodyFor(text: String, seed: Int = 0) = Prosody(
        pitchBucket = 8,
        rateBucket = 9,
        urgency = 5,
        loudness = 5,
        words = Words.split(text).mapIndexed { i, _ ->
            Prosody.Word(
                Prosody.Pitch.entries[(i + seed) % 4],
                Prosody.Energy.entries[(i + seed + 1) % 4],
                Prosody.Timing.entries[(i + seed + 2) % 4],
            )
        },
    )

    @Test
    fun a_short_utterance_stays_one_frame() {
        val frames = Chunking.frames(sentence, "hi", prosodyFor(sentence), 1)
        assertEquals(1, frames.size)
        assertEquals(sentence, SutraWire.decode(frames.single()).text)
    }

    @Test
    fun long_speech_splits_and_every_word_survives_in_order() {
        val long = List(30) { sentence }.joinToString(" ")
        val frames = Chunking.frames(long, "hi", prosodyFor(long), 1)

        assertTrue("expected several frames, got ${frames.size}", frames.size > 1)
        frames.forEach { frame ->
            assertTrue("frame of ${frame.size} B exceeds the payload cap", frame.size <= SutraWire.MAX_PAYLOAD + 9)
        }
        val rebuilt = frames.joinToString(" ") { SutraWire.decode(it).text.orEmpty() }
        assertEquals(long, rebuilt)
    }

    @Test
    fun each_chunk_keeps_its_own_words_prosody() {
        val long = List(30) { sentence }.joinToString(" ")
        val prosody = prosodyFor(long, seed = 2)
        val chunks = Chunking.split(long, "hi", prosody)

        val flattened = chunks.flatMap { it.prosody.words }
        assertEquals(prosody.words, flattened)
        chunks.forEach { chunk ->
            assertEquals(Words.split(chunk.text).size, chunk.prosody.words.size)
        }
    }

    @Test
    fun sequence_numbers_advance_and_wrap() {
        val long = List(30) { sentence }.joinToString(" ")
        val frames = Chunking.frames(long, "hi", prosodyFor(long), 0xFFFE)
        val sequences = frames.map { SutraWire.decode(it).sequence }
        assertEquals(listOf(0xFFFE, 0xFFFF, 0).take(sequences.size), sequences.take(3))
    }

    @Test
    fun a_single_word_too_large_for_a_frame_is_reported_not_silently_cut() {
        val monster = "क".repeat(4000)
        try {
            Chunking.split(monster, "hi")
            fail("a 4000-character word was accepted")
        } catch (e: Chunking.UnsplittableException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun empty_and_blank_text_produce_no_frames() {
        assertTrue(Chunking.frames("", "hi", null, 1).isEmpty())
        assertTrue(Chunking.frames("   \n ", "hi", null, 1).isEmpty())
    }

    @Test
    fun english_and_mixed_script_text_also_split_losslessly() {
        val mixed = List(40) { "Sector 18 mein Ramesh ko bhejo, 5 log hain" }.joinToString(" ")
        val frames = Chunking.frames(mixed, "en", null, 1)
        assertEquals(mixed, frames.joinToString(" ") { SutraWire.decode(it).text.orEmpty() })
    }
}
