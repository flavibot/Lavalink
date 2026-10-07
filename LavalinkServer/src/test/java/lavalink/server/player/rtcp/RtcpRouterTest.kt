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

    /** A media connection after its session description: SSRC, AES-GCM key. */
    private fun connection(guild: Long, ssrc: Int, key: ByteArray, server: InetSocketAddress = voiceServer): DiscordUDPConnection {
        val media = client.createConnection(guild) as MediaConnectionImpl
        val udp = DiscordUDPConnection(media, server, ssrc)
        media.setConnectionHandler(udp)
        val keyArray = JsonArray()
        key.forEach { keyArray.add(it.toInt() and 0xff) }
        udp.handleSessionDescription(JsonObject().add("mode", RtcpCipher.AES_GCM).add("secret_key", keyArray))
        return udp
    }

    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun receiverReport(key: ByteArray, ssrc: Int, fractionLost: Int): ByteArray {
        val plain = ByteArray(32)
        plain[0] = 0x81.toByte(); plain[1] = 201.toByte(); plain[3] = 7
        for (at in listOf(4, 8)) for (i in 0 until 4) plain[at + i] = (ssrc ushr (24 - 8 * i)).toByte()
        plain[12] = fractionLost.toByte()
        val nonce = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(plain, 0, 8)
        return plain.copyOf(8) + cipher.doFinal(plain, 8, 24) + nonce.copyOf(4)
    }

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
}
