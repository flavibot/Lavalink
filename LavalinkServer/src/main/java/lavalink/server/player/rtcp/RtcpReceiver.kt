package lavalink.server.player.rtcp

import org.slf4j.LoggerFactory

/**
 * Turns the datagrams read from a media socket into [RtcpReceiverStats]: picks
 * the RTCP ones, opens them with the connection's key, keeps the report blocks
 * about our own SSRC. One instance per media connection, fed by two threads: Koe's
 * event loop until the first frame, a udp-queue socket reader after it (a report
 * can be in flight on each when Discord switches), hence the lock around the cipher.
 */
class RtcpReceiver(val stats: RtcpReceiverStats = RtcpReceiverStats()) {
    companion object {
        private val log = LoggerFactory.getLogger(RtcpReceiver::class.java)
    }

    private val cipher = RtcpCipher()

    /**
     * @param mode the transport encryption mode of the connection, null before
     * the session description
     * @param key the transport key, null before the session description
     * @param ourSsrc our audio SSRC on this connection (unsigned)
     * @return whether the datagram was an RTCP packet opened with this key
     */
    @Synchronized
    fun onDatagram(mode: String?, key: ByteArray?, ourSsrc: Long, data: ByteArray, offset: Int, length: Int): Boolean {
        if (mode == null || key == null || !RtcpParser.isRtcp(data, offset, length)) return false
        val plain = cipher.open(mode, key, data, offset, length) ?: return false
        for (report in RtcpParser.parse(plain)) {
            for (block in report.blocks) {
                if (block.ssrc != ourSsrc) continue
                stats.onReport(block)
                if (log.isDebugEnabled) {
                    log.debug(
                        "RTCP {} about ssrc {}: fraction lost {}/256, cumulative lost {}, jitter {} ({} ms), highest seq {}",
                        if (report.packetType == RtcpParser.SENDER_REPORT) "SR" else "RR",
                        block.ssrc, block.fractionLost, block.cumulativeLost, block.jitter,
                        "%.1f".format(block.jitter * 1000.0 / RtcpReceiverStats.OPUS_CLOCK_RATE), block.highestSequence
                    )
                }
            }
        }
        return true
    }
}
