package live.sutra.codec

import java.io.ByteArrayOutputStream

private const val MASK = 0xFFFF_FFFFL
private const val TOP = 1L shl 24
private const val BOT = 1L shl 16

internal const val MAX_TOTAL_FREQUENCY = (1 shl 16) - 1

internal class FrequencyModel(frequencies: IntArray) {
    val size = frequencies.size
    private val cumulative = IntArray(size + 1)

    init {
        require(frequencies.all { it > 0 }) { "every symbol needs a nonzero frequency to stay encodable" }
        for (i in frequencies.indices) cumulative[i + 1] = cumulative[i] + frequencies[i]
        require(total <= MAX_TOTAL_FREQUENCY) { "total frequency $total exceeds $MAX_TOTAL_FREQUENCY" }
    }

    val total: Int get() = cumulative[size]
    fun low(symbol: Int): Int = cumulative[symbol]
    fun frequency(symbol: Int): Int = cumulative[symbol + 1] - cumulative[symbol]

    fun symbolAt(target: Int): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun uniform(size: Int) = FrequencyModel(IntArray(size) { 1 })
    }
}

internal class RangeEncoder {
    private val out = ByteArrayOutputStream()
    private var low = 0L
    private var range = MASK

    fun encode(model: FrequencyModel, symbol: Int) {
        require(symbol in 0 until model.size) { "symbol $symbol outside model of ${model.size}" }
        range /= model.total
        low = (low + model.low(symbol) * range) and MASK
        range *= model.frequency(symbol)
        while (true) {
            if ((low xor ((low + range) and MASK)) >= TOP) {
                if (range >= BOT) break
                range = (-low) and (BOT - 1)
            }
            out.write(((low ushr 24) and 0xFF).toInt())
            low = (low shl 8) and MASK
            range = (range shl 8) and MASK
        }
    }

    fun finish(): ByteArray {
        for (bytes in 0..4) {
            val step = 1L shl (32 - 8 * bytes)
            val value = ((low + step - 1) / step) * step
            if (value < low + range) {
                for (i in 0 until bytes) out.write(((value ushr (24 - 8 * i)) and 0xFF).toInt())
                break
            }
        }
        return out.toByteArray()
    }
}

internal class RangeDecoder(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    private var position = start
    private var low = 0L
    private var range = MASK
    private var code = 0L

    init {
        repeat(4) { code = ((code shl 8) or nextByte()) and MASK }
    }

    private fun nextByte(): Long =
        if (position < end) (data[position++].toLong() and 0xFF) else { position++; 0L }

    fun decode(model: FrequencyModel): Int {
        range /= model.total
        val target = (((code - low) and MASK) / range).coerceAtMost(model.total - 1L).toInt()
        val symbol = model.symbolAt(target)
        low = (low + model.low(symbol) * range) and MASK
        range *= model.frequency(symbol)
        while (true) {
            if ((low xor ((low + range) and MASK)) >= TOP) {
                if (range >= BOT) break
                range = (-low) and (BOT - 1)
            }
            code = ((code shl 8) or nextByte()) and MASK
            low = (low shl 8) and MASK
            range = (range shl 8) and MASK
        }
        return symbol
    }
}
