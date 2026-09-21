package live.itantra.domain

enum class Outcome { DELIVERED, FREETEXT, REJECTED }

data class RelayResult(
    val transcript: String,
    val outcome: Outcome,
    val reason: String,
    val match: MatchResult? = null,
    val frame: ByteArray = ByteArray(0),
    val isAlert: Boolean = false,
    val matchMillis: Long = 0,
) {
    val frameBytes: Int get() = frame.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is RelayResult && transcript == other.transcript &&
            outcome == other.outcome && frame.contentEquals(other.frame))

    override fun hashCode(): Int = 31 * transcript.hashCode() + frame.contentHashCode()
}

data class ReceivedMessage(
    val sequence: Int,
    val text: String,
    val language: String,
    val isAlert: Boolean,
    val messageId: Int?,
    val untranslated: Boolean,
)

class Relay(private val phrasebook: Phrasebook) {

    private var sequence = 0
    private val seen = LinkedHashSet<Int>()

    fun send(text: String, sourceLanguage: String): RelayResult {
        if (text.isBlank()) {
            return RelayResult(text, Outcome.REJECTED, "empty transcript")
        }

        val started = System.nanoTime()
        val match = phrasebook.match(text, sourceLanguage)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        sequence = (sequence + 1) and 0xFFFF

        return if (match.accepted) {
            val best = match.best!!
            val alert = phrasebook.isAlert(best.messageId)
            RelayResult(
                transcript = text,
                outcome = Outcome.DELIVERED,
                reason = "phrasebook match",
                match = match,
                frame = Wire.encodeTemplated(best.messageId, best.slots, sequence, alert),
                isAlert = alert,
                matchMillis = elapsed,
            )
        } else {
            RelayResult(
                transcript = text,
                outcome = Outcome.FREETEXT,
                reason = match.reason,
                match = match,
                frame = Wire.encodeFreeText(text, sourceLanguage, sequence),
                matchMillis = elapsed,
            )
        }
    }

    fun receive(raw: ByteArray, listenerLanguage: String): ReceivedMessage? {
        val frame = Wire.decode(raw)
        if (!seen.add(frame.sequence)) return null
        if (seen.size > SEEN_WINDOW) seen.iterator().let { it.next(); it.remove() }

        return if (frame.isTemplated && frame.messageId != null) {
            ReceivedMessage(
                sequence = frame.sequence,
                text = phrasebook.render(frame.messageId, frame.slots, listenerLanguage),
                language = listenerLanguage,
                isAlert = frame.isAlert,
                messageId = frame.messageId,
                untranslated = false,
            )
        } else {
            ReceivedMessage(
                sequence = frame.sequence,
                text = frame.text.orEmpty(),
                language = frame.language ?: "und",
                isAlert = frame.isAlert,
                messageId = null,
                untranslated = true,
            )
        }
    }

    private companion object {
        const val SEEN_WINDOW = 256
    }
}
