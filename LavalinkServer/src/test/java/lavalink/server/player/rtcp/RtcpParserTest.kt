package lavalink.server.player.rtcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RFC 3550 section 6.4 layouts, the first one as Discord sent it on dev. */
class RtcpParserTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `the receiver report Discord sends, decrypted as received on dev`() {
        // V=2 RC=1 PT=201 length=7, sender SSRC = our SSRC (16530), one block about it, all zero on an idle connection.
        val plain = hex("81c90007 00004092 00004092 00000000 00000000 00000000 00000000 00000000")

        val reports = RtcpParser.parse(plain)

        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals(RtcpParser.RECEIVER_REPORT, report.packetType)
        assertEquals(16530L, report.senderSsrc)
        assertEquals(listOf(RtcpReportBlock(16530, 0, 0, 0, 0, 0, 0)), report.blocks)
    }

    @Test
    fun `report block fields`() {
        val plain = hex(
            "81c90007 00004092" +
                "00004092" + // SSRC of the source
                "40 000123" + // fraction lost 64/256, cumulative lost 291
                "00012345" + // extended highest sequence: 1 cycle, seq 0x2345
                "000001e0" + // jitter 480 timestamp units = 10 ms at 48 kHz
                "a1b2c3d4" + // LSR
                "00018000" // DLSR 1.5 s
        )

        val block = RtcpParser.parse(plain).single().blocks.single()

        assertEquals(16530L, block.ssrc)
        assertEquals(64, block.fractionLost)
        assertEquals(291, block.cumulativeLost)
        assertEquals(0x12345L, block.highestSequence)
        assertEquals(480L, block.jitter)
        assertEquals(0xa1b2c3d4L, block.lastSenderReport)
        assertEquals(0x18000L, block.delaySinceLastSenderReport)
    }

    @Test
    fun `cumulative lost is a signed 24-bit value`() {
        val plain = hex("81c90007 00004092 00004092 00fffffe 00000000 00000000 00000000 00000000")
        assertEquals(-2, RtcpParser.parse(plain).single().blocks.single().cumulativeLost)

        val max = hex("81c90007 00004092 00004092 ff7fffff 00000000 00000000 00000000 00000000")
        val block = RtcpParser.parse(max).single().blocks.single()
        assertEquals(0x7fffff, block.cumulativeLost)
        assertEquals(255, block.fractionLost)
    }

    @Test
    fun `sender report with two blocks after its sender info`() {
        val plain = hex(
            "82c80012 11111111" +
                "e8f5a1b2 00000000 00010000 00000064 00002710" + // NTP, RTP timestamp, packet and octet counts
                "00004092 10 000005 00000064 00000030 00000000 00000000" +
                "00000051 00 000000 00000001 00000000 00000000 00000000"
        )

        val report = RtcpParser.parse(plain).single()

        assertEquals(RtcpParser.SENDER_REPORT, report.packetType)
        assertEquals(0x11111111L, report.senderSsrc)
        assertEquals(listOf(16530L, 81L), report.blocks.map { it.ssrc })
        assertEquals(16, report.blocks[0].fractionLost)
        assertEquals(5, report.blocks[0].cumulativeLost)
        assertEquals(48L, report.blocks[0].jitter)
    }

    @Test
    fun `compound packet - the receiver report is read, SDES skipped, padding honoured`() {
        val plain = hex(
            "81c90007 00004092 00004092 08000002 00000010 00000020 00000000 00000000" +
                // SDES, one chunk with a CNAME "ab", padded by 4 bytes (P bit, last byte = 4)
                "a1ca0003 00004092 01026162 00000004"
        )

        val reports = RtcpParser.parse(plain)

        assertEquals(1, reports.size)
        assertEquals(8, reports.single().blocks.single().fractionLost)
        assertEquals(2, reports.single().blocks.single().cumulativeLost)
    }

    @Test
    fun `a receiver report without blocks says nothing was received`() {
        val reports = RtcpParser.parse(hex("80c90001 00004092"))
        assertEquals(1, reports.size)
        assertEquals(emptyList<RtcpReportBlock>(), reports.single().blocks)
    }

    @Test
    fun `malformed packets yield nothing`() {
        // Blocks announced that do not fit the length.
        assertEquals(emptyList<RtcpReport>(), RtcpParser.parse(hex("82c90007 00004092 00004092 00000000 00000000 00000000 00000000 00000000")))
        // A length running past the datagram.
        assertEquals(emptyList<RtcpReport>(), RtcpParser.parse(hex("81c90009 00004092 00004092 00000000 00000000 00000000 00000000 00000000")))
        // Version 1.
        assertEquals(emptyList<RtcpReport>(), RtcpParser.parse(hex("41c90007 00004092 00004092 00000000 00000000 00000000 00000000 00000000")))
        // Padding longer than the packet.
        assertEquals(emptyList<RtcpReport>(), RtcpParser.parse(hex("a0c90001 000040ff")))
        assertEquals(emptyList<RtcpReport>(), RtcpParser.parse(ByteArray(0)))
    }

    @Test
    fun `what else arrives on a media socket is not RTCP`() {
        val receiverReport = hex("81c90007 00004092")
        assertTrue(RtcpParser.isRtcp(receiverReport, 0, receiverReport.size))

        // Opus RTP, payload type 120.
        val rtp = hex("8078 1234 00000960 00004092 0000")
        assertFalse(RtcpParser.isRtcp(rtp, 0, rtp.size))
        // RTP with the marker bit: 0xf8 is not in 192-223 either.
        val marked = hex("80f8 1234 00000960 00004092")
        assertFalse(RtcpParser.isRtcp(marked, 0, marked.size))
        // IP discovery reply (type 2, length 70).
        val discovery = ByteArray(74).also { it[1] = 2; it[3] = 70 }
        assertFalse(RtcpParser.isRtcp(discovery, 0, discovery.size))
        // UDP ping response.
        val ping = hex("1337f00d 00000001")
        assertFalse(RtcpParser.isRtcp(ping, 0, ping.size))
        // Too short to hold a header.
        assertFalse(RtcpParser.isRtcp(receiverReport, 0, 4))
    }
}
