package live.sutra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ProsodyTest {

    private val rate = ProsodyAnalyzer.SAMPLE_RATE

    private fun tone(hz: Double, seconds: Double, amplitude: Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            (amplitude * (sin(2 * PI * hz * t) + 0.5 * sin(4 * PI * hz * t) + 0.25 * sin(6 * PI * hz * t)) / 1.75).toFloat()
        }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) { p.copyInto(out, offset); offset += p.size }
        return out
    }

    @Test
    fun yin_recovers_pitch_across_the_speaking_range() {
        for (hz in listOf(85.0, 120.0, 165.0, 220.0, 300.0, 380.0)) {
            val audio = tone(hz, 0.2, 0.5)
            val estimate = ProsodyAnalyzer.yin(audio, 400)
            assertTrue("$hz Hz estimated as $estimate", abs(estimate - hz) / hz < 0.02)
        }
    }

    @Test
    fun noise_and_silence_are_unvoiced() {
        val random = java.util.Random(1)
        val noise = FloatArray(4000) { (random.nextGaussian() * 0.3).toFloat() }
        assertEquals(0.0, ProsodyAnalyzer.yin(noise, 400), 0.0)
        assertEquals(0.0, ProsodyAnalyzer.yin(silence(0.2), 400), 0.0)
    }

    @Test
    fun a_louder_higher_second_word_and_a_pause_are_all_heard() {

        val audio = concat(
            silence(0.2),
            tone(140.0, 0.6, 0.12),
            silence(0.5),
            tone(220.0, 0.6, 0.6),
            silence(0.2),
        )
        val prosody = ProsodyAnalyzer.analyze(audio, "पानी आओ").prosody
        val (first, second) = prosody.words

        assertEquals(Prosody.Timing.LONG_PAUSE, first.timing)
        assertEquals(Prosody.Pitch.LOW, first.pitch)
        assertEquals(Prosody.Pitch.HIGH, second.pitch)
        assertTrue("first ${first.energy}, second ${second.energy}", second.energy > first.energy)
    }

    @Test
    fun a_rising_contour_is_marked_rising() {
        val n = (0.8 * rate).toInt()
        var phase = 0.0
        val glide = FloatArray(n) { i ->
            val hz = 140.0 + 120.0 * i / n
            phase += 2 * PI * hz / rate
            (0.4 * sin(phase)).toFloat()
        }
        val prosody = ProsodyAnalyzer.analyze(concat(silence(0.1), glide, silence(0.1)), "क्या").prosody
        assertEquals(Prosody.Pitch.RISING, prosody.words.single().pitch)
    }

    @Test
    fun silence_yields_neutral_prosody_not_invented_levels() {
        val prosody = ProsodyAnalyzer.analyze(silence(1.0), "कुछ नहीं").prosody
        assertEquals(Prosody.neutral(2), prosody)
    }

    @Test
    fun every_packed_level_survives_the_bit_packing() {
        val words = Prosody.Pitch.entries.flatMap { p ->
            Prosody.Energy.entries.flatMap { e -> Prosody.Timing.entries.map { t -> Prosody.Word(p, e, t) } }
        }
        val prosody = Prosody(15, 0, 7, 3, words)
        val bytes = prosody.pack()
        assertEquals(2 + (64 * 6 + 7) / 8, bytes.size)
        assertEquals(prosody, Prosody.unpack(bytes, 0, words.size))
    }

    @Test
    fun pitch_buckets_are_monotonic_and_dequantize_near_their_input() {
        var previous = 0
        for (hz in 70..400 step 5) {
            val bucket = Prosody.pitchBucketFor(hz.toDouble())
            assertTrue(bucket >= previous)
            previous = bucket
        }
        val restored = Prosody(Prosody.pitchBucketFor(165.0), 6, 0, 4, emptyList()).medianPitchHz!!
        assertTrue("165 Hz restored as $restored", abs(restored - 165) / 165 < 0.07)
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                val samples = size / 2
                return FloatArray(samples) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    @Test
    fun real_hindi_speech_fits_in_a_frame_under_one_kilobit_per_second() {
        val assets = File("../app/src/androidTest/assets")
        val wav = File(assets, "hindi16k.wav")
        if (!wav.exists()) {
            println("SKIP: ${wav.path} not found")
            return
        }
        val transcript = File(assets, "hindi16k.txt").readText(Charsets.UTF_8).trim()
        val audio = readWav(wav)
        val started = System.nanoTime()
        val analysis = ProsodyAnalyzer.analyze(audio, transcript)
        val analysisMillis = (System.nanoTime() - started) / 1_000_000

        val frame = SutraWire.encodeUtterance(transcript, "hi", 1, analysis.prosody)
        val decoded = SutraWire.decode(frame)
        assertEquals(transcript, decoded.text)
        assertEquals(analysis.prosody, decoded.prosody)

        val seconds = audio.size.toDouble() / rate
        val utf8 = transcript.toByteArray(Charsets.UTF_8).size
        val bps = SutraWire.bitrate(frame.size, seconds)
        val p = analysis.prosody
        println("clip %.2f s, %d words, speech %.2f s, analysis %d ms".format(seconds, p.words.size, analysis.speechSeconds, analysisMillis))
        println("median F0 %s Hz, rate %.1f syl/s, loudness %d, urgency %d".format(p.medianPitchHz?.let { "%.0f".format(it) }, p.syllablesPerSecond, p.loudness, p.urgency))
        println("raw PCM16 %d B | UTF-8 text %d B | Sutra frame %d B (text %d + prosody %d + framing)".format(
            audio.size * 2, utf8, frame.size, TextCodec.encode(transcript).size, p.encodedSize()))
        println("Sutra %.0f bps vs 24000 bps voice codec: %.0fx less".format(bps, 24_000 / bps))
        assertTrue("frame costs %.0f bps".format(bps), bps < 1_000)
    }
}
