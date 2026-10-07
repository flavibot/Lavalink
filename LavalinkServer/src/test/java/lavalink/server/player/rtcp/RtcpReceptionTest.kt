package lavalink.server.player.rtcp

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import dev.arbjerg.lavalink.protocol.v4.StatsData
import dev.arbjerg.lavalink.protocol.v4.VoiceDiagnostics
import dev.arbjerg.lavalink.protocol.v4.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import lavalink.server.config.KoeConfiguration
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer
import lavalink.server.io.StatsCollector
import lavalink.server.player.LavalinkPlayer
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeClient
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.internal.json.JsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.socket.WebSocketSession
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import moe.kyokobot.koe.internal.json.JsonObject as KoeJson

/**
 * Discord's RTCP receiver reports, end to end on the server's own path: a
 * [SocketContext] with its Koe listener, a [LavalinkPlayer] playing a real
 * lavaplayer track, the frame poller factory [KoeConfiguration] builds (the
 * native udp-queue), and a Koe media connection that ran IP discovery and
 * encrypts with aead_aes256_gcm_rtpsize.
 *
 * The voice server is a loopback socket that behaves like the one measured on
 * dev: it answers IP discovery, and sends an encrypted receiver report to the
 * address the RTP last came from (Koe's discovery socket until the first frame).
 */
class RtcpReceptionTest {
    private val ssrc = 16530
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val playerManager = DefaultAudioPlayerManager()
    private val servers = mutableListOf<DiscordLikeVoiceServer>()
    private var options: KoeOptions? = null
    private var client: KoeClient? = null
    private var context: SocketContext? = null

    @AfterEach
    fun tearDown() {
        context?.shutdown()
        client?.close()
        options?.eventLoopGroup?.shutdownGracefully(0, 1, TimeUnit.SECONDS)?.syncUninterruptibly()
        playerManager.shutdown()
        servers.forEach { it.close() }
    }

    @Test
    fun `a report sent back to the source of the audio reaches the player's voice diagnostics`() {
        val server = server()
        val (player, connection) = playingPlayer(server)

        val rtpSource = server.awaitRtpSource()
        assertNotEquals(server.discoverySource, rtpSource, "the audio leaves from the udp-queue's socket, not Koe's")

        server.sendReceiverReport(fractionLost = 64, cumulativeLost = 12, highestSequence = 0x0001_2345, jitter = 480)

        val rtcp = awaitRtcp(player, connection) { it["reportsLastMinute"]!!.jsonPrimitive.int >= 1 }
        assertEquals(0.25, rtcp["fractionLost"]!!.jsonPrimitive.double, "fractionLost")
        assertEquals(12, rtcp["cumulativeLost"]!!.jsonPrimitive.int, "cumulativeLost")
        assertEquals(10.0, rtcp["jitterMs"]!!.jsonPrimitive.double, "jitterMs")
        assertTrue(rtcp["reportAgeMs"]!!.jsonPrimitive.long < 2_000, "reportAgeMs")
    }

    @Test
    fun `reports keep arriving while the audio flows, one rolling count per connection`() {
        val server = server()
        val (player, connection) = playingPlayer(server)
        server.awaitRtpSource()

        repeat(5) {
            server.sendReceiverReport(fractionLost = it * 10, cumulativeLost = it, highestSequence = 100L + it, jitter = 96)
            Thread.sleep(50)
        }

        val rtcp = awaitRtcp(player, connection) { it["reportsLastMinute"]!!.jsonPrimitive.int >= 5 }
        assertEquals(5, rtcp["reportsLastMinute"]!!.jsonPrimitive.int, "reportsLastMinute")
        assertEquals(40 / 256.0, rtcp["fractionLost"]!!.jsonPrimitive.double, "fractionLost of the latest report")
        assertEquals(4, rtcp["cumulativeLost"]!!.jsonPrimitive.int, "cumulativeLost of the latest report")
        assertEquals(2.0, rtcp["jitterMs"]!!.jsonPrimitive.double, "jitterMs")
    }

    @Test
    fun `before the first frame the reports come to Koe's socket and are read there`() {
        val server = server()
        val (player, connection) = connectedPlayer(server, playing = false)

        server.sendReceiverReport(fractionLost = 0, cumulativeLost = 0, highestSequence = 0, jitter = 0)

        val rtcp = awaitRtcp(player, connection) { it["reportsLastMinute"]!!.jsonPrimitive.int >= 1 }
        assertEquals(0.0, rtcp["fractionLost"]!!.jsonPrimitive.double, "fractionLost")
        assertEquals(1, rtcp["reportsLastMinute"]!!.jsonPrimitive.int, "reportsLastMinute")
    }

    @Test
    fun `the stats op carries the reports of the playing players`() {
        val server = server()
        val (player, connection) = playingPlayer(server)
        server.awaitRtpSource()
        server.sendReceiverReport(fractionLost = 128, cumulativeLost = 3, highestSequence = 7, jitter = 960)
        awaitRtcp(player, connection) { it["reportsLastMinute"]!!.jsonPrimitive.int >= 1 }

        val socketServer = Mockito.mock(SocketServer::class.java)
        Mockito.`when`(socketServer.contexts).thenReturn(listOf(context!!))
        val stats = json.parseToJsonElement(
            json.encodeToString(StatsData.serializer(), StatsCollector(socketServer).retrieveStats(context!!) as StatsData)
        ).jsonObject
        val rtcp = stats["rtcp"]?.jsonObject ?: throw AssertionError("no rtcp in the stats op: $stats")
        assertEquals(1, rtcp["players"]!!.jsonPrimitive.int, "players with a fresh report")
        assertEquals(0.5, rtcp["fractionLostMax"]!!.jsonPrimitive.double, "fractionLostMax")
        assertEquals(20.0, rtcp["jitterMsMax"]!!.jsonPrimitive.double, "jitterMsMax")
    }

    private fun awaitRtcp(player: LavalinkPlayer, connection: MediaConnectionImpl, until: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var voice: JsonObject
        do {
            voice = json.parseToJsonElement(
                json.encodeToString(VoiceDiagnostics.serializer(), player.voiceDiagnostics(connection))
            ).jsonObject
            val rtcp = voice["rtcp"]?.jsonObject
            if (rtcp != null && until(rtcp)) return rtcp
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        throw AssertionError("no RTCP report in the player's voice diagnostics within 5 s: $voice")
    }

    private fun playingPlayer(server: DiscordLikeVoiceServer) = connectedPlayer(server, playing = true)

    /**
     * The server's objects, and what Koe's voice gateway does on READY and
     * SESSION_DESCRIPTION: the dispatcher events the server listens to, IP
     * discovery on the media socket, then frame polling.
     */
    private fun connectedPlayer(server: DiscordLikeVoiceServer, playing: Boolean): Pair<LavalinkPlayer, MediaConnectionImpl> {
        val serverOptions = KoeConfiguration(ServerConfig()).koeOptions()
        serverOptions.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        val options = KoeOptions.builder().setDAVEEnabled(false).setFramePollerFactory(serverOptions.framePollerFactory).create()
        this.options = options
        val client = Koe.koe(options).newClient(1234L).also { client = it }

        // No websocket client: what the context sends goes nowhere.
        val session = Mockito.mock(WebSocketSession::class.java)
        val socketServer = Mockito.mock(SocketServer::class.java)
        val context = SocketContext(
            "rtcp", playerManager, ServerConfig(), session, socketServer, StatsCollector(socketServer),
            1234L, null, client, emptyList(), emptyList()
        ).also { context = it }

        val player = context.getPlayer(1L)
        if (playing) player.audioPlayer.playTrack(SilenceTrack())
        val connection = context.getMediaConnection(player) as MediaConnectionImpl
        player.provideTo(connection)

        connection.dispatcher.gatewayReady(server.address, ssrc)
        val udp = DiscordUDPConnection(connection, server.address, ssrc)
        connection.setConnectionHandler(udp)
        udp.connect().toCompletableFuture().get(5, TimeUnit.SECONDS)
        val keyArray = JsonArray()
        key.forEach { keyArray.add(it.toInt() and 0xff) }
        val description = KoeJson().add("mode", "aead_aes256_gcm_rtpsize").add("secret_key", keyArray)
        connection.dispatcher.sessionDescription(description)
        udp.handleSessionDescription(description)
        return player to connection
    }

    private fun server() = DiscordLikeVoiceServer(key, ssrc).also { servers += it }

    /** Endless Opus silence, 20 ms a frame. */
    private class SilenceTrack : BaseAudioTrack(
        AudioTrackInfo("silence", "test", Units.DURATION_MS_UNKNOWN, "silence", true, null)
    ) {
        override fun getSourceManager(): AudioSourceManager = SilenceSource

        override fun process(executor: LocalAudioTrackExecutor) {
            executor.executeProcessingLoop({
                var timecode = 0L
                while (true) {
                    executor.processingContext.frameBuffer.consume(
                        ImmutableAudioFrame(timecode, OpusCodecInfo.SILENCE_FRAME, 100, StandardAudioDataFormats.DISCORD_OPUS)
                    )
                    timecode += 20
                }
            }, null)
        }
    }

    private object SilenceSource : AudioSourceManager {
        override fun getSourceName() = "silence"
        override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? = null
        override fun isTrackEncodable(track: AudioTrack) = true
        override fun encodeTrack(track: AudioTrack, output: DataOutput) {}
        override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack = SilenceTrack()
        override fun shutdown() {}
    }

    /**
     * A loopback voice server doing what Discord's did on dev: it answers IP
     * discovery, and sends its receiver reports to where the RTP last came from
     * (the discovery socket before any). A report is an RR about our SSRC in
     * the aead_aes256_gcm_rtpsize layout: the 8-byte header in clear as
     * associated data, the report block encrypted, the 16-byte tag, then a
     * 4-byte nonce counter from 0.
     */
    private class DiscordLikeVoiceServer(private val key: ByteArray, private val ssrc: Int) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        private val nonce = AtomicInteger()
        @Volatile var discoverySource: InetSocketAddress? = null
        @Volatile var rtpSource: InetSocketAddress? = null
        val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

        init {
            Thread({
                val buf = ByteArray(2048)
                while (!socket.isClosed) {
                    try {
                        val datagram = DatagramPacket(buf, buf.size)
                        socket.receive(datagram)
                        val from = datagram.socketAddress as InetSocketAddress
                        if (datagram.length == 74 && buf[1].toInt() == 1) {
                            discoverySource = from
                            socket.send(DatagramPacket(discoveryReply(from), 74, from))
                        } else if (datagram.length >= 12 && buf[0].toInt() and 0xc0 == 0x80) {
                            rtpSource = from
                        }
                    } catch (_: IOException) {
                        // closed
                    }
                }
            }, "DiscordLikeVoiceServer").apply { isDaemon = true }.start()
        }

        fun awaitRtpSource(): InetSocketAddress {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (rtpSource == null && System.nanoTime() < deadline) Thread.sleep(10)
            return rtpSource ?: throw AssertionError("no RTP reached the voice server")
        }

        fun sendReceiverReport(fractionLost: Int, cumulativeLost: Int, highestSequence: Long, jitter: Long) {
            val to = rtpSource ?: discoverySource ?: error("nobody to report to")
            val plain = ByteArray(32)
            plain[0] = 0x81.toByte() // V=2, one report block
            plain[1] = 201.toByte()
            plain[3] = 7 // 8 words
            putInt(plain, 4, ssrc) // Discord puts our SSRC as the sender too
            putInt(plain, 8, ssrc)
            putInt(plain, 12, (fractionLost shl 24) or (cumulativeLost and 0xffffff))
            putInt(plain, 16, highestSequence.toInt())
            putInt(plain, 20, jitter.toInt())

            val n = nonce.getAndIncrement()
            val iv = ByteArray(12)
            for (i in 0 until 4) iv[i] = (n ushr (8 * i)).toByte()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(plain, 0, 8)
            val sealed = cipher.doFinal(plain, 8, plain.size - 8)
            val packet = plain.copyOf(8) + sealed + iv.copyOf(4)
            socket.send(DatagramPacket(packet, packet.size, to))
        }

        private fun discoveryReply(to: InetSocketAddress): ByteArray {
            val reply = ByteArray(74)
            reply[1] = 2
            reply[3] = 70
            putInt(reply, 4, ssrc)
            val ip = to.address.hostAddress.toByteArray()
            System.arraycopy(ip, 0, reply, 8, ip.size)
            reply[72] = (to.port ushr 8).toByte()
            reply[73] = to.port.toByte()
            return reply
        }

        private fun putInt(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }

        override fun close() = socket.close()
    }
}
