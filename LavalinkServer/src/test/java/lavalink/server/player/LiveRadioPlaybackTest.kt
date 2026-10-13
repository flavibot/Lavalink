package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStuckEvent
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import lavalink.server.player.FakeRadio.Mode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The whole chain of an HTTP radio, as the node plays one: a source's
 * HttpAudioTrack (a delegated track, like obsidian's) opens the stream, the
 * MP3 reader and decoder run on the playback thread, and the frames are pulled
 * as a voice connection pulls them. The radio cuts every connection after
 * ~6 s of audio (~4 s of which are its burst), mid-frame. With the resume, the track plays on through every
 * cut with no TrackEnd; without it, it ends at the first cut, which is what
 * the node did every ~2 minutes through its proxy.
 */
class LiveRadioPlaybackTest {
    private val manager = DefaultAudioPlayerManager().apply {
        registerSourceManager(HttpAudioSourceManager())
    }

    @BeforeEach
    fun fastCuts() {
        LiveStreamResume.reset()
        LiveStreamResume.setMinConnectionMs(0)
        LiveStreamResume.setBackoffMs(20, 40)
    }

    @AfterEach
    fun tearDown() {
        LiveStreamResume.reset()
        manager.shutdown()
    }

    @Test
    fun `a live MP3 radio cut every few seconds plays on without a track end`() {
        radio().use { radio ->
            val (player, ends) = play(radio.url)
            val frames = pull(player, ends, AUDIO_FRAMES)

            assertTrue(ends.isEmpty(), "the track ended: $ends")
            assertEquals(AUDIO_FRAMES, frames, "frames pulled")
            // 30 s of 128 kbps audio is ~480 000 bytes, ~38 000 new bytes a connection.
            assertTrue(radio.requests.size >= 8, "${radio.requests.size} requests")
            player.destroy()
        }
    }

    @Test
    fun `without the resume the same radio ends at its first cut`() {
        LiveStreamResume.setEnabled(false)

        radio().use { radio ->
            val (player, ends) = play(radio.url)
            val frames = pull(player, ends, AUDIO_FRAMES)

            assertTrue(ends.isNotEmpty(), "never ended, $frames frames")
            assertTrue(frames < AUDIO_FRAMES / 4, "ended after $frames frames")
            player.destroy()
        }
    }

    private fun radio() =
        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000, gap = 2_000, content = { mp3Byte(it) })

    private fun play(url: String): Pair<AudioPlayer, MutableList<AudioEvent>> {
        val track = manager.loadItemSync(url) as AudioTrack
        assertTrue(track.info.isStream, "a body without a Content-Length is a stream")

        val ends = CopyOnWriteArrayList<AudioEvent>()
        val player = manager.createPlayer()
        player.addListener { event ->
            if (event is TrackEndEvent || event is TrackExceptionEvent || event is TrackStuckEvent) {
                ends += event
            }
        }
        player.playTrack(track)
        return player to ends
    }

    /** Pulls frames as fast as they come, as many as [wanted], until the track ends. */
    private fun pull(player: AudioPlayer, ends: List<AudioEvent>, wanted: Int): Int {
        var frames = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)

        while (frames < wanted && ends.isEmpty() && System.nanoTime() < deadline) {
            try {
                if (player.provide(100, TimeUnit.MILLISECONDS) != null) frames++
            } catch (e: TimeoutException) {
                // Nothing yet.
            }
        }

        return frames
    }

    companion object {
        /** 30 s of audio, 20 ms a frame. */
        private const val AUDIO_FRAMES = 1500

        /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, joint stereo, no padding: 417 bytes. */
        private const val FRAME_SIZE = 417
        private val HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
        private const val SIDE_INFO_END = 4 + 32

        /**
         * A silent MP3 radio: zero side info (no main data, so silence) and the
         * frame's number in its ancillary bytes, so no two frames are alike.
         */
        fun mp3Byte(position: Long): Byte {
            val frame = position / FRAME_SIZE
            val offset = (position % FRAME_SIZE).toInt()

            return when {
                offset < 4 -> HEADER[offset]
                offset < SIDE_INFO_END -> 0
                offset < SIDE_INFO_END + 8 -> (frame ushr (8 * (SIDE_INFO_END + 7 - offset))).toByte()
                else -> 0
            }
        }
    }
}
