package live.itantra.transport

import live.itantra.domain.Wire

class FrameReader(private val maxFrameBytes: Int = MAX_FRAME_BYTES) {

    private var buffer = ByteArray(0)

    val pending: Int get() = buffer.size

    fun append(data: ByteArray, length: Int = data.size): List<ByteArray> {
        buffer = buffer + data.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()

        while (true) {
            if (buffer.size < 2) break

            if (buffer[0] != Wire.MAGIC || buffer[1] != Wire.VERSION) {
                val start = findFrameStart(buffer)
                if (start < 0) {

                    buffer = if (buffer.isNotEmpty() && buffer.last() == Wire.MAGIC) {
                        byteArrayOf(Wire.MAGIC)
                    } else {
                        ByteArray(0)
                    }
                    break
                }
                buffer = buffer.copyOfRange(start, buffer.size)
                continue
            }

            if (buffer.size < HEADER_BYTES) break

            val payload = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
            val total = HEADER_BYTES + payload + CRC_BYTES

            if (total > maxFrameBytes) {

                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            if (buffer.size < total) break

            frames += buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)
        }
        return frames
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    private fun findFrameStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == Wire.MAGIC && data[i + 1] == Wire.VERSION) return i
        }
        return -1
    }

    companion object {
        private const val HEADER_BYTES = 8
        private const val CRC_BYTES = 2

        const val MAX_FRAME_BYTES = 1024
    }
}
