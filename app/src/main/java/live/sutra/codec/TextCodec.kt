package live.sutra.codec

import java.io.ByteArrayOutputStream

object TextCodec {

    const val ALPHABET = 228
    private const val SPACE = 128
    private const val ASCII_BASE = 129
    private const val ZWNJ = 223
    private const val ZWJ = 224
    private const val DANDA = 225
    private const val DOUBLE_DANDA = 226
    private const val ESCAPE = 227

    private const val BRAHMIC_FIRST = 0x0900
    private const val BRAHMIC_LAST = 0x0DFF
    private const val BLOCK = 0x80
    private const val NO_SCRIPT = 0x0F
    private const val RAW_BITS = 7

    private val MODEL = FrequencyModel(SymbolTable.FREQUENCIES)
    private val RAW = FrequencyModel.uniform(1 shl RAW_BITS)

    class CorruptTextException(message: String) : Exception(message)

    init {
        check(SymbolTable.FREQUENCIES.size == ALPHABET) {
            "SymbolTable has ${SymbolTable.FREQUENCIES.size} symbols, codec expects $ALPHABET — regenerate it"
        }
    }

    fun encode(text: String): ByteArray {
        val codePoints = text.codePoints().toArray()
        val script = primaryBlock(codePoints)
        val out = ByteArrayOutputStream()
        out.write(script ?: NO_SCRIPT)
        writeVarint(out, codePoints.size)

        val coder = RangeEncoder()
        for (cp in codePoints) {
            val symbol = symbolOf(cp, script)
            coder.encode(MODEL, symbol)
            if (symbol == ESCAPE) {
                for (shift in intArrayOf(14, 7, 0)) coder.encode(RAW, (cp shr shift) and 0x7F)
            }
        }
        out.write(coder.finish())
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): String {
        if (end - start < 2) throw CorruptTextException("text field shorter than its header")
        val script = bytes[start].toInt() and 0xFF
        if (script != NO_SCRIPT && script > (BRAHMIC_LAST - BRAHMIC_FIRST) / BLOCK) {
            throw CorruptTextException("unknown script $script")
        }
        val (count, bodyStart) = readVarint(bytes, start + 1, end)

        if (count > MAX_SYMBOLS) throw CorruptTextException("implausible symbol count $count")

        val decoder = RangeDecoder(bytes, bodyStart, end)
        val builder = StringBuilder(count)
        repeat(count) {
            when (val symbol = decoder.decode(MODEL)) {
                in 0 until BLOCK -> {
                    if (script == NO_SCRIPT) throw CorruptTextException("script offset in a script-less packet")
                    builder.appendCodePoint(BRAHMIC_FIRST + script * BLOCK + symbol)
                }
                SPACE -> builder.append(' ')
                in ASCII_BASE until ASCII_BASE + 94 -> builder.append((0x21 + symbol - ASCII_BASE).toChar())
                ZWNJ -> builder.append('‌')
                ZWJ -> builder.append('‍')
                DANDA -> builder.append('।')
                DOUBLE_DANDA -> builder.append('॥')
                else -> {
                    var cp = 0
                    repeat(3) { cp = (cp shl RAW_BITS) or decoder.decode(RAW) }
                    if (!Character.isValidCodePoint(cp)) throw CorruptTextException("invalid escaped code point $cp")
                    builder.appendCodePoint(cp)
                }
            }
        }
        return builder.toString()
    }

    private fun symbolOf(cp: Int, script: Int?): Int = when {
        cp == 0x0964 -> DANDA
        cp == 0x0965 -> DOUBLE_DANDA
        cp == 0x20 -> SPACE
        cp in 0x21..0x7E -> ASCII_BASE + cp - 0x21
        cp == 0x200C -> ZWNJ
        cp == 0x200D -> ZWJ
        script != null && blockOf(cp) == script -> cp - BRAHMIC_FIRST - script * BLOCK
        else -> ESCAPE
    }

    private fun blockOf(cp: Int): Int? =
        if (cp in BRAHMIC_FIRST..BRAHMIC_LAST) (cp - BRAHMIC_FIRST) / BLOCK else null

    private fun primaryBlock(codePoints: IntArray): Int? {
        val counts = IntArray((BRAHMIC_LAST - BRAHMIC_FIRST) / BLOCK + 1)
        for (cp in codePoints) {

            if (cp == 0x0964 || cp == 0x0965) continue
            blockOf(cp)?.let { counts[it]++ }
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: return null
        return if (counts[best] == 0) null else best
    }

    private const val MAX_SYMBOLS = 4096

    internal fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    internal fun readVarint(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var i = start
        while (true) {
            if (i >= end) throw CorruptTextException("truncated varint")
            val b = bytes[i++].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
            if (shift > 28) throw CorruptTextException("varint too long")
        }
    }
}
