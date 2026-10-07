package lavalink.server.player.rtcp

import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeClient
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.internal.json.JsonArray
import moe.kyokobot.koe.internal.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One udp-queue socket carries the reports of every player it sends for. */
class RtcpRouterTest {
    private val voiceServer = InetSocketAddress("127.0.0.1", 50_001)
    private val options: KoeOptions = KoeOptions.builder().setDAVEEnabled(false).create()
    private val client: KoeClient = Koe.koe(options).newClient(1234L)
    private val router = RtcpRouter()

    @AfterEach
    fun tearDown() {
        client.close()
        options.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
    }

    /** A media connection after its session description: SSRC, transport key and mode (AES-GCM unless said). */
    private fun connection(
        guild: Long,
        ssrc: Int,
        key: ByteArray,
        server: InetSocketAddress = voiceServer,
        mode: String = RtcpCipher.AES_GCM,
    ): DiscordUDPConnection {
        val media = client.createConnection(guild) as MediaConnectionImpl
        val udp = DiscordUDPConnection(media, server, ssrc)
        media.setConnectionHandler(udp)
        val keyArray = JsonArray()
        key.forEach { keyArray.add(it.toInt() and 0xff) }
        udp.handleSessionDescription(JsonObject().add("mode", mode).add("secret_key", keyArray))
        return udp
    }

    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * An RR with one block about [ssrc]. Discord puts that SSRC in the clear
     * sender field too (seen on dev); [sender] lets a test put another there,
     * as RFC 3550 would (the reporter's own SSRC).
     */
    private fun plainReport(ssrc: Int, fractionLost: Int, sender: Int = ssrc): ByteArray {
        val plain = ByteArray(32)
        plain[0] = 0x81.toByte(); plain[1] = 201.toByte(); plain[3] = 7
        for (i in 0 until 4) plain[4 + i] = (sender ushr (24 - 8 * i)).toByte()
        for (i in 0 until 4) plain[8 + i] = (ssrc ushr (24 - 8 * i)).toByte()
        plain[12] = fractionLost.toByte()
        return plain
    }

    /** aead_aes256_gcm_rtpsize: 8 clear bytes as associated data, tag, 4-byte nonce counter (little endian). */
    private fun sealAesGcm(key: ByteArray, counter: Int, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 4) nonce[i] = (counter ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(plain, 0, 8)
        return plain.copyOf(8) + cipher.doFinal(plain, 8, plain.size - 8) + nonce.copyOf(4)
    }

    /** aead_xchacha20_poly1305_rtpsize, same layout; the counter is the first 4 of the 24 nonce bytes. */
    private fun sealXChaCha(key: ByteArray, counter: Int, plain: ByteArray): ByteArray {
        val nonce = ByteArray(24)
        for (i in 0 until 4) nonce[i] = (counter ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hChaCha20(key, nonce), "ChaCha20"), IvParameterSpec(nonce.copyOfRange(12, 24)))
        cipher.updateAAD(plain, 0, 8)
        return plain.copyOf(8) + cipher.doFinal(plain, 8, plain.size - 8) + nonce.copyOf(4)
    }

    private fun receiverReport(key: ByteArray, ssrc: Int, fractionLost: Int): ByteArray =
        sealAesGcm(key, 0, plainReport(ssrc, fractionLost))

    @Test
    fun `two players on the same voice server each get their own reports`() {
        val keyA = key()
        val keyB = key()
        val a = RtcpReceiver()
        val b = RtcpReceiver()
        router.register(connection(1L, 51, keyA), a)
        router.register(connection(2L, 56, keyB), b)

        val forB = receiverReport(keyB, 56, fractionLost = 128)
        assertTrue(router.onDatagram(voiceServer, forB, 0, forB.size))

        assertNull(a.stats.snapshot(), "A's key does not open B's report")
        assertEquals(0.5, b.stats.snapshot()?.fractionLost, "B's report")
    }

    @Test
    fun `a report about another SSRC opened with our key is not ours`() {
        val key = key()
        val receiver = RtcpReceiver()
        router.register(connection(1L, 51, key), receiver)

        val aboutAnother = receiverReport(key, 52, fractionLost = 255)
        assertTrue(router.onDatagram(voiceServer, aboutAnother, 0, aboutAnother.size))

        assertNull(receiver.stats.snapshot())
    }

    @Test
    fun `nothing is routed from a server without registrations, or after the registration is closed`() {
        val key = key()
        val receiver = RtcpReceiver()
        val registration = router.register(connection(1L, 51, key), receiver)!!
        val report = receiverReport(key, 51, fractionLost = 1)

        assertFalse(router.onDatagram(InetSocketAddress("127.0.0.1", 50_002), report, 0, report.size), "another voice server")

        registration.close()
        assertEquals(0, router.registrations())
        assertFalse(router.onDatagram(voiceServer, report, 0, report.size), "after close")
        assertNull(receiver.stats.snapshot())
    }

    @Test
    fun `datagrams that are not RTCP are left alone`() {
        val receiver = RtcpReceiver()
        router.register(connection(1L, 51, key()), receiver)
        val rtp = byteArrayOf(0x80.toByte(), 0x78, 0, 1, 0, 0, 3, 0xc0.toByte(), 0, 0, 0, 51)

        assertFalse(router.onDatagram(voiceServer, rtp, 0, rtp.size))
    }

    /**
     * Two connections to one voice server opened in the same second: Discord
     * counts each one's reports from 0 at about one a second, so their nonce
     * counters are equal. A is registered first, B's report comes first.
     */
    private fun sameCounterOnOneServer(mode: String, sender: (Int) -> Int, seal: (ByteArray, Int, ByteArray) -> ByteArray) {
        val keyA = key()
        val keyB = key()
        val a = RtcpReceiver()
        val b = RtcpReceiver()
        router.register(connection(1L, 51, keyA, mode = mode), a)
        router.register(connection(2L, 56, keyB, mode = mode), b)

        val forB = seal(keyB, 3, plainReport(56, 128, sender = sender(56)))
        val forA = seal(keyA, 3, plainReport(51, 64, sender = sender(51)))
        assertTrue(router.onDatagram(voiceServer, forB, 0, forB.size), "B's report opens with B's key")
        assertEquals(0.5, b.stats.snapshot()?.fractionLost, "B's report")
        assertTrue(router.onDatagram(voiceServer, forA, 0, forA.size), "A's own report opens with A's key")
        assertEquals(0.25, a.stats.snapshot()?.fractionLost, "A's report")
    }

    @Test
    fun `XChaCha20, two connections with the same nonce counter each get their own report`() {
        sameCounterOnOneServer(RtcpCipher.XCHACHA20_POLY1305, sender = { it }, ::sealXChaCha)
    }

    @Test
    fun `XChaCha20, the same nonce counter, reports that do not carry our SSRC in clear`() {
        // RFC 3550 puts the reporter's own SSRC there: then only the keys can tell the connections apart.
        sameCounterOnOneServer(RtcpCipher.XCHACHA20_POLY1305, sender = { 0x5eed }, ::sealXChaCha)
    }

    @Test
    fun `AES-GCM, two connections with the same nonce counter each get their own report`() {
        sameCounterOnOneServer(RtcpCipher.AES_GCM, sender = { 0x5eed }, ::sealAesGcm)
    }
}
