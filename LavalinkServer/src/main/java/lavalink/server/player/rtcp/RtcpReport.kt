package lavalink.server.player.rtcp

/**
 * One RFC 3550 report block (section 6.4.1): what the voice server received from
 * one SSRC it hears, i.e. from this node for our own SSRC.
 */
data class RtcpReportBlock(
    /** The SSRC the block is about (unsigned). */
    val ssrc: Long,
    /** Fraction of our packets lost since the previous report, 8-bit fixed point (0-255, /256). */
    val fractionLost: Int,
    /** Packets lost since the stream started. Signed 24 bits: duplicates can drive it below zero. */
    val cumulativeLost: Int,
    /** Extended highest sequence number received (cycles in the high 16 bits). */
    val highestSequence: Long,
    /** Interarrival jitter in RTP timestamp units (48 kHz for Opus). */
    val jitter: Long,
    /** Middle 32 bits of the NTP timestamp of our last sender report, 0 when none was received. */
    val lastSenderReport: Long,
    /** Delay since that sender report, in 1/65536 s. */
    val delaySinceLastSenderReport: Long,
)

/** A sender (PT 200) or receiver (PT 201) report from one compound RTCP packet. */
data class RtcpReport(
    val packetType: Int,
    /** The SSRC of whoever sent the report: the voice server, not us. */
    val senderSsrc: Long,
    val blocks: List<RtcpReportBlock>,
)

/**
 * Parses the plaintext of an RTCP packet (the clear header followed by the
 * decrypted body), per RFC 3550 section 6.4. Only sender and receiver reports
 * are read; the other packet types of a compound packet are skipped.
 */
object RtcpParser {
    const val SENDER_REPORT = 200
    const val RECEIVER_REPORT = 201

    /** Version, padding, report count, packet type, length, sender SSRC. */
    const val HEADER_LENGTH = 8
    private const val SENDER_INFO_LENGTH = 20
    private const val REPORT_BLOCK_LENGTH = 24

    /**
     * Whether a datagram on the media socket is RTCP. RTP and RTCP share the
     * socket, and RFC 5761 section 4 tells them apart by the second byte: 192-223
     * is RTCP. IP discovery replies and UDP pings never start with version 2.
     */
    fun isRtcp(packet: ByteArray, offset: Int, length: Int): Boolean {
        if (length < HEADER_LENGTH) return false
        val first = packet[offset].toInt() and 0xff
        val type = packet[offset + 1].toInt() and 0xff
        return first ushr 6 == 2 && type in 192..223
    }

    /**
     * The sender SSRC of the first packet of a compound, from the 8 bytes the
     * transport encryption leaves clear. Discord puts our own SSRC there.
     */
    fun senderSsrc(packet: ByteArray, offset: Int): Long = u32(packet, offset + 4)

    /**
     * Reads every sender and receiver report of a compound packet. Stops at the
     * first malformed packet (wrong version, a length running past the end, or
     * report blocks that do not fit), keeping what was read before it: a
     * datagram that is not RTCP at all yields nothing.
     */
    fun parse(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): List<RtcpReport> {
        val end = offset + length
        val reports = mutableListOf<RtcpReport>()
        var pos = offset
        while (end - pos >= 4) {
            val first = packet[pos].toInt() and 0xff
            if (first ushr 6 != 2) break
            val padded = first and 0x20 != 0
            val count = first and 0x1f
            val type = packet[pos + 1].toInt() and 0xff
            // The length field counts 32-bit words minus one, header included.
            val packetLength = (u16(packet, pos + 2) + 1) * 4
            if (packetLength > end - pos) break

            // RFC 3550 6.4.1: padding only ever follows the last packet of a
            // compound, and its last byte says how much of the packet it takes.
            var contentEnd = pos + packetLength
            if (padded) {
                val padding = packet[contentEnd - 1].toInt() and 0xff
                if (padding == 0 || padding > packetLength - 4) break
                contentEnd -= padding
            }

            val blocksStart = when (type) {
                SENDER_REPORT -> pos + HEADER_LENGTH + SENDER_INFO_LENGTH
                RECEIVER_REPORT -> pos + HEADER_LENGTH
                else -> -1
            }
            if (blocksStart >= 0) {
                if (blocksStart + count * REPORT_BLOCK_LENGTH > contentEnd) break
                val blocks = List(count) { block(packet, blocksStart + it * REPORT_BLOCK_LENGTH) }
                reports += RtcpReport(type, u32(packet, pos + 4), blocks)
            }
            pos += packetLength
        }
        return reports
    }

    private fun block(b: ByteArray, at: Int): RtcpReportBlock {
        val lost = (b[at + 5].toInt() and 0xff shl 16) or (b[at + 6].toInt() and 0xff shl 8) or (b[at + 7].toInt() and 0xff)
        return RtcpReportBlock(
            ssrc = u32(b, at),
            fractionLost = b[at + 4].toInt() and 0xff,
            // Sign-extend the 24-bit two's complement value.
            cumulativeLost = (lost shl 8) shr 8,
            highestSequence = u32(b, at + 8),
            jitter = u32(b, at + 12),
            lastSenderReport = u32(b, at + 16),
            delaySinceLastSenderReport = u32(b, at + 20),
        )
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff shl 8) or (b[at + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, at: Int) =
        (b[at].toLong() and 0xff shl 24) or (b[at + 1].toLong() and 0xff shl 16) or
            (b[at + 2].toLong() and 0xff shl 8) or (b[at + 3].toLong() and 0xff)
}
