package live.sutra.codec

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class Prosody(

    val pitchBucket: Int,

    val rateBucket: Int,

    val urgency: Int,

    val loudness: Int,
    val words: List<Word>,
) {
    enum class Pitch { LOW, MID, HIGH, RISING }
    enum class Energy { SOFT, NORMAL, LOUD, SHOUT }
    enum class Timing { PLAIN, LENGTHENED, SHORT_PAUSE, LONG_PAUSE }

    data class Word(val pitch: Pitch, val energy: Energy, val timing: Timing) {
        internal val bits: Int get() = (pitch.ordinal shl 4) or (energy.ordinal shl 2) or timing.ordinal

        companion object {
            val NEUTRAL = Word(Pitch.MID, Energy.NORMAL, Timing.PLAIN)
            internal fun of(bits: Int) = Word(
                Pitch.entries[(bits shr 4) and 3],
                Energy.entries[(bits shr 2) and 3],
                Timing.entries[bits and 3],
            )
        }
    }

    init {
        require(pitchBucket in 0..15 && rateBucket in 0..15) { "pitch/rate bucket out of range" }
        require(urgency in 0..7 && loudness in 0..7) { "urgency/loudness out of range" }
    }

    val medianPitchHz: Float?
        get() = if (pitchBucket == 0) null
        else (MIN_F0 * exp((pitchBucket - 0.5) / 15.0 * ln(MAX_F0 / MIN_F0))).toFloat()

    val syllablesPerSecond: Float get() = 1.5f + rateBucket / 2f

    val isUrgent: Boolean get() = urgency >= URGENT_THRESHOLD

    fun encodedSize(): Int = HEADER_BYTES + (words.size * BITS_PER_WORD + 7) / 8

    internal fun pack(): ByteArray {
        val out = ByteArray(encodedSize())
        out[0] = ((pitchBucket shl 4) or rateBucket).toByte()
        out[1] = ((urgency shl 5) or (loudness shl 2)).toByte()
        var bitPosition = 0
        for (word in words) {
            val bits = word.bits
            for (b in BITS_PER_WORD - 1 downTo 0) {
                if ((bits shr b) and 1 == 1) {
                    val index = HEADER_BYTES + bitPosition / 8
                    out[index] = (out[index].toInt() or (0x80 ushr (bitPosition % 8))).toByte()
                }
                bitPosition++
            }
        }
        return out
    }

    companion object {
        const val HEADER_BYTES = 2
        const val BITS_PER_WORD = 6
        const val URGENT_THRESHOLD = 5
        internal const val MIN_F0 = 70.0
        internal const val MAX_F0 = 400.0

        fun neutral(wordCount: Int) = Prosody(0, 6, 0, 4, List(wordCount) { Word.NEUTRAL })

        fun pitchBucketFor(hz: Double?): Int {
            if (hz == null || hz <= 0) return 0
            val position = ln(hz.coerceIn(MIN_F0, MAX_F0) / MIN_F0) / ln(MAX_F0 / MIN_F0)
            return (1 + (position * 14.999).toInt()).coerceIn(1, 15)
        }

        fun rateBucketFor(syllablesPerSecond: Double): Int =
            ((syllablesPerSecond - 1.5) * 2).roundToInt().coerceIn(0, 15)

        fun loudnessBucketFor(dbfs: Double): Int = ((dbfs + 50) / 5).toInt().coerceIn(0, 7)

        internal fun unpack(bytes: ByteArray, start: Int, wordCount: Int): Prosody {
            val size = HEADER_BYTES + (wordCount * BITS_PER_WORD + 7) / 8
            require(start + size <= bytes.size) { "prosody field truncated" }
            val h0 = bytes[start].toInt() and 0xFF
            val h1 = bytes[start + 1].toInt() and 0xFF
            var bitPosition = 0
            val words = List(wordCount) {
                var bits = 0
                repeat(BITS_PER_WORD) {
                    val byte = bytes[start + HEADER_BYTES + bitPosition / 8].toInt() and 0xFF
                    bits = (bits shl 1) or ((byte shr (7 - bitPosition % 8)) and 1)
                    bitPosition++
                }
                Word.of(bits)
            }
            return Prosody(h0 shr 4, h0 and 0x0F, h1 shr 5, (h1 shr 2) and 7, words)
        }
    }
}

object Words {
    private val WHITESPACE = Regex("""\s+""")

    fun split(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    fun weight(word: String): Int {
        var n = 0
        word.codePoints().forEach { cp ->
            when (Character.getType(cp)) {
                Character.OTHER_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
                Character.UPPERCASE_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt() -> n++
            }
        }
        return maxOf(1, n)
    }
}
