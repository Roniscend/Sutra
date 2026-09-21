package live.sutra.link

class DeliveryTracker(
    private val retryAfterMillis: Long = 3_000,
    private val giveUpAfterMillis: Long = 180_000,
    private val maxAttempts: Int = 8,

    private val backoffCap: Int = 8,
) {

    enum class State { PENDING, DELIVERED, LOST }

    data class Entry(
        val sequence: Int,
        val frame: ByteArray,
        val firstSentAtMillis: Long,
        val lastSentAtMillis: Long,
        val attempts: Int,
        val state: State,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Entry && sequence == other.sequence && state == other.state && attempts == other.attempts)

        override fun hashCode(): Int = 31 * sequence + state.ordinal
    }

    private val entries = LinkedHashMap<Int, Entry>()

    fun onSent(sequence: Int, frame: ByteArray, nowMillis: Long) {
        entries[sequence] = Entry(sequence, frame, nowMillis, nowMillis, 1, State.PENDING)
    }

    fun onAck(sequence: Int): Boolean {
        val entry = entries[sequence] ?: return false
        if (entry.state != State.PENDING) return false
        entries[sequence] = entry.copy(state = State.DELIVERED)
        return true
    }

    fun due(nowMillis: Long): List<Entry> {
        val resend = mutableListOf<Entry>()
        for ((sequence, entry) in entries.toList()) {
            if (entry.state != State.PENDING) continue
            when {
                nowMillis - entry.firstSentAtMillis >= giveUpAfterMillis || entry.attempts >= maxAttempts &&
                    nowMillis - entry.lastSentAtMillis >= retryAfterMillis ->
                    entries[sequence] = entry.copy(state = State.LOST)

                nowMillis - entry.lastSentAtMillis >= retryAfterMillis * minOf(entry.attempts, backoffCap) -> {
                    val next = entry.copy(lastSentAtMillis = nowMillis, attempts = entry.attempts + 1)
                    entries[sequence] = next
                    resend += next
                }
            }
        }
        return resend
    }

    fun state(sequence: Int): State? = entries[sequence]?.state

    fun pendingCount(): Int = entries.count { it.value.state == State.PENDING }

    fun lostCount(): Int = entries.count { it.value.state == State.LOST }

    fun forget(sequence: Int) {
        entries.remove(sequence)
    }

    fun clear() = entries.clear()
}
