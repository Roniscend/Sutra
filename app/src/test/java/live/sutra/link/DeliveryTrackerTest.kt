package live.sutra.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTrackerTest {

    private val frame = byteArrayOf(1, 2, 3)

    @Test
    fun an_ack_marks_the_frame_delivered_and_stops_retries() {
        val tracker = DeliveryTracker()
        tracker.onSent(7, frame, 0)
        assertTrue(tracker.onAck(7))
        assertEquals(DeliveryTracker.State.DELIVERED, tracker.state(7))
        assertTrue(tracker.due(60_000).isEmpty())
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun retries_back_off_instead_of_hammering_a_dead_link() {

        val tracker = DeliveryTracker(retryAfterMillis = 1_000, giveUpAfterMillis = 100_000, maxAttempts = 8)
        tracker.onSent(1, frame, 0)

        assertTrue("too early to retry", tracker.due(500).isEmpty())
        assertEquals(listOf(1), tracker.due(1_100).map { it.sequence })
        assertTrue("2x has not elapsed", tracker.due(2_500).isEmpty())
        assertEquals(listOf(1), tracker.due(3_200).map { it.sequence })
        assertTrue("3x has not elapsed", tracker.due(5_000).isEmpty())
        assertEquals(listOf(1), tracker.due(6_300).map { it.sequence })
    }

    @Test
    fun a_frame_that_is_never_acknowledged_becomes_lost_not_pending_forever() {
        val tracker = DeliveryTracker(retryAfterMillis = 1_000, giveUpAfterMillis = 3_000)
        tracker.onSent(2, frame, 0)
        tracker.due(1_100)
        tracker.due(3_400)
        tracker.due(3_500)

        assertEquals(DeliveryTracker.State.LOST, tracker.state(2))
        assertEquals(1, tracker.lostCount())
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun a_late_ack_for_a_lost_frame_is_ignored() {
        val tracker = DeliveryTracker(retryAfterMillis = 1_000, giveUpAfterMillis = 2_000)
        tracker.onSent(3, frame, 0)
        tracker.due(2_500)
        assertEquals(DeliveryTracker.State.LOST, tracker.state(3))
        assertFalse(tracker.onAck(3))
        assertEquals(DeliveryTracker.State.LOST, tracker.state(3))
    }

    @Test
    fun an_ack_for_something_never_sent_is_not_an_error() {
        assertFalse(DeliveryTracker().onAck(99))
    }

    @Test
    fun attempts_are_capped_so_a_dead_link_does_not_retry_forever() {
        val tracker = DeliveryTracker(retryAfterMillis = 500, giveUpAfterMillis = 60_000, maxAttempts = 3)
        tracker.onSent(4, frame, 0)
        var sends = 0
        var now = 0L
        repeat(40) {
            now += 1_000
            sends += tracker.due(now).size
        }
        assertEquals("first send plus two retries", 2, sends)
        assertEquals(DeliveryTracker.State.LOST, tracker.state(4))
    }

    @Test
    fun an_outage_lasting_a_minute_still_ends_in_delivery_not_loss() {

        val tracker = DeliveryTracker()
        tracker.onSent(5, frame, 0)
        var now = 0L
        repeat(12) { now += 5_000; tracker.due(now) }
        assertEquals(DeliveryTracker.State.PENDING, tracker.state(5))
        assertTrue("the ack arrives once coverage returns", tracker.onAck(5))
        assertEquals(DeliveryTracker.State.DELIVERED, tracker.state(5))
    }
}
