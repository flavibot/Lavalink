package lavalink.server.player.rtcp

import dev.arbjerg.lavalink.protocol.v4.RtcpDiagnostics
import dev.arbjerg.lavalink.protocol.v4.RtcpStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RtcpReceiverStatsTest {
    private var now = 1_791_000_000_500L
    private val stats = RtcpReceiverStats { now }

    private fun block(fraction: Int = 0, lost: Int = 0, jitter: Long = 0) = RtcpReportBlock(16530, fraction, lost, 0, jitter, 0, 0)

    @Test
    fun `nothing before the first report`() {
        assertNull(stats.snapshot())
    }

    @Test
    fun `the latest report, converted`() {
        stats.onReport(block(fraction = 10))
        now += 1_000
        stats.onReport(block(fraction = 64, lost = -3, jitter = 1440))
        now += 250

        assertEquals(RtcpDiagnostics(0.25, -3, 30.0, 250, 2), stats.snapshot())
    }

    @Test
    fun `one report a second fills the minute, and the count drains once they stop`() {
        now = 1_791_000_000_000L
        repeat(90) {
            if (it > 0) now += 1_000
            stats.onReport(block())
        }
        // The window is the last 60 whole seconds, the current one included.
        assertEquals(60, stats.snapshot()!!.reportsLastMinute, "a report a second, for 90 s")

        now += 30_000
        assertEquals(30, stats.snapshot()!!.reportsLastMinute, "30 s after the last one")
        assertEquals(30_000, stats.snapshot()!!.reportAgeMs)

        now += 30_000
        val stale = stats.snapshot()!!
        assertEquals(0, stale.reportsLastMinute, "a minute after the last one")
        assertEquals(60_000, stale.reportAgeMs, "the age keeps growing")
    }

    @Test
    fun `several reports in one second, and a gap longer than the window`() {
        repeat(3) { stats.onReport(block()) }
        assertEquals(3, stats.snapshot()!!.reportsLastMinute)

        // The bucket of this second is reused an hour later: the old count must not come back.
        now += 3_600_000
        stats.onReport(block())
        assertEquals(1, stats.snapshot()!!.reportsLastMinute)
    }

    @Test
    fun `the stats op counts a stale report as missing`() {
        val fresh = RtcpDiagnostics(0.5, 3, 20.0, 900, 60)
        val healthy = RtcpDiagnostics(0.0, 0, 2.0, 1_000, 60)
        val stale = RtcpDiagnostics(0.0, 0, 0.0, 142_294, 0) // as seen on dev before the fix, during playback

        assertEquals(RtcpStats(2, 2, 0.25, 0.5, 11.0, 20.0), RtcpStatsAggregate.of(listOf(fresh, healthy, stale, null)))
        assertEquals(RtcpStats(0, 1, 0.0, 0.0, 0.0, 0.0), RtcpStatsAggregate.of(listOf(stale)))
        assertNull(RtcpStatsAggregate.of(emptyList()), "nothing plays")
    }
}
