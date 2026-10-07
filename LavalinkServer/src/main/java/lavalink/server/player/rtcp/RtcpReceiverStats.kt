package lavalink.server.player.rtcp

import dev.arbjerg.lavalink.protocol.v4.RtcpDiagnostics
import dev.arbjerg.lavalink.protocol.v4.RtcpStats

/**
 * What the voice server said about our audio, for one media connection: the
 * latest report block about our SSRC, and how many arrived in the last 60 s.
 *
 * Written by the thread that read the report, read by the player update thread,
 * hence the lock (one report a second, a read every few seconds).
 */
class RtcpReceiverStats(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        /** RTP clock of Opus: 48 000 timestamp units a second. */
        const val OPUS_CLOCK_RATE = 48_000.0
        private const val WINDOW_SECONDS = 60
    }

    private var latest: RtcpReportBlock? = null
    private var latestAt = 0L

    // One bucket per second of the last minute, each tagged with the second it counts.
    private val counts = IntArray(WINDOW_SECONDS)
    private val seconds = LongArray(WINDOW_SECONDS) { Long.MIN_VALUE }

    @Synchronized
    fun onReport(block: RtcpReportBlock) {
        val now = clock()
        latest = block
        latestAt = now
        val second = Math.floorDiv(now, 1000L)
        val i = Math.floorMod(second, WINDOW_SECONDS.toLong()).toInt()
        if (seconds[i] != second) {
            seconds[i] = second
            counts[i] = 0
        }
        counts[i]++
    }

    /** Null until the first report about our SSRC arrives on this connection. */
    @Synchronized
    fun snapshot(): RtcpDiagnostics? {
        val block = latest ?: return null
        val now = clock()
        val second = Math.floorDiv(now, 1000L)
        var lastMinute = 0
        for (i in 0 until WINDOW_SECONDS) {
            if (second - seconds[i] in 0 until WINDOW_SECONDS) lastMinute += counts[i]
        }
        return RtcpDiagnostics(
            fractionLost = block.fractionLost / 256.0,
            cumulativeLost = block.cumulativeLost,
            jitterMs = block.jitter * 1000.0 / OPUS_CLOCK_RATE,
            reportAgeMs = (now - latestAt).coerceAtLeast(0),
            reportsLastMinute = lastMinute,
        )
    }
}

/**
 * The stats op's view of [RtcpDiagnostics] over the playing players. A report
 * older than [FRESH_MS] (Discord sends about one a second) counts as missing:
 * an old "nothing lost" must not read as a healthy path.
 */
object RtcpStatsAggregate {
    const val FRESH_MS = 10_000L

    /** @return null when nothing plays */
    fun of(playing: List<RtcpDiagnostics?>): RtcpStats? {
        if (playing.isEmpty()) return null
        val fresh = playing.filterNotNull().filter { it.reportAgeMs <= FRESH_MS }
        return RtcpStats(
            players = fresh.size,
            playersWithoutReports = playing.size - fresh.size,
            fractionLostAvg = fresh.map { it.fractionLost }.average().takeIf { it.isFinite() } ?: 0.0,
            fractionLostMax = fresh.maxOfOrNull { it.fractionLost } ?: 0.0,
            jitterMsAvg = fresh.map { it.jitterMs }.average().takeIf { it.isFinite() } ?: 0.0,
            jitterMsMax = fresh.maxOfOrNull { it.jitterMs } ?: 0.0,
        )
    }
}
