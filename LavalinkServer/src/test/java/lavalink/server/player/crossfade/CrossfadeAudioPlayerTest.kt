package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The two-deck player driven by scripted decks and a raw-PCM codec: frames are 20 ms of
 * 960 stereo samples, A's samples are +10000 and B's -10000 (sample 0 carries a frame tag).
 */
class CrossfadeAudioPlayerTest {

    private class Rig(@Volatile var ready: Boolean = true) {
        val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val decks = CopyOnWriteArrayList<FakeDeck>()
        val codecs = CopyOnWriteArrayList<PcmFrameCodec>()
        val events = CopyOnWriteArrayList<String>()
        val player = CrossfadeAudioPlayer(
            deckFactory = { FakeDeck("deck${decks.size}", format).also { decks.add(it) } },
            codecFactory = { PcmFrameCodec().also { codecs.add(it) } },
            outputFormat = format,
            isReady = { ready },
        )
        val target = MutableAudioFrame(ByteBuffer.allocate(format.maximumChunkSize()))

        init {
            player.addListener(AudioEventListener { events.add(FakeDeck.describe(it)) })
        }

        val deckA: FakeDeck get() = decks[0]
        val deckB: FakeDeck get() = decks[1]
        val mixed: Long get() = player.counters.mixedFrames.get()

        fun poll(): ByteArray? = if (player.provide(target)) target.data else null
        fun polls(count: Int): List<ByteArray?> = List(count) { poll() }
    }

    private fun track(id: String, frames: Int, value: Int, tagBase: Int = 0, durationMs: Long = frames * 20L, stream: Boolean = false) =
        FakeTrack(id, List(frames) { PcmFrameCodec.frame(tagBase + it, value) }, durationMs, stream)

    /** Sample 1 of the left channel (index 2): past the tag, so it carries the mix. */
    private fun sample(frame: ByteArray?): Int = PcmFrameCodec.readShort(frame!!, 2).toInt()

    private fun expectedMix(k: Int, n: Int): Int {
        val gB = (k * 960 + 1).toDouble() / (n * 960)
        return (10000 * (1 - gB) - 10000 * gB).roundToInt()
    }

    @Test
    fun `at rest every frame is the current deck's bytes and no codec is built`() {
        val rig = Rig()
        val a = track("A", 30, 10000)
        rig.player.playTrack(a)
        for (i in 0 until 30) assertArrayEquals(a.frames[i], rig.poll(), "frame $i")
        assertNull(rig.poll())
        assertEquals(0, rig.codecs.size)
        assertEquals(1, rig.decks.size, "the second deck is created on the first arm only")
        assertEquals(listOf("start:A", "end:A:FINISHED"), rig.events)
    }

    @Test
    fun `a 200 ms fade on a 1 s track gives 40 frames of A, exactly 10 mixed frames, then B unchanged`() {
        val rig = Rig()
        val a = track("A", 50, 10000)
        val b = track("B", 100, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 200)

        for (i in 0 until 39) assertArrayEquals(a.frames[i], rig.poll(), "poll ${i + 1} is A's frame $i")
        assertSame(a, rig.player.playingTrack, "A is current before the overlap starts")
        // Poll 40 sends A's frame 39 (200 ms left after it) unchanged and starts the overlap.
        assertArrayEquals(a.frames[39], rig.poll())
        assertSame(b, rig.player.playingTrack, "B is current from the overlap's start")
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)

        for (k in 0 until 10) {
            val out = rig.poll()
            val expected = expectedMix(k, 10)
            assertTrue(abs(sample(out) - expected) <= 1, "mixed frame $k: expected $expected, got ${sample(out)}")
            assertEquals(k * 20L, rig.player.playingTrack!!.position, "the reported position is B's")
        }
        assertEquals(10L, rig.mixed)
        assertEquals(1, rig.codecs.size, "one codec for the whole crossfade")
        assertEquals(4L, rig.player.counters.prerollFrames.get(), "A's frames 36..39 warmed the codec")
        assertEquals(14, rig.codecs[0].encodeCount, "4 pre-roll encodes + 10 mixed frames")

        for (i in 10 until 30) assertArrayEquals(b.frames[i], rig.poll(), "B's frame $i, unchanged")
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertEquals(1L, rig.player.counters.completed.get())
    }

    @Test
    fun `the client sees FINISHED then START once, never REPLACED, and the decks' own events are swallowed`() {
        val rig = Rig()
        val a = track("A", 50, 10000)
        val b = track("B", 100, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 200)
        rig.polls(60)

        assertEquals(listOf("start:A", "end:A:FINISHED", "start:B"), rig.events)
        // B's preload start and its pause/resume happened on the deck, not on the wrapper.
        assertEquals(listOf("pause", "start:B", "resume"), rig.deckB.dispatched)
        // The tail's real end is a silent STOPPED.
        assertEquals(listOf("start:A", "end:A:STOPPED"), rig.deckA.dispatched)
    }

    @Test
    fun `after the ramp the tail is stopped once, its end marker was cleared and the codec is closed`() {
        val rig = Rig()
        val a = track("A", 50, 10000)
        rig.player.playTrack(a)
        rig.player.arm(track("B", 100, -10000), 200)
        rig.polls(50)

        assertEquals(1, rig.deckA.calls.count { it == "stop" })
        assertNull(rig.deckA.playingTrack)
        assertEquals(listOf("null"), a.markers, "C3: setMarker(null) on A at the overlap start")
        assertTrue(rig.codecs.single().closed)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertFalse(rig.events.contains("end:A:STOPPED"))
    }

    @Test
    fun `disarm, stop and play while armed stop deck B silently`() {
        for (action in listOf("disarm", "stop", "play")) {
            val rig = Rig()
            val a = track("A", 50, 10000)
            rig.player.playTrack(a)
            rig.player.arm(track("B", 100, -10000), 200)
            rig.polls(5)
            when (action) {
                "disarm" -> assertTrue(rig.player.disarm())
                "stop" -> rig.player.stopTrack()
                else -> rig.player.playTrack(track("C", 10, 5))
            }
            assertNull(rig.deckB.playingTrack, action)
            assertTrue(rig.deckB.dispatched.contains("end:B:STOPPED"), action)
            assertEquals(CrossfadePhase.IDLE, rig.player.phase, action)
            val expected = when (action) {
                "disarm" -> listOf("start:A")
                "stop" -> listOf("start:A", "end:A:STOPPED")
                else -> listOf("start:A", "end:A:REPLACED", "start:C")
            }
            assertEquals(expected, rig.events, action)
            if (action == "disarm") {
                assertFalse(rig.player.disarm(), "nothing left to disarm")
                for (i in 5 until 50) assertArrayEquals(a.frames[i], rig.poll(), "A at full gain, frame $i")
                assertEquals(1L, rig.player.counters.disarmed.get())
            }
        }
    }

    @Test
    fun `an arm stops the reused deck before playing on it`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 100, -10000), 200)
        assertEquals(listOf("stop", "pause", "play:B"), rig.deckB.calls)
        rig.polls(60) // crossfade done: B is current, deck A is the spare

        val before = rig.deckA.calls.size
        rig.player.arm(track("C", 100, 3000), 200)
        val armCalls = rig.deckA.calls.subList(before, rig.deckA.calls.size)
        assertEquals("stop", armCalls.first(), "C4: stop (clears the shadow track) before play")
        assertEquals("play:C", armCalls.last())
        assertEquals(CrossfadePhase.ARMED, rig.player.phase)
    }

    @Test
    fun `a seek is refused during the overlap`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 100, -10000), 200)
        rig.polls(42)
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)
        assertThrows<CrossfadeConflictException> { rig.player.seek(100) }
        assertEquals(1L, rig.player.counters.seeksRefused.get())
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase, "the overlap goes on")
    }

    @Test
    fun `a seek while armed moves the trigger, and a seek near the end shortens the ramp`() {
        val rig = Rig()
        val a = track("A", 50, 10000)
        val b = track("B", 100, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 200)
        rig.polls(5)
        rig.player.seek(900) // 100 ms before the end
        assertEquals(CrossfadePhase.ARMED, rig.player.phase, "a seek does not disarm")

        // Frame 45 (t = 900) goes out unchanged; 80 ms of A remain after it: a 4-frame ramp.
        assertArrayEquals(a.frames[45], rig.poll())
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)
        assertEquals(4, rig.player.state().rampFrames)
        for (k in 0 until 4) {
            val out = rig.poll()
            assertTrue(abs(sample(out) - expectedMix(k, 4)) <= 1, "mixed frame $k")
        }
        assertEquals(4L, rig.mixed)
        assertArrayEquals(b.frames[4], rig.poll())
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
    }

    @Test
    fun `a next track that fails while armed disarms, and A plays to its end at full gain`() {
        for (failure in listOf("exception", "cleanup")) {
            val rig = Rig()
            val a = track("A", 50, 10000)
            rig.player.playTrack(a)
            rig.player.arm(track("B", 100, -10000), 200)
            rig.polls(5)
            if (failure == "exception") rig.deckB.fail("boom") else rig.deckB.stopWith(AudioTrackEndReason.CLEANUP)

            for (i in 5 until 50) assertArrayEquals(a.frames[i], rig.poll(), "$failure: A's frame $i")
            assertNull(rig.poll())
            assertEquals(listOf("start:A", "end:A:FINISHED"), rig.events, failure)
            assertEquals(1L, rig.player.counters.disarmed.get(), failure)
            assertEquals(0, rig.codecs.size, failure)
            assertEquals(CrossfadePhase.IDLE, rig.player.phase, failure)
        }
    }

    @Test
    fun `a tail that ends early gets a 10-frame catch-up ramp`() {
        val rig = Rig()
        // A claims 5 s but has 160 frames (3.2 s); a 2 s fade starts at t = 2980 with n = 100.
        val a = track("A", 160, 10000, durationMs = 5000)
        val b = track("B", 300, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 2000)
        rig.polls(150)
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)
        assertEquals(100, rig.player.state().rampFrames)

        val mixed = rig.polls(20)
        assertEquals(20L, rig.mixed, "10 frames with A, then 10 catch-up frames instead of 90")
        assertEquals(1L, rig.player.counters.tailEndedEarly.get())
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        // After A is gone, B's gain rises from 0.1 to 1 frame after frame.
        val values = mixed.drop(10).map { sample(it) }
        for (i in 1 until values.size) assertTrue(values[i] < values[i - 1], "B gets louder: $values")
        assertTrue(values.first() in -1100..-900, "catch-up starts at B's gain of the moment (0.1): $values")
        assertTrue(values.last() < -9000, "B is near full gain at the end: $values")
        assertArrayEquals(b.frames[20], rig.poll())
    }

    @Test
    fun `a next track that is not ready swaps at the end of A without mixing`() {
        val rig = Rig(ready = false)
        val a = track("A", 50, 10000)
        val b = track("B", 100, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 200)
        for (i in 0 until 50) assertArrayEquals(a.frames[i], rig.poll(), "A's frame $i")
        // Poll 51: A ends, B takes over in the same poll.
        assertArrayEquals(b.frames[0], rig.poll())
        assertArrayEquals(b.frames[1], rig.poll())
        assertEquals(listOf("start:A", "end:A:FINISHED", "start:B"), rig.events)
        assertEquals(0L, rig.mixed)
        assertEquals(1L, rig.player.counters.endSwaps.get())
        assertTrue(rig.codecs.all { it.closed }, "the pre-roll codec is closed at the swap")
        assertSame(b, rig.player.playingTrack)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
    }

    @Test
    fun `an A shorter than its duration swaps at its real end`() {
        val rig = Rig()
        val a = track("A", 30, 10000, durationMs = 1000)
        val b = track("B", 100, -10000, tagBase = 1000)
        rig.player.playTrack(a)
        rig.player.arm(b, 200)
        for (i in 0 until 30) assertArrayEquals(a.frames[i], rig.poll(), "A's frame $i")
        assertArrayEquals(b.frames[0], rig.poll())
        assertEquals(listOf("start:A", "end:A:FINISHED", "start:B"), rig.events)
        assertEquals(0, rig.codecs.size)
    }

    @Test
    fun `B dying during the overlap ends the crossfade and silences the tail`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 3, -10000), 200)
        rig.polls(43) // 40 of A, then 3 mixed frames with all of B
        assertEquals(3L, rig.mixed)
        assertNull(rig.poll(), "B is gone")
        assertEquals(listOf("start:A", "end:A:FINISHED", "start:B", "end:B:FINISHED"), rig.events)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertNull(rig.deckA.playingTrack)
        assertTrue(rig.deckA.dispatched.contains("end:A:STOPPED"))
        assertTrue(rig.codecs.single().closed)
        assertNull(rig.poll())
    }

    @Test
    fun `play during the overlap is an ordinary REPLACED and the tail is silenced`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 100, -10000), 200)
        rig.polls(42)
        val c = track("C", 10, 7)
        rig.player.playTrack(c)
        assertEquals(listOf("start:A", "end:A:FINISHED", "start:B", "end:B:REPLACED", "start:C"), rig.events)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertNull(rig.deckA.playingTrack)
        assertTrue(rig.codecs.single().closed)
        assertArrayEquals(c.frames[0], rig.poll())
        assertEquals(1L, rig.player.counters.cutShort.get())
    }

    @Test
    fun `a stop from inside a deck's provide is handled`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 100, -10000), 200)
        var provides = 0
        // What the end marker does: TrackEndMarkerHandler calls stop() from the poll thread.
        rig.deckA.beforeProvide = { if (++provides == 10) rig.player.stopTrack() }
        val out = rig.polls(12)
        assertNull(out[9])
        assertEquals(listOf("start:A", "end:A:STOPPED"), rig.events)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertNull(rig.deckB.playingTrack)
    }

    @Test
    fun `a paused player keeps polling its decks and freezes the ramp`() {
        val rig = Rig()
        rig.player.playTrack(track("A", 50, 10000))
        rig.player.arm(track("B", 100, -10000), 200)
        rig.polls(10)

        rig.player.isPaused = true
        val a0 = rig.deckA.provideCount
        val b0 = rig.deckB.provideCount
        repeat(5) { assertNull(rig.poll()) }
        assertEquals(a0 + 5, rig.deckA.provideCount, "deck A is still polled (no 60 s cleanup)")
        assertEquals(b0 + 5, rig.deckB.provideCount, "deck B is still polled (no 60 s cleanup)")
        rig.player.isPaused = false

        rig.polls(33) // A's frames 10..39 (overlap starts), then 3 mixed frames
        assertEquals(3L, rig.mixed)
        rig.player.isPaused = true
        assertTrue(rig.deckA.isPaused && rig.deckB.isPaused, "tail and current are paused")
        repeat(5) { assertNull(rig.poll()) }
        assertEquals(3, rig.player.state().rampFrame, "the ramp is frozen")
        rig.player.isPaused = false
        rig.polls(7)
        assertEquals(10L, rig.mixed)
        assertEquals(CrossfadePhase.IDLE, rig.player.phase)
        assertEquals(listOf("start:A", "pause", "resume", "end:A:FINISHED", "start:B", "pause", "resume"), rig.events)
    }

    @Test
    fun `arming is refused when nothing or a stream plays, when it is too late, and while armed or overlapping`() {
        val rig = Rig()
        assertThrows<CrossfadeConflictException> { rig.player.arm(track("B", 10, 1), 200) }
        rig.player.playTrack(track("S", 10, 1, durationMs = Long.MAX_VALUE, stream = true))
        assertThrows<CrossfadeConflictException> { rig.player.arm(track("B", 10, 1), 200) }
        rig.player.playTrack(track("A", 50, 10000))
        assertThrows<CrossfadeConflictException> { rig.player.arm(track("B", 10, 1), 1000) }
        rig.player.arm(track("B", 100, -10000), 200)
        assertThrows<CrossfadeConflictException> { rig.player.arm(track("B2", 10, 1), 200) }
        rig.polls(41)
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)
        assertThrows<CrossfadeConflictException> { rig.player.arm(track("B3", 10, 1), 200) }
        assertThrows<CrossfadeConflictException> { rig.player.disarm() }
        assertEquals(CrossfadePhase.OVERLAP, rig.player.phase)
    }

    @Test
    fun `concurrent polls and REST calls neither deadlock nor throw (a smoke test, not a proof)`() {
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val rig = Rig()
            val errors = CopyOnWriteArrayList<Throwable>()
            val stop = AtomicBoolean()
            val poller = thread(name = "poll", isDaemon = true) {
                while (!stop.get()) {
                    try {
                        rig.player.provide(rig.target)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                    LockSupport.parkNanos(20_000)
                }
            }
            val rest = thread(name = "rest", isDaemon = true) {
                var i = 0
                while (!stop.get()) {
                    try {
                        when (i % 6) {
                            0 -> rig.player.playTrack(track("A$i", 15, 10000))
                            1, 4 -> rig.player.arm(track("B$i", 15, -10000), 200)
                            2 -> rig.player.seek(100)
                            3 -> rig.player.isPaused = !rig.player.isPaused
                            else -> if (i % 12 == 5) rig.player.stopTrack() else rig.player.disarm()
                        }
                    } catch (e: CrossfadeConflictException) {
                        // expected: too late, already armed, overlap running
                    } catch (e: RuntimeException) {
                        if (e.message?.startsWith("Can't seek") != true) errors.add(e)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                    i++
                    // Mostly tight, sometimes long enough for an overlap to run to its end.
                    LockSupport.parkNanos(if (i % 7 == 0) 3_000_000 else 50_000)
                }
            }
            Thread.sleep(2000)
            stop.set(true)
            poller.join()
            rest.join()
            rig.player.destroy()
            assertEquals(emptyList<Throwable>(), errors)
            assertEquals(0, rig.codecs.sumOf { it.usedAfterClose }, "no codec used after close")
            val c = rig.player.counters
            println(
                "smoke: armed=${c.armed} overlaps=${c.overlaps} completed=${c.completed} endSwaps=${c.endSwaps} " +
                    "cutShort=${c.cutShort} disarmed=${c.disarmed} mixed=${c.mixedFrames}"
            )
        }
    }
}
