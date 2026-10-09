package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.player.filters.FilterChain
import lavalink.server.player.filters.TimescaleConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `lavalink.server.instantFilters` (FlaviBot fork): a filter change asks the decoder to seek
 * to the position the member hears, which drops the frame buffer's frames filtered the old
 * way. The player is the real [LavalinkPlayer] over lavaplayer; the track is a delegated one
 * whose inner decode loop records the seeks it receives (DelegatedTestTracks.kt).
 */
class InstantFiltersTest {
    private val playerManager = DefaultAudioPlayerManager()
    private val playerUpdates = Executors.newSingleThreadScheduledExecutor()
    private val source = TestDelegatedSourceManager()
    private val players = mutableListOf<LavalinkPlayer>()

    @AfterEach
    fun tearDown() {
        players.forEach { it.audioPlayer.destroy() }
        playerManager.shutdown()
        playerUpdates.shutdownNow()
    }

    @Test
    fun `a filter change re-seeks a seekable track to where it is, so the buffered frames are dropped`() {
        val player = player(instant = true)
        val track = playing(player, stream = false)
        val before = track.position

        player.filters = FilterChain(timescale = TimescaleConfig(speed = 1.3))

        val inner = track.inner!!
        assertTrue(inner.seeked.await(5, TimeUnit.SECONDS), "the decoder was not asked to seek")
        assertEquals(before, inner.seekedTo.get(), "the seek must land where the track already is: no jump")
    }

    @Test
    fun `nothing is re-seeked with the flag off`() {
        val player = player(instant = false)
        val track = playing(player, stream = false)

        player.filters = FilterChain(timescale = TimescaleConfig(speed = 1.3))

        assertFalse(track.inner!!.seeked.await(500, TimeUnit.MILLISECONDS), "a seek reached the decoder with instantFilters off")
    }

    @Test
    fun `a stream cannot be re-seeked and keeps the buffer's delay`() {
        val player = player(instant = true)
        val track = playing(player, stream = true)

        player.filters = FilterChain(timescale = TimescaleConfig(speed = 1.3))

        assertFalse(track.inner!!.seeked.await(500, TimeUnit.MILLISECONDS), "a seek reached a stream's decoder")
    }

    @Test
    fun `a filter change with nothing playing is just a filter change`() {
        val player = player(instant = true)
        player.filters = FilterChain(timescale = TimescaleConfig(speed = 1.3))
        assertTrue(player.filters.isEnabled)
    }

    private fun player(instant: Boolean): LavalinkPlayer {
        val socketContext = Mockito.mock(SocketContext::class.java)
        // No websocket client: player updates are skipped as for a paused session.
        Mockito.`when`(socketContext.sessionPaused).thenReturn(true)
        Mockito.`when`(socketContext.playerUpdateService).thenReturn(playerUpdates)
        val config = ServerConfig().apply { instantFilters = instant }
        return LavalinkPlayer(socketContext, 1L, config, playerManager, emptyList()).also { players += it }
    }

    /** A delegated track playing until its inner decode loop runs (so a seek can interrupt it). */
    private fun playing(player: LavalinkPlayer, stream: Boolean): TestDelegatedTrack {
        val info = AudioTrackInfo("Instant", "Test artist", 1_412_501, "instant-${if (stream) "stream" else "track"}", stream, "https://example.com/instant")
        val track = TestDelegatedTrack(info, source)
        player.audioPlayer.playTrack(track)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (track.inner == null && System.nanoTime() < deadline) Thread.sleep(5)
        val inner = checkNotNull(track.inner) { "the playback thread never built the inner track" }
        assertTrue(inner.started.await(5, TimeUnit.SECONDS), "the inner decode loop never started")
        return track
    }
}
