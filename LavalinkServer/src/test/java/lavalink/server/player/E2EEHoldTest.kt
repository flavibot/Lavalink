package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import io.netty.buffer.ByteBuf
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeClient
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.internal.json.JsonArray
import moe.kyokobot.koe.internal.json.JsonObject
import moe.kyokobot.koe.media.AudioFrameProvider
import moe.kyokobot.koe.poller.udpqueue.QueueManagerPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bot the call's end-to-end encryption group does not admit: Koe 3.1 sends
 * nothing and pulls nothing until it is admitted. The send path the server
 * runs (a [LavalinkPlayer] playing a real lavaplayer track, the guarded
 * udp-queue poller, a loopback voice server) with the encryption state
 * switched by the test: a Koe connection in the "plain" transport mode has no
 * DAVE session to be refused by, and the real refusal needs Discord.
 */
class E2EEHoldTest {
    private val playerManager = DefaultAudioPlayerManager()
    private val playerUpdates = Executors.newSingleThreadScheduledExecutor()
    private val servers = mutableListOf<FakeVoiceServer>()
    private val pools = mutableListOf<QueueManagerPool>()
    private var options: KoeOptions? = null
    private var client: KoeClient? = null

    /** The encryption state every connection of the test reads. */
    @Volatile private var admitted = false

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
    fun `the track goes on at real time and nothing is sent while the bot is not admitted`() {
        val server = server()
        val player = playingPlayer()
        val connection = connect(client(graceMs = 200), player, server.address)
        val started = System.nanoTime()

        Thread.sleep(1_500)

        val position = player.audioPlayer.playingTrack.position
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(0, server.rtpReceived(), "RTP packets sent while not admitted")
        assertTrue(position >= 900, "the track is at $position ms after ${elapsed - 200} ms past the grace")
        // One frame a 20 ms tick: never ahead of the wall clock (+ one tick).
        assertTrue(position <= elapsed - 200 + 40, "the track is at $position ms, ahead of the ${elapsed - 200} ms past the grace")
        assertNotNull(player.voiceDiagnostics(connection).e2eeWaitingSince, "voice.e2eeWaitingSince")
    }

    @Test
    fun `nothing is pulled during the grace`() {
        val server = server()
        val player = playingPlayer()
        val connection = connect(client(graceMs = 2_000), player, server.address)

        Thread.sleep(800)

        assertEquals(0, player.audioPlayer.playingTrack.position, "position during the grace")
        assertEquals(0, server.rtpReceived(), "RTP packets sent during the grace")
        assertNull(player.voiceDiagnostics(connection).e2eeWaitingSince, "voice.e2eeWaitingSince during the grace")
    }

    @Test
    fun `once admitted the audio is sent from where the track is`() {
        val server = server()
        val player = playingPlayer()
        val connection = connect(client(graceMs = 200), player, server.address)
        Thread.sleep(1_000)
        val held = player.audioPlayer.playingTrack.position
        assertTrue(held > 0, "the track went on while not admitted")

        admitted = true
        val packets = server.awaitRtp(25)

        assertEquals(25, packets.size, "RTP packets once admitted")
        assertTrue(player.audioPlayer.playingTrack.position >= held + 24 * 20, "the track goes on from $held ms")
        assertNull(player.voiceDiagnostics(connection).e2eeWaitingSince, "voice.e2eeWaitingSince once admitted")
    }

    @Test
    fun `an idle or paused player is not drained`() {
        val provider = CountingProvider(hasAudio = false)
        val connection = client(graceMs = 0).createConnection(2L) as MediaConnectionImpl
        connection.audioSender = provider
        startSession(connection, server().address)

        Thread.sleep(300)

        assertEquals(0, provider.drained.get(), "frames drained")
        assertTrue(provider.asked.get() > 0, "the poller still asks the provider for audio")
    }

    @Test
    fun `each new sender is told the wait in progress, and the next track is not held for another grace`() {
        val first = CountingProvider()
        val connection = client(graceMs = 200).createConnection(3L) as MediaConnectionImpl
        connection.audioSender = first
        startSession(connection, server().address)
        Thread.sleep(500)
        val since = first.waitingSince
        assertNotNull(since, "waitingSince told to the first sender")

        // What PlayerRestHandler does on every play: a new sender on the same connection.
        first.hasAudio = false
        Thread.sleep(100)
        val second = CountingProvider()
        connection.audioSender = second
        Thread.sleep(100)

        assertEquals(since, second.waitingSince, "waitingSince told to a sender attached during the wait")
        assertTrue(second.drained.get() > 0, "the next track is drained at once, not held for another grace")
        assertEquals(0, second.pulled.get(), "frames sent from it")
    }

    @Test
    fun `the held frames are counted apart from the sent and the lost ones`() {
        var now = 0L
        val counter = AudioLossCounter(clock = { now })
        repeat(3) { counter.onE2EEHeld() }
        counter.onSuccess()

        now = 60_000L

        assertEquals(3, counter.lastMinuteE2EEHeld, "held frames, last whole minute")
        assertEquals(1, counter.lastMinuteSuccess, "sent frames, last whole minute")
        assertEquals(0, counter.lastMinuteLoss, "lost frames, last whole minute")
    }

    private fun client(graceMs: Long): KoeClient {
        // DAVE needs a voice gateway: the test stands in for its answer.
        val factory = GuardedUdpQueueFramePollerFactory(pool(), UdpSendProbe(), e2eeReady = { _: MediaConnection -> admitted }, e2eeGraceMs = graceMs)
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
        override fun getSourceManager(): com.sedmelluq.discord.lavaplayer.source.AudioSourceManager = SilenceSource

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

    private object SilenceSource : com.sedmelluq.discord.lavaplayer.source.AudioSourceManager {
        override fun getSourceName() = "silence"
        override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? = null
        override fun isTrackEncodable(track: AudioTrack) = true
        override fun encodeTrack(track: AudioTrack, output: DataOutput) {}
        override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack = SilenceTrack()
        override fun shutdown() {}
    }

    /** Endless audio counting what the poller does with it. */
    private class CountingProvider(@Volatile var hasAudio: Boolean = true) : AudioFrameProvider, SendPathListener {
        val pulled = AtomicInteger()
        val drained = AtomicInteger()
        val asked = AtomicInteger()
        @Volatile var waitingSince: Long? = null

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
        override fun onSendRefused() {}
        override fun onSendPathChanged(refusedSince: Long?) {}
        override fun drainHeldFrame() {
            drained.incrementAndGet()
        }

        override fun onE2EEWaitChanged(waitingSince: Long?) {
            this.waitingSince = waitingSince
        }
    }

    /** A UDP socket on loopback standing in for a Discord voice server; counts the RTP packets. */
    private class FakeVoiceServer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        private val rtp = java.util.concurrent.LinkedBlockingQueue<Int>()
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
                            rtp.add(datagram.length)
                            received.incrementAndGet()
                        }
                    } catch (_: IOException) {
                        // closed
                    }
                }
            }, "FakeVoiceServer").apply { isDaemon = true }.start()
        }

        fun rtpReceived() = received.get()

        fun awaitRtp(count: Int): List<Int> {
            val packets = mutableListOf<Int>()
            while (packets.size < count) packets += rtp.poll(5, TimeUnit.SECONDS) ?: break
            return packets
        }

        override fun close() = socket.close()
    }
}
