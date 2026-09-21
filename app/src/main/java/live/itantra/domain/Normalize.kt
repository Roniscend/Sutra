package live.itantra.domain

object Normalize {

    private val BLOCK_BASES = intArrayOf(
        0x0900,
        0x0980,
        0x0A00,
        0x0A80,
        0x0B00,
        0x0B80,
        0x0C00,
        0x0C80,
        0x0D00,
    )

    private const val DEVANAGARI = 0x0900

    private const val OFF_VIRAMA = 0x4D
    private const val OFF_NUKTA = 0x3C
    private const val OFF_VISARGA = 0x03
    private val NASAL_OFFSETS = setOf(0x01, 0x02)

    private val VOWEL_CLASS: Map<Int, Char> = buildMap {
        listOf(0x05, 0x06, 0x3E).forEach { put(it, 'a') }
        listOf(0x07, 0x08, 0x3F, 0x40).forEach { put(it, 'i') }
        listOf(0x09, 0x0A, 0x41, 0x42).forEach { put(it, 'u') }
        listOf(0x0B, 0x60, 0x43, 0x62).forEach { put(it, 'r') }
        listOf(0x0E, 0x0F, 0x10, 0x46, 0x47, 0x48).forEach { put(it, 'e') }
        listOf(0x12, 0x13, 0x14, 0x4A, 0x4B, 0x4C).forEach { put(it, 'o') }
    }

    private val CONSONANT_FOLD: Map<Int, Int> = mapOf(
        0x1F to 0x24,
        0x20 to 0x25,
        0x21 to 0x26,
        0x22 to 0x27,
        0x23 to 0x28,
        0x36 to 0x38,
        0x37 to 0x38,
        0x35 to 0x2C,
        0x1E to 0x1C,
    )

    private val PUNCTUATION = Regex("""[।॥,.!?;:\-_'"()\[\]{}/\\–—]+""")
    private val WHITESPACE = Regex("""\s+""")

    private fun blockBaseOf(cp: Int): Int {
        for (base in BLOCK_BASES) if (cp >= base && cp < base + 0x80) return base
        return -1
    }

    fun normalize(input: String?, fillers: Set<String> = emptySet()): String {
        if (input.isNullOrBlank()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val cp = ch.code
            val base = blockBaseOf(cp)
            if (base >= 0) {
                val off = cp - base
                if (off in 0x66..0x6F) {
                    sb.append(('0' + (off - 0x66)))
                    continue
                }
            }
            sb.append(ch)
        }
        val cleaned = PUNCTUATION.replace(sb.toString(), " ").lowercase()
        return cleaned.split(WHITESPACE)
            .filter { it.isNotBlank() && it !in fillers }
            .joinToString(" ")
    }

    fun phoneticKey(input: String?, fillers: Set<String> = emptySet()): String {
        val text = normalize(input, fillers)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val cp = ch.code
            val base = blockBaseOf(cp)

            if (base < 0) {
                if (ch == ' ' || ch.isDigit() || (ch.code < 128 && ch.isLetter())) out.append(ch)
                i++
                continue
            }

            val off = cp - base
            when {
                off == OFF_NUKTA || off == OFF_VISARGA -> i++

                off in NASAL_OFFSETS -> { out.append('n'); i++ }

                off in 0x15..0x39 -> {
                    val folded = CONSONANT_FOLD[off] ?: off
                    out.append((DEVANAGARI + folded).toChar())
                    i++

                    while (i < text.length && (text[i].code - blockBaseOf(text[i].code)) == OFF_NUKTA
                        && blockBaseOf(text[i].code) >= 0
                    ) i++
                    val nextOff = if (i < text.length) {
                        val nb = blockBaseOf(text[i].code)
                        if (nb >= 0) text[i].code - nb else -1
                    } else -1
                    when {
                        nextOff == OFF_VIRAMA -> i++
                        nextOff >= 0 && VOWEL_CLASS.containsKey(nextOff) -> {
                            out.append(VOWEL_CLASS[nextOff]!!); i++
                        }
                        else -> out.append('a')
                    }
                }

                VOWEL_CLASS.containsKey(off) -> { out.append(VOWEL_CLASS[off]!!); i++ }

                else -> i++
            }
        }
        return WHITESPACE.replace(out.toString(), " ").trim()
    }

    fun parseNumber(tokens: List<String>, words: Map<String, Int>): Int? {
        if (tokens.isEmpty()) return null
        var total = 0
        var current = 0
        var seen = false
        for (tok in tokens) {
            val asDigits = tok.toIntOrNull()
            if (asDigits != null) {
                current += asDigits
                seen = true
                continue
            }
            val value = words[tok] ?: return null
            seen = true
            if (value == 100 || value == 1000) {
                current = (if (current == 0) 1 else current) * value
                total += current
                current = 0
            } else {
                current += value
            }
        }
        return if (seen) total + current else null
    }
}
