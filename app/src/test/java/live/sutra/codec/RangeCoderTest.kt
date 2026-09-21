package live.sutra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RangeCoderTest {

    private fun roundTrip(model: FrequencyModel, symbols: IntArray): ByteArray {
        val encoder = RangeEncoder()
        symbols.forEach { encoder.encode(model, it) }
        val bytes = encoder.finish()
        val decoder = RangeDecoder(bytes)
        val decoded = IntArray(symbols.size) { decoder.decode(model) }
        assertTrue("mismatch for ${symbols.size} symbols", symbols.contentEquals(decoded))
        return bytes
    }

    @Test
    fun skewed_and_uniform_models_round_trip_under_fuzz() {
        val random = Random(173)
        val skewed = FrequencyModel(IntArray(228) { if (it < 8) 4000 else 1 + random.nextInt(20) })
        val uniform = FrequencyModel.uniform(128)
        repeat(3_000) {
            val length = random.nextInt(0, 200)
            roundTrip(skewed, IntArray(length) {
                if (random.nextInt(10) < 8) random.nextInt(8) else random.nextInt(228)
            })
            roundTrip(uniform, IntArray(length) { random.nextInt(128) })
        }
    }

    @Test
    fun rare_symbols_back_to_back_round_trip() {

        val model = FrequencyModel(IntArray(228) { if (it == 0) 60_000 else 1 })
        roundTrip(model, IntArray(500) { if (it % 2 == 0) 227 else 1 })
        roundTrip(model, IntArray(500) { 0 })
    }

    @Test
    fun empty_input_costs_no_bytes() {
        assertEquals(0, roundTrip(FrequencyModel.uniform(4), IntArray(0)).size)
    }

    @Test
    fun a_confident_model_spends_well_under_a_bit_per_likely_symbol() {
        val model = FrequencyModel(intArrayOf(60_000, 100, 100))
        val bytes = roundTrip(model, IntArray(800) { 0 })
        assertTrue("800 near-certain symbols took ${bytes.size} bytes", bytes.size < 10)
    }

    @Test
    fun symbol_lookup_finds_every_interval_edge() {
        val model = FrequencyModel(intArrayOf(3, 1, 5, 2))
        val expected = intArrayOf(0, 0, 0, 1, 2, 2, 2, 2, 2, 3, 3)
        for (target in expected.indices) assertEquals(expected[target], model.symbolAt(target))
    }
}
