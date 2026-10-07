package lavalink.server.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioLossCounterTest {
    private var now = 10 * MINUTE + 5_000
    private val counter = AudioLossCounter { now }

    @Test
    fun `send failures have their own bucket`() {
        repeat(3) { counter.onSendFailure() }
        repeat(2) { counter.onSuccess() }
        counter.onLoss()

        now += MINUTE

        assertEquals(3, counter.lastMinuteSendFailures, "send failures")
        assertEquals(2, counter.lastMinuteSuccess, "sent")
        assertEquals(1, counter.lastMinuteLoss, "loss")
    }

    @Test
    fun `consecutive minutes roll over`() {
        repeat(3) { counter.onSendFailure() }
        now += MINUTE
        repeat(5) { counter.onSuccess() }

        assertEquals(3, counter.lastMinuteSendFailures, "the minute before")
        assertEquals(0, counter.lastMinuteSuccess, "nothing was sent the minute before")

        now += MINUTE

        assertEquals(0, counter.lastMinuteSendFailures)
        assertEquals(5, counter.lastMinuteSuccess)
    }

    @Test
    fun `a minute after the voice went down reads zero, not the minute before it`() {
        repeat(1807) { counter.onSuccess() }
        now += MINUTE
        assertEquals(1807, counter.lastMinuteSuccess)

        // Nothing polls the player while its voice is down.
        now += MINUTE

        assertEquals(0, counter.lastMinuteSuccess, "sent during a minute nothing was polled")
    }

    @Test
    fun `polling that resumes after a gap does not report the minute before the gap`() {
        repeat(1807) { counter.onSuccess() }
        // Two minutes without voice, then a rejoin: polling resumes.
        now += 2 * MINUTE + 3_000
        counter.onSuccess()

        assertEquals(0, counter.lastMinuteSuccess, "sent during the minute before the rejoin")

        now += MINUTE

        assertEquals(1, counter.lastMinuteSuccess)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
