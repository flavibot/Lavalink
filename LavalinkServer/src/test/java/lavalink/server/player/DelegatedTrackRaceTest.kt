package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import lavalink.server.util.toTrack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * lavaplayer's `DelegatedAudioTrack.processDelegate` (2.2.7, unchanged on its
 * main branch) is `synchronized` around the whole `delegate.process()`: the
 * playback thread holds the track's monitor for the entire playback, while
 * `getPosition()` / `getDuration()` fall back to that monitor whenever they read
 * a null `delegate`. A thread that read null while the track was starting and
 * reached the monitor just after the playback thread took it stayed parked
 * until the playback ended or was stopped. The PATCH that starts a track reads
 * its position microseconds after the hand-off (`LavalinkPlayer.play` →
 * `sendPlayerUpdate`, then `toPlayer`), as does the player-update task that
 * `onTrackStart` schedules at once: on dev a play PATCH hung until the next
 * request for that player stopped the track. The fork's copy of the class
 * (src/main/java/com/sedmelluq/...) holds the monitor for the hand-over only.
 */
class DelegatedTrackRaceTest {
    private val manager = DefaultAudioPlayerManager()
    private val source = TestDelegatedSourceManager()

    @AfterEach
    fun shutdown() = manager.shutdown()

    @Test
    fun `reading a delegated track while it starts never parks the reader until the track is stopped`() {
        val player = manager.createPlayer()
        val parked = mutableListOf<String>()

        repeat(ITERATIONS) { i ->
            val track = TestDelegatedTrack(TestDelegatedSourceManager.info("race-$i"), source)
            val done = AtomicBoolean(false)
            // What a PATCH's answer and a GET compute from the playing track
            // (util.kt `toTrack` reads getPosition and getDuration).
            val reader = thread(name = "reader-$i") {
                while (!done.get()) {
                    track.toTrack(manager, emptyList())
                    track.position
                }
            }

            player.playTrack(track)
            // The playback thread is inside the container track's decode loop.
            assertTrue(awaitInner(track), "playback never reached the container track")
            done.set(true)
            reader.join(PARK_MS)

            if (reader.isAlive) {
                // The campaign's signature: the next request for the player
                // (a stop) interrupts the playback and frees the reader.
                val stoppedAt = System.nanoTime()
                player.stopTrack()
                reader.join(5_000)
                assertFalse(reader.isAlive, "a stop did not release the reader")
                parked += "iteration $i: reader parked ${PARK_MS} ms+, released " +
                    "${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stoppedAt)} ms after the stop"
            } else {
                player.stopTrack()
            }
        }

        assertEquals(emptyList<String>(), parked, "${parked.size} of $ITERATIONS readers parked on the starting track")
    }

    @Test
    fun `a seek on a delegated track that is playing still reaches its decode loop`() {
        val player = manager.createPlayer()
        val track = TestDelegatedTrack(TestDelegatedSourceManager.info("seek"), source)

        player.playTrack(track)
        assertTrue(awaitInner(track), "playback never reached the container track")

        track.position = 30_000
        val inner = track.inner!!
        assertTrue(inner.seeked.await(5, TimeUnit.SECONDS), "the seek never reached the container track")
        assertEquals(30_000, inner.seekedTo.get())
        player.stopTrack()
    }

    private fun awaitInner(track: TestDelegatedTrack): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (track.inner == null) {
            if (System.nanoTime() > deadline) return false
            Thread.onSpinWait()
        }
        return track.inner!!.started.await(5, TimeUnit.SECONDS)
    }

    companion object {
        private const val ITERATIONS = 100
        private const val PARK_MS = 1_000L
    }
}
