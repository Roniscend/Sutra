package live.sutra.codec

object Chunking {

    data class Chunk(val text: String, val prosody: Prosody)

    class UnsplittableException(message: String) : Exception(message)

    fun split(text: String, language: String, prosody: Prosody? = null): List<Chunk> {
        val words = Words.split(text)
        if (words.isEmpty()) return emptyList()
        val levels = prosody ?: Prosody.neutral(words.size)
        require(levels.words.size == words.size) {
            "prosody has ${levels.words.size} words but the text has ${words.size}"
        }

        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < words.size) {
            var end = words.size
            var accepted = -1

            while (end > start) {
                val candidate = build(words, levels, start, end, language)
                if (candidate != null) {
                    accepted = end
                    break
                }
                end = start + (end - start) / 2
            }
            if (accepted < 0) {
                throw UnsplittableException(
                    "the word '${words[start]}' does not fit in a ${SutraWire.MAX_PAYLOAD}-byte payload"
                )
            }
            var extended = accepted
            while (extended < words.size && build(words, levels, start, extended + 1, language) != null) {
                extended++
            }
            chunks += chunkOf(words, levels, start, extended)
            start = extended
        }
        return chunks
    }

    fun frames(
        text: String,
        language: String,
        prosody: Prosody?,
        firstSequence: Int,
        flags: Int = 0,
    ): List<ByteArray> = split(text, language, prosody).mapIndexed { index, chunk ->
        SutraWire.encodeUtterance(
            text = chunk.text,
            language = language,
            sequence = (firstSequence + index) and 0xFFFF,
            prosody = chunk.prosody,
            flags = flags,
        )
    }

    private fun build(
        words: List<String>,
        prosody: Prosody,
        start: Int,
        end: Int,
        language: String,
    ): ByteArray? = try {
        val chunk = chunkOf(words, prosody, start, end)
        SutraWire.encodeUtterance(chunk.text, language, 0, chunk.prosody)
    } catch (_: SutraWire.PayloadTooLargeException) {
        null
    }

    private fun chunkOf(words: List<String>, prosody: Prosody, start: Int, end: Int): Chunk {
        val slice = words.subList(start, end)
        return Chunk(
            text = slice.joinToString(" "),
            prosody = prosody.copy(words = prosody.words.subList(start, end)),
        )
    }
}
