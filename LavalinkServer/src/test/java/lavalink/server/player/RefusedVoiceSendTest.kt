package lavalink.server.player

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
import io.netty.buffer.ByteBuf
import lavalink.server.config.KoeConfiguration
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeClient
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.internal.json.JsonArray
import moe.kyokobot.koe.internal.json.JsonObject
import moe.kyokobot.koe.media.AudioFrameProvider
import moe.kyokobot.koe.poller.FramePollerFactory
import moe.kyokobot.koe.poller.udpqueue.QueueManagerPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The send path the server runs with: a [LavalinkPlayer] playing a real
 * lavaplayer track, a frame poller on the native udp-queue, and a Koe
 * connection in the "plain" transport mode, so no voice gateway is needed.
 *
 * The refused destination is the limited broadcast address: a socket without
 * SO_BROADCAST gets EACCES from the kernel on every send to it, the
 * udp-queue's native socket as much as any other. That is the same synchronous
 * refusal as the EPERM of an iptables OUTPUT DROP rule, which needs
 * privileges a test does not have.
 */
class RefusedVoiceSendTest {
    private val refused = InetSocketAddress("255.255.255.255", 50_000)
    private val playerManager = DefaultAudioPlayerManager()
    private val playerUpdates = Executors.newSingleThreadScheduledExecutor()
    private val servers = mutableListOf<FakeVoiceServer>()
    private val pools = mutableListOf<QueueManagerPool>()
    private var options: KoeOptions? = null
    private var client: KoeClient? = null

    @AfterEach
    fun tearDown() {
        client?.close()
        options?.eventLoopGroup?.shutdownGracefully(0, 1, TimeUnit.SECONDS)?.syncUninterruptibly()
        playerManager.shutdown()
        playerUpdates.shutdownNow()
        servers.forEach { it.close() }
        pools.forEach { it.close() }
    }

    @Test
    fun `a track does not advance over audio the OS refuses to send`() {
        DatagramChannel.open(StandardProtocolFamily.INET).use { channel ->
            assertThrows<IOException>("the kernel must refuse a send to $refused for this test to mean anything") {
                channel.send(ByteBuffer.allocate(0), refused)
            }
        }
        val client = serverClient()
        val player = playingPlayer()
        val connection = connect(client, player, refused)

        Thread.sleep(1_500)

        val position = player.audioPlayer.playingTrack.position
        assertEquals(0, position, "the track advanced $position ms while every UDP send to the voice server was refused")
        assertNotNull(player.voiceDiagnostics(connection).sendRefusedSince, "voice.sendRefusedSince")
    }

    @Test
    fun `a track advances and its audio arrives when the OS accepts the sends`() {
        val server = server()
        val client = serverClient()
        val player = playingPlayer()
        val connection = connect(client, player, server.address)

        val packets = server.awaitRtp(50)

        assertEquals(50, packets.size, "RTP packets received")
        val position = player.audioPlayer.playingTrack.position
        assertTrue(position >= 49 * 20, "the track is at $position ms after 50 frames reached the server")
        assertNull(player.voiceDiagnostics(connection).sendRefusedSince, "voice.sendRefusedSince")
    }

    @Test
    fun `the audio resumes where it stopped once the sends are accepted again`() {
        val probe = SwitchableProbe(refusing = true)
        val server = server()
        val client = client(GuardedUdpQueueFramePollerFactory(pool(), probe))
        val player = playingPlayer()
        val connection = connect(client, player, server.address)

        Thread.sleep(500)
        assertEquals(0, player.audioPlayer.playingTrack.position, "position while refused")
        assertEquals(0, server.rtpReceived(), "RTP packets sent while refused")
        assertNotNull(player.voiceDiagnostics(connection).sendRefusedSince, "voice.sendRefusedSince while refused")

        probe.refusing = false
        val packets = server.awaitRtp(25)

        assertEquals(25, packets.size, "RTP packets received once accepted")
        // The first frame of the track is the first one sent: nothing was pulled and lost meanwhile.
        assertEquals(0, packets.first().timestamp, "RTP timestamp of the first packet")
        packets.zipWithNext().forEach { (a, b) -> assertEquals(a.timestamp + 960, b.timestamp, "consecutive frames") }
        assertTrue(player.audioPlayer.playingTrack.position >= 24 * 20, "the track advances again")
        assertNull(player.voiceDiagnostics(connection).sendRefusedSince, "voice.sendRefusedSince once accepted")
    }

    @Test
    fun `each poll held by a refusal counts one send failure and pulls nothing`() {
        val client = client(GuardedUdpQueueFramePollerFactory(pool(), UdpSendProbe()))
        val provider = CountingProvider()
        val connection = client.createConnection(2L) as MediaConnectionImpl
        connection.audioSender = provider
        val started = System.nanoTime()
        startSession(connection, refused)

        Thread.sleep(600)

        val polls = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 20
        assertEquals(0, provider.pulled.get(), "frames pulled while refused")
        val refusedPolls = provider.refused.get()
        assertTrue(refusedPolls.toLong() in polls / 2..polls + 4, "$refusedPolls send failures counted over about $polls polls")
    }

    @Test
    fun `an idle player is neither probed nor held`() {
        val probe = SwitchableProbe(refusing = true)
        val client = client(GuardedUdpQueueFramePollerFactory(pool(), probe))
        val provider = CountingProvider(hasAudio = false)
        val connection = client.createConnection(3L) as MediaConnectionImpl
        connection.audioSender = provider
        startSession(connection, refused)

        Thread.sleep(300)

        assertEquals(0, probe.calls.get(), "probes")
        assertEquals(0, provider.refused.get(), "send failures")
        assertTrue(provider.asked.get() > 0, "the poller still asks the provider for audio")
    }

    /** A client with the frame poller factory [KoeConfiguration] gives the server. */
    private fun serverClient(): KoeClient {
        val serverOptions = KoeConfiguration(ServerConfig()).koeOptions()
        serverOptions.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        return client(serverOptions.framePollerFactory)
    }

    private fun client(factory: FramePollerFactory): KoeClient {
        // DAVE needs a voice gateway.
        val options = KoeOptions.builder().setDAVEEnabled(false).setFramePollerFactory(factory).create()
        this.options = options
        return Koe.koe(options).newClient(1234L).also { client = it }
    }

    private fun pool() = QueueManagerPool(1, QueueManagerPool.DEFAULT_BUFFER_DURATION).also { pools += it }

    private fun server() = FakeVoiceServer().also { servers += it }

    private fun playingPlayer(): LavalinkPlayer {
        val socketContext = Mockito.mock(SocketContext::class.java)
        // No websocket client: player updates are skipped as for a paused session.
        Mockito.`when`(socketContext.sessionPaused).thenReturn(true)
        Mockito.`when`(socketContext.playerUpdateService).thenReturn(playerUpdates)
        val player = LavalinkPlayer(socketContext, 1L, ServerConfig(), playerManager, emptyList())
        player.audioPlayer.playTrack(SilenceTrack())
        return player
    }

    private fun connect(client: KoeClient, player: LavalinkPlayer, server: InetSocketAddress): MediaConnectionImpl {
        val connection = client.createConnection(player.guildId) as MediaConnectionImpl
        player.provideTo(connection)
        startSession(connection, server)
        return connection
    }

    /** What READY and SESSION_DESCRIPTION do on a real connection: a UDP handler, then frame polling. */
    private fun startSession(connection: MediaConnectionImpl, server: InetSocketAddress) {
        val udp = DiscordUDPConnection(connection, server, 1)
        connection.setConnectionHandler(udp)
        val key = JsonArray()
        repeat(32) { key.add(0) }
        udp.handleSessionDescription(JsonObject().add("mode", "plain").add("secret_key", key))
    }

    /** Endless Opus silence from timecode 0, 20 ms a frame: its position is exactly the audio pulled from it. */
    private class SilenceTrack : BaseAudioTrack(
        AudioTrackInfo("silence", "test", Units.DURATION_MS_UNKNOWN, "silence", true, null)
    ) {
        // The track start event encodes the track: it needs a source.
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

    /** Endless audio counting what the poller does with it. */
    private class CountingProvider(private val hasAudio: Boolean = true) : AudioFrameProvider, SendPathListener {
        val pulled = AtomicInteger()
        val refused = AtomicInteger()
        val asked = AtomicInteger()

        override fun onCodecChanged(codec: CodecInstance) {}
        override fun dispose() {}
        override fun canProvide(): Boolean {
            asked.incrementAndGet()
            return hasAudio
        }

        override fun provideFrame(buf: ByteBuf): Boolean {
            pulled.incrementAndGet()
            buf.writeBytes(OpusCodecInfo.SILENCE_FRAME)
            return true
        }

        override fun hasAudioToSend() = hasAudio
        override fun onSendRefused() {
            refused.incrementAndGet()
        }

        override fun onSendPathChanged(refusedSince: Long?) {}
    }

    /** The OS's answer, switched by the test: lets a reachable server stand in for a refused one that comes back. */
    private class SwitchableProbe(@Volatile var refusing: Boolean) : UdpSendProbe() {
        val calls = AtomicInteger()

        override fun refusal(address: InetSocketAddress): IOException? {
            calls.incrementAndGet()
            return if (refusing) SocketException("Operation not permitted") else null
        }
    }

    private class Rtp(val seq: Int, val timestamp: Int)

    /** A UDP socket on loopback standing in for a Discord voice server; keeps the RTP packets (the probes are empty). */
    private class FakeVoiceServer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        private val rtp = LinkedBlockingQueue<Rtp>()
        private val received = AtomicInteger()
        val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

        init {
            Thread({
                val buf = ByteArray(2048)
                while (!socket.isClosed) {
                    try {
                        val datagram = DatagramPacket(buf, buf.size)
                        socket.receive(datagram)
                        if (datagram.length >= 12) {
                            val packet = ByteBuffer.wrap(datagram.data, 0, datagram.length)
                            rtp.add(Rtp(packet.getChar(2).code, packet.getInt(4)))
                            received.incrementAndGet()
                        }
                    } catch (_: IOException) {
                        // closed
                    }
                }
            }, "FakeVoiceServer").apply { isDaemon = true }.start()
        }

        fun rtpReceived() = received.get()

        fun awaitRtp(count: Int): List<Rtp> {
            val packets = mutableListOf<Rtp>()
            while (packets.size < count) packets += rtp.poll(5, TimeUnit.SECONDS) ?: break
            return packets
        }

        override fun close() = socket.close()
    }
}
