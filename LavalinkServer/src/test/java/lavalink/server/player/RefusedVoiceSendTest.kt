package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
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

    @Test
    fun `a hold ends with its track and the next track dates its own refusal`() {
        val probe = SwitchableProbe(refusing = true)
        val client = client(GuardedUdpQueueFramePollerFactory(pool(), probe))
        val provider = CountingProvider()
        val connection = client.createConnection(4L) as MediaConnectionImpl
        connection.audioSender = provider
        startSession(connection, refused)

        Thread.sleep(200)
        val firstRefusal = provider.refusedSince
        assertNotNull(firstRefusal, "refusedSince while the first track plays")

        provider.hasAudio = false
        Thread.sleep(200)
        assertNull(provider.refusedSince, "refusedSince once nothing plays")

        provider.hasAudio = true
        Thread.sleep(200)
        val secondRefusal = provider.refusedSince
        assertNotNull(secondRefusal, "refusedSince while the next track plays")
        assertTrue(secondRefusal!! > firstRefusal!!, "the next track's refusal starts when it does, not at the first one")
    }

    @Test
    fun `a track that fails to load ends while the sends are refused`() {
        val client = serverClient()
        val player = playingPlayer()
        val ends = LinkedBlockingQueue<AudioTrackEndReason>()
        player.audioPlayer.addListener(object : AudioEventAdapter() {
            override fun onTrackEnd(p: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                ends.add(endReason)
            }
        })
        connect(client, player, refused)
        Thread.sleep(300)
        assertNotNull(player.voiceDiagnostics(null).sendRefusedSince, "voice.sendRefusedSince while refused")

        // A skip to a song whose source refuses it (an expired link, a 403).
        // lavaplayer only reports its end when the terminator is pulled, and a
        // hold pulls nothing: the queue would not move until the refusal ended
        // or the player cleanup stopped it (then replayed by the engine).
        player.play(FailingTrack())
        assertEquals(AudioTrackEndReason.REPLACED, ends.poll(1, TimeUnit.SECONDS), "end of the track the skip replaces")

        // LOAD_FAILED, or FINISHED when the terminator is pulled before
        // lavaplayer stores the exception (its own race): both advance the queue.
        val reason = ends.poll(3, TimeUnit.SECONDS)
        assertTrue(
            reason == AudioTrackEndReason.LOAD_FAILED || reason == AudioTrackEndReason.FINISHED,
            "end of a track that failed to load, 3 s after it was played: $reason"
        )
    }

    @Test
    fun `a refusal ends with its connection when a voice update replaces it`() {
        val server = server()
        val client = serverClient()
        val player = playingPlayer()
        connect(client, player, refused)
        Thread.sleep(300)
        assertNotNull(player.voiceDiagnostics(null).sendRefusedSince, "voice.sendRefusedSince while refused")

        // What PlayerRestHandler does on a voice update with a new session or
        // endpoint, or forceReconnect (a rejoin, a voice server move, the
        // engine's reconnects): destroy, create, provideTo. The new connection
        // has its own poller, which had never refused anything.
        client.destroyConnection(player.guildId)
        val second = client.createConnection(player.guildId) as MediaConnectionImpl
        player.provideTo(second)
        startSession(second, server.address)

        val packets = server.awaitRtp(25)
        assertEquals(25, packets.size, "RTP packets on the new connection")
        assertTrue(player.audioPlayer.playingTrack.position >= 24 * 20, "the track advances on the new connection")
        assertNull(player.voiceDiagnostics(second).sendRefusedSince, "voice.sendRefusedSince while audio flows on the new connection")
    }

    @Test
    fun `a track played during a refusal on the same connection keeps its date`() {
        val client = serverClient()
        val player = playingPlayer()
        val connection = connect(client, player, refused)
        Thread.sleep(300)
        val since = player.voiceDiagnostics(connection).sendRefusedSince
        assertNotNull(since, "voice.sendRefusedSince while refused")

        // What PlayerRestHandler does on every play: the track, then a new
        // sender on the connection it already has. The refusal goes on.
        player.play(SilenceTrack())
        player.provideTo(connection)
        Thread.sleep(200)

        assertEquals(since, player.voiceDiagnostics(connection).sendRefusedSince, "voice.sendRefusedSince after the skip")
        assertEquals(0, player.audioPlayer.playingTrack.position, "position of the new track while refused")
    }

    @Test
    fun `each new sender is told the refusal in progress`() {
        val probe = SwitchableProbe(refusing = true)
        val client = client(GuardedUdpQueueFramePollerFactory(pool(), probe))
        val first = CountingProvider()
        val connection = client.createConnection(5L) as MediaConnectionImpl
        connection.audioSender = first
        startSession(connection, refused)
        Thread.sleep(200)
        val since = first.refusedSince
        assertNotNull(since, "refusedSince told to the first sender")

        val second = CountingProvider()
        connection.audioSender = second
        Thread.sleep(200)

        assertEquals(since, second.refusedSince, "refusedSince told to a sender attached during the refusal")
        assertEquals(0, second.pulled.get(), "frames pulled from it while refused")
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

    /** A song whose source refuses it before its first frame (an expired link, a 403). */
    private class FailingTrack : BaseAudioTrack(
        AudioTrackInfo("failing", "test", Units.DURATION_MS_UNKNOWN, "failing", false, null)
    ) {
        override fun getSourceManager(): AudioSourceManager = SilenceSource

        override fun process(executor: LocalAudioTrackExecutor) {
            throw FriendlyException("the source refused the stream", FriendlyException.Severity.COMMON, null)
        }
    }

    /** Endless audio counting what the poller does with it. */
    private class CountingProvider(@Volatile var hasAudio: Boolean = true) : AudioFrameProvider, SendPathListener {
        val pulled = AtomicInteger()
        val refused = AtomicInteger()
        val asked = AtomicInteger()
        @Volatile var refusedSince: Long? = null

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

        override fun onSendPathChanged(refusedSince: Long?) {
            this.refusedSince = refusedSince
        }
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
