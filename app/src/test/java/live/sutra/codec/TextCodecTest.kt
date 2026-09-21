package live.sutra.codec

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextCodecTest {

    private fun heldOut(): List<Pair<String, String>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("eval_utterances.json")
            ?: error("eval_utterances.json missing from test resources")
        val array = JSONObject(stream.readBytes().toString(Charsets.UTF_8)).getJSONArray("utterances")
        return (0 until array.length()).map { array.getJSONObject(it).let { u -> u.getString("lang") to u.getString("text") } }
    }

    @Test
    fun every_held_out_utterance_round_trips_exactly() {
        for ((lang, text) in heldOut()) {
            assertEquals("$lang: $text", text, TextCodec.decode(TextCodec.encode(text)))
        }
    }

    @Test
    fun strings_outside_the_alphabet_still_round_trip() {
        val cases = listOf(
            "",
            "a",
            "Ramesh को Sector 18 में बुलाओ",
            "यहाँ 8 लोग फंसे हैं। जल्दी॥",
            "বাংলা आणि हिंदी mixed",
            "help 🚑 now",
            "tab\tand\nnewline",
            "ക്ക‍ണ്ണ‌",
            "१२३ ४५६",
        )
        for (text in cases) assertEquals(text, TextCodec.decode(TextCodec.encode(text)))
    }

    @Test
    fun random_unicode_round_trips() {
        val random = Random(26173)
        repeat(2_000) {
            val length = random.nextInt(0, 60)
            val text = buildString {
                repeat(length) {
                    val cp = when (random.nextInt(4)) {
                        0 -> random.nextInt(0x0900, 0x0E00)
                        1 -> random.nextInt(0x20, 0x7F)
                        2 -> random.nextInt(0x0900, 0x0980)
                        else -> random.nextInt(0xA0, 0x2FFFF).let { if (it in 0xD800..0xDFFF) 0x41 else it }
                    }
                    appendCodePoint(cp)
                }
            }
            assertEquals(text, TextCodec.decode(TextCodec.encode(text)))
        }
    }

    @Test
    fun encoding_is_deterministic() {
        val text = "गाँव में पानी घुस गया है"
        assertArrayEquals(TextCodec.encode(text), TextCodec.encode(text))
    }

    @Test
    fun held_out_indic_text_is_at_least_three_times_smaller_than_utf8() {
        val byLanguage = heldOut().groupBy({ it.first }, { it.second })
        var utf8Total = 0
        var codedTotal = 0
        println("lang  n   utf8B  sutraB  ratio  bits/char")
        for ((lang, texts) in byLanguage.toSortedMap()) {
            val utf8 = texts.sumOf { it.toByteArray(Charsets.UTF_8).size }
            val coded = texts.sumOf { TextCodec.encode(it).size }
            val chars = texts.sumOf { it.codePointCount(0, it.length) }
            println("%-4s %2d  %5d  %6d  %5.2f  %5.2f".format(lang, texts.size, utf8, coded, utf8.toDouble() / coded, coded * 8.0 / chars))
            if (lang != "en") {
                utf8Total += utf8
                codedTotal += coded
            }
        }
        val ratio = utf8Total.toDouble() / codedTotal
        println("Indic overall: %.2fx smaller than UTF-8".format(ratio))
        assertTrue("Indic compression only %.2fx".format(ratio), ratio >= 3.0)
    }

    @Test(expected = TextCodec.CorruptTextException::class)
    fun an_implausible_symbol_count_is_rejected_not_allocated() {
        TextCodec.decode(byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0x7F))
    }

    @Test
    fun the_symbol_table_matches_the_alphabet() {
        assertEquals(TextCodec.ALPHABET, SymbolTable.FREQUENCIES.size)
        assertTrue(SymbolTable.FREQUENCIES.all { it > 0 })
    }
}
