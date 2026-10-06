package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.PlayerPauseEvent
import com.sedmelluq.discord.lavaplayer.player.event.PlayerResumeEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStuckEvent
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A crossfade request or a seek that the current state does not allow (HTTP 409). */
class CrossfadeConflictException(reason: String) : ResponseStatusException(HttpStatus.CONFLICT, reason)

enum class CrossfadePhase { IDLE, ARMING, ARMED, OVERLAP }

/** Whether the track's executor has buffered at least one frame (the readiness probe for deck B). */
fun executorHasFrames(track: AudioTrack): Boolean =
    ((track as? InternalAudioTrack)?.activeExecutor as? LocalAudioTrackExecutor)?.audioBuffer?.hasReceivedFrames() == true

/**
 * Crossfade proof of concept: an [AudioPlayer] made of two real players ("decks").
 *
 * At rest it forwards everything to the current deck, so the frames are the same bytes and
 * Opus passthrough is kept. A client arms it with the next track and a fade length; the next
 * deck preloads that track paused. When the current track's remaining time reaches the fade
 * length, the node itself starts the overlap: it emits `TrackEnd(A, FINISHED)` then
 * `TrackStart(B)`, and for the next n frames it decodes both decks, mixes them with a linear
 * ramp and re-encodes. When the ramp is done the old deck is stopped silently and the player
 * is a plain forward again, now to the other deck. If no overlap could start, it swaps to B
 * at A's end without mixing (gapless).
 *
 * Only the events of the current deck reach this player's listeners, re-created with this
 * player as their source. The next deck's preload start, the tail's events and the decks'
 * pause events are swallowed.
 *
 * Locks (never held across a deck call unless stated):
 * - [stateLock] guards roles, phase, [gen] and the arm parameters; it is a leaf.
 * - [codecLock] guards decode, mix, encode and close; it is a leaf.
 * - [listenerLock] serialises dispatch to this player's listeners. A forwarded deck event takes
 *   it inside the deck's own lock, as lavaplayer's dispatch does; so no deck method is ever
 *   called while it is held. Role changes made by the poll thread happen inside it (taking
 *   [stateLock] inside), so "is this deck current" is answered consistently with emission.
 *
 * The poll thread decides on a snapshot and commits a transition only if [gen] did not move;
 * every REST-side change bumps [gen].
 */
class CrossfadeAudioPlayer(
    private val deckFactory: () -> AudioPlayer,
    private val codecFactory: () -> FrameCodec,
    private val outputFormat: AudioDataFormat,
    private val isReady: (AudioTrack) -> Boolean = ::executorHasFrames,
) : AudioPlayer {

    companion object {
        private val log = LoggerFactory.getLogger(CrossfadeAudioPlayer::class.java)

        /** Start warming deck A's decoder (and the encoder) this long before the overlap. */
        const val PREROLL_MS = 60L

        /** Once the tail has ended early, B reaches full gain within this many frames. */
        const val TAIL_CATCHUP_FRAMES = 10
    }

    class Counters {
        val armed = AtomicLong()
        val overlaps = AtomicLong()
        val completed = AtomicLong()
        val endSwaps = AtomicLong()
        val tailEndedEarly = AtomicLong()
        val cutShort = AtomicLong()
        val disarmed = AtomicLong()
        val seeksRefused = AtomicLong()
        val codecOpens = AtomicLong()
        val mixedFrames = AtomicLong()
        val prerollFrames = AtomicLong()
    }

    /** What GET /crossfade shows. */
    class State(
        val phase: CrossfadePhase,
        val fadeMs: Long,
        val current: AudioTrack?,
        val next: AudioTrack?,
        val nextReady: Boolean,
        val nextFailed: Boolean,
        val tail: AudioTrack?,
        val rampFrame: Int,
        val rampFrames: Int,
        val gainB: Double,
        val counters: Counters,
        val mixLastMicros: Long,
        val mixAvgMicros: Long,
        val mixMaxMicros: Long,
    )

    private inner class Deck(val player: AudioPlayer) : AudioEventListener {
        /** The last end event seen while this deck was current (set on the dispatching thread). */
        @Volatile var endedTrack: AudioTrack? = null
        @Volatile var endReason: AudioTrackEndReason? = null

        override fun onEvent(event: AudioEvent) = onDeckEvent(this, event)
    }

    private class Snap(
        val gen: Long,
        val current: Deck,
        val other: Deck,
        val outgoing: AudioTrack?,
        val incoming: AudioTrack?,
        val fadeMs: Long,
    )

    private val stateLock = Any()
    private val codecLock = Any()
    private val listenerLock = Any()
    private val deckLock = Any()
    private val listeners = CopyOnWriteArrayList<AudioEventListener>()

    private val frameMs = outputFormat.frameDuration()
    private val chunkBytes = outputFormat.maximumChunkSize()
    private val samplesPerChannel = outputFormat.chunkSampleCount
    private val channels = outputFormat.channelCount
    private val totalSamples = outputFormat.totalSampleCount()

    // Poll-thread scratch space: frames sized for the output format, direct PCM buffers.
    private val scratchNext = scratchFrame()
    private val frameA = scratchFrame()
    private val frameB = scratchFrame()
    private val bytes = ByteArray(chunkBytes)
    private val encoded = ByteArray(chunkBytes)
    private val pcmA = directShorts(totalSamples)
    private val pcmB = directShorts(totalSamples)
    private val pcmOut = directShorts(totalSamples)

    private val deck0: Deck = newDeck()
    @Volatile private var deck1: Deck? = null

    // Roles and phase: written under stateLock (role changes by the poll thread also hold listenerLock).
    @Volatile private var current: Deck = deck0
    @Volatile private var next: Deck? = null
    @Volatile private var tail: Deck? = null
    @Volatile var phase: CrossfadePhase = CrossfadePhase.IDLE
        private set
    @Volatile private var gen = 0L
    @Volatile private var fadeMs = 0L
    @Volatile private var nextTrack: AudioTrack? = null
    @Volatile private var armedOn: AudioTrack? = null
    @Volatile private var tailTrack: AudioTrack? = null
    private val nextFailed = AtomicBoolean()
    private val tailGone = AtomicBoolean()

    // Ramp: frame k of n; B's gain rises linearly from rampBase at rampBaseFrame to 1 at n.
    @Volatile private var rampFrame = 0
    @Volatile private var rampFrames = 0
    @Volatile private var rampBase = 0.0
    @Volatile private var rampBaseFrame = 0
    private var catchingUp = false

    // Player options, applied to every deck.
    @Volatile private var volumeLevel = 100
    @Volatile private var filters: PcmFilterFactory? = null
    @Volatile private var bufferDuration: Int? = null
    private val pausedFlag = AtomicBoolean()

    // Codec (codecLock) and the cost of decode + mix + encode, measured on the poll thread.
    private var codec: FrameCodec? = null
    @Volatile private var mixLastNanos = 0L
    @Volatile private var mixMaxNanos = 0L
    @Volatile private var mixTotalNanos = 0L
    @Volatile private var mixCount = 0L

    val counters = Counters()

    private fun scratchFrame() = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(chunkBytes)) }

    private fun directShorts(count: Int): ShortBuffer =
        ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    private fun newDeck(): Deck {
        val deck = Deck(deckFactory())
        deck.player.addListener(deck)
        return deck
    }

    private fun ensureSecondDeck() {
        if (deck1 != null) return
        synchronized(deckLock) {
            if (deck1 == null) {
                val deck = newDeck()
                deck.player.volume = volumeLevel
                deck.player.setFilterFactory(filters)
                deck.player.setFrameBufferDuration(bufferDuration)
                deck1 = deck
            }
        }
    }

    private inline fun forEachDeck(action: (Deck) -> Unit) {
        action(deck0)
        deck1?.let(action)
    }

    // ---------------------------------------------------------------- events

    private fun onDeckEvent(deck: Deck, event: AudioEvent) {
        when (event) {
            // The wrapper emits its own pause and resume events.
            is PlayerPauseEvent, is PlayerResumeEvent -> return
            is TrackEndEvent -> {
                markGone(deck, event.track)
                if (deck === current) {
                    deck.endReason = event.endReason
                    deck.endedTrack = event.track
                }
            }
            is TrackExceptionEvent -> markGone(deck, event.track)
            else -> {}
        }

        synchronized(listenerLock) {
            if (deck !== current) return
            emitLocked(rewrap(event))
        }
    }

    private fun markGone(deck: Deck, track: AudioTrack?) {
        if (deck === next && track === nextTrack) nextFailed.set(true)
        if (deck === tail && track === tailTrack) tailGone.set(true)
    }

    private fun rewrap(event: AudioEvent): AudioEvent = when (event) {
        is TrackStartEvent -> TrackStartEvent(this, event.track)
        is TrackEndEvent -> TrackEndEvent(this, event.track, event.endReason)
        is TrackExceptionEvent -> TrackExceptionEvent(this, event.track, event.exception)
        is TrackStuckEvent -> TrackStuckEvent(this, event.track, event.thresholdMs, event.stackTrace)
        else -> event
    }

    private fun emit(event: AudioEvent) = synchronized(listenerLock) { emitLocked(event) }

    private fun emitLocked(event: AudioEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                log.error("Handler of event {} threw an exception.", event, e)
            }
        }
    }

    // ---------------------------------------------------------------- crossfade API

    /**
     * Arms the player: [track] preloads paused on the other deck and the overlap starts when
     * the current track has [fadeMs] left. Throws [CrossfadeConflictException] when nothing
     * (or a stream) is playing, when it is too late, or when a crossfade is already armed or
     * running. The caller checks the output format and the fade bounds.
     */
    fun arm(track: AudioTrack, fadeMs: Long) {
        val outgoing = current.player.playingTrack ?: throw CrossfadeConflictException("Nothing is playing")
        if (outgoing.info.isStream || outgoing.duration == Long.MAX_VALUE) {
            throw CrossfadeConflictException("The current track is a stream")
        }
        val remaining = outgoing.duration - outgoing.position
        if (remaining <= fadeMs) {
            throw CrossfadeConflictException("Too late: $remaining ms left for a $fadeMs ms fade")
        }

        ensureSecondDeck()
        val spare: Deck
        val armGen: Long
        synchronized(stateLock) {
            if (phase != CrossfadePhase.IDLE) {
                throw CrossfadeConflictException("A crossfade is already ${phase.name.lowercase()}")
            }
            spare = if (current === deck0) deck1!! else deck0
            phase = CrossfadePhase.ARMING
            gen++
            armGen = gen
            next = spare
            nextTrack = track
            armedOn = outgoing
            this.fadeMs = fadeMs
            nextFailed.set(false)
        }

        try {
            val deck = spare.player
            deck.stopTrack() // C4: no stale track and no shadow track on a reused deck
            deck.isPaused = true
            deck.volume = volumeLevel
            deck.setFilterFactory(filters)
            deck.setFrameBufferDuration(bufferDuration)
            deck.playTrack(track) // B starts loading; its TrackStart is swallowed (not current)
        } catch (e: Exception) {
            cancel(allowOverlap = false)
            throw e
        }

        var armed = false
        synchronized(stateLock) {
            if (gen == armGen && phase == CrossfadePhase.ARMING) {
                phase = CrossfadePhase.ARMED
                armed = true
            }
        }
        if (!armed) {
            stopQuietly(spare)
            throw CrossfadeConflictException("The crossfade was cancelled while arming")
        }
        counters.armed.incrementAndGet()
        log.info("Crossfade armed: {} -> {} over {} ms", outgoing.identifier, track.identifier, fadeMs)
    }

    /** Disarms. Returns false when nothing was armed; refuses (409) once the overlap has started. */
    fun disarm(): Boolean = cancel(allowOverlap = false)

    /** The seek guard (C2): refused during an overlap; while armed it only moves the trigger. */
    fun seek(position: Long) {
        val track = current.player.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        synchronized(stateLock) {
            if (phase == CrossfadePhase.OVERLAP) {
                counters.seeksRefused.incrementAndGet()
                throw CrossfadeConflictException("Cannot seek during a crossfade overlap")
            }
            if (phase == CrossfadePhase.ARMED) gen++
        }
        track.position = position
    }

    fun state(): State {
        val ph = phase
        val n = rampFrames
        val k = rampFrame
        val incoming = nextTrack
        val count = mixCount
        return State(
            phase = ph,
            fadeMs = if (ph == CrossfadePhase.IDLE) 0L else fadeMs,
            current = current.player.playingTrack,
            next = incoming,
            nextReady = incoming != null && readySafe(incoming),
            nextFailed = nextFailed.get(),
            tail = tailTrack,
            rampFrame = if (ph == CrossfadePhase.OVERLAP) k else 0,
            rampFrames = if (ph == CrossfadePhase.OVERLAP) n else 0,
            gainB = when (ph) {
                CrossfadePhase.OVERLAP -> gainB(k, n, rampBase, rampBaseFrame)
                CrossfadePhase.IDLE -> 1.0
                else -> 0.0
            },
            counters = counters,
            mixLastMicros = mixLastNanos / 1000,
            mixAvgMicros = if (count == 0L) 0L else mixTotalNanos / count / 1000,
            mixMaxMicros = mixMaxNanos / 1000,
        )
    }

    private fun readySafe(track: AudioTrack): Boolean = try {
        isReady(track)
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- frames (Koe poll thread)

    override fun provide(targetFrame: MutableAudioFrame): Boolean = when (phase) {
        CrossfadePhase.ARMED -> provideArmed(targetFrame)
        CrossfadePhase.OVERLAP -> provideOverlap(targetFrame)
        else -> current.player.provide(targetFrame)
    }

    override fun provide(targetFrame: MutableAudioFrame, timeout: Long, unit: TimeUnit): Boolean = provide(targetFrame)

    override fun provide(): AudioFrame? = provide(0, TimeUnit.MILLISECONDS)

    override fun provide(timeout: Long, unit: TimeUnit): AudioFrame? {
        val frame = scratchFrame()
        return if (provide(frame)) frame.freeze() else null
    }

    private fun snap(expected: CrossfadePhase): Snap? = synchronized(stateLock) {
        if (phase != expected) return null
        val other = (if (expected == CrossfadePhase.ARMED) next else tail) ?: return null
        val outgoing = if (expected == CrossfadePhase.ARMED) armedOn else tailTrack
        Snap(gen, current, other, outgoing, nextTrack, fadeMs)
    }

    private fun provideArmed(target: MutableAudioFrame): Boolean {
        val s = snap(CrossfadePhase.ARMED) ?: return current.player.provide(target)
        // Deck B is paused: this returns false, consumes nothing and keeps its cleanup clock fresh.
        s.other.player.provide(scratchNext)
        val ok = s.current.player.provide(target)
        // A REST call or a re-entrant stop (end marker) changed things meanwhile: plain forward.
        if (gen != s.gen) return ok

        if (nextFailed.get()) {
            log.warn("Crossfade: the next track {} failed while armed, disarming", s.incoming?.identifier)
            disarmFrom(s.gen)
            return ok
        }

        val playing = s.current.player.playingTrack
        if (!ok) {
            return if (playing == null) swapAtEnd(s, target) else false
        }

        val outgoing = s.outgoing
        if (outgoing == null || playing !== outgoing) {
            disarmFrom(s.gen)
            return true
        }

        val remaining = outgoing.duration - (target.timecode + frameMs)
        if (remaining <= s.fadeMs + PREROLL_MS) preroll(s.gen, target)
        if (remaining <= s.fadeMs && readySafe(s.incoming!!)) startOverlap(s, remaining)
        return true
    }

    /** Feeds A's passthrough frame to decoder A and the encoder, so both are warm when the mix starts. */
    private fun preroll(gen0: Long, frame: MutableAudioFrame) {
        val length = frame.dataLength
        frame.getData(bytes, 0)
        synchronized(codecLock) {
            val c = openCodecLocked(gen0) ?: return
            try {
                c.decodeA(bytes, length, pcmA)
                c.encode(pcmA, encoded)
                counters.prerollFrames.incrementAndGet()
            } catch (e: Exception) {
                log.debug("Crossfade pre-roll failed", e)
            }
        }
    }

    private fun startOverlap(s: Snap, remaining: Long) {
        val outgoing = s.outgoing!!
        val incoming = s.incoming!!
        val n = maxOf(1L, remaining / frameMs).toInt()
        var committed = false
        synchronized(listenerLock) {
            synchronized(stateLock) {
                if (gen == s.gen && phase == CrossfadePhase.ARMED) {
                    tail = s.current
                    tailTrack = outgoing
                    tailGone.set(false)
                    current = s.other
                    next = null
                    nextTrack = null
                    armedOn = null
                    rampFrame = 0
                    rampFrames = n
                    rampBase = 0.0
                    rampBaseFrame = 0
                    catchingUp = false
                    phase = CrossfadePhase.OVERLAP
                    gen++
                    committed = true
                }
            }
            if (committed) {
                emitLocked(TrackEndEvent(this, outgoing, AudioTrackEndReason.FINISHED))
                emitLocked(TrackStartEvent(this, incoming))
            }
        }
        if (!committed) return

        counters.overlaps.incrementAndGet()
        // C3: the tail's end marker must never call LavalinkPlayer.stop(), which would stop B.
        outgoing.setMarker(null)
        resume(s.other)
        log.info("Crossfade overlap: {} -> {} over {} frames", outgoing.identifier, incoming.identifier, n)
    }

    /** A ended while armed with no overlap started: swap to B at once (gapless, no mix). */
    private fun swapAtEnd(s: Snap, target: MutableAudioFrame): Boolean {
        val finished = s.current.endedTrack === s.outgoing && s.current.endReason == AudioTrackEndReason.FINISHED
        if (!finished) {
            disarmFrom(s.gen)
            return false
        }

        val incoming = s.incoming!!
        var committed = false
        synchronized(listenerLock) {
            synchronized(stateLock) {
                if (gen == s.gen && phase == CrossfadePhase.ARMED) {
                    current = s.other
                    next = null
                    nextTrack = null
                    armedOn = null
                    phase = CrossfadePhase.IDLE
                    gen++
                    committed = true
                }
            }
            if (committed) emitLocked(TrackStartEvent(this, incoming))
        }
        if (!committed) return false

        counters.endSwaps.incrementAndGet()
        log.info("Crossfade: {} ended before any overlap, swapped to {} without mixing", s.outgoing?.identifier, incoming.identifier)
        stopQuietly(s.current) // nothing plays there any more; this clears its shadow track (C4)
        closeCodec()
        resume(s.other)
        return s.other.player.provide(target)
    }

    private fun provideOverlap(target: MutableAudioFrame): Boolean {
        val s = snap(CrossfadePhase.OVERLAP) ?: return current.player.provide(target)
        val b = s.current
        val a = s.other

        var okA = false
        if (!tailGone.get()) {
            okA = try {
                a.player.provide(frameA)
            } catch (e: Exception) {
                log.warn("Crossfade: the outgoing deck threw, dropping it", e)
                tailGone.set(true)
                false
            }
            if (!okA && a.player.playingTrack == null) tailGone.set(true)
        }
        val okB = b.player.provide(frameB)

        if (gen != s.gen) return passthrough(okB, target)

        if (!okB && b.player.playingTrack == null) {
            // B ended or failed during the overlap; its end was already forwarded.
            endOverlap(s.gen, completed = false)
            return false
        }

        // Paused: both decks are paused too, the ramp is frozen.
        if (pausedFlag.get()) return false

        val k = rampFrame
        var n = rampFrames
        if (tailGone.get() && !catchingUp) {
            catchingUp = true
            counters.tailEndedEarly.incrementAndGet()
            if (n - k > TAIL_CATCHUP_FRAMES) {
                rampBase = gainB(k, n, rampBase, rampBaseFrame)
                rampBaseFrame = k
                n = k + TAIL_CATCHUP_FRAMES
                rampFrames = n
            }
        }
        val from = gainB(k, n, rampBase, rampBaseFrame)
        val to = gainB(k + 1, n, rampBase, rampBaseFrame)

        val started = System.nanoTime()
        var mixed = false
        synchronized(codecLock) {
            val c = openCodecLocked(s.gen)
            if (c != null) {
                try {
                    if (okA) {
                        frameA.getData(bytes, 0)
                        c.decodeA(bytes, frameA.dataLength, pcmA)
                    } else {
                        zero(pcmA)
                    }
                    if (okB) {
                        frameB.getData(bytes, 0)
                        c.decodeB(bytes, frameB.dataLength, pcmB)
                    } else {
                        zero(pcmB)
                    }
                    FrameMixer.mixRamp(pcmA, pcmB, pcmOut, from, to, samplesPerChannel, channels)
                    val length = c.encode(pcmOut, encoded)
                    target.store(encoded, 0, length)
                    mixed = true
                } catch (e: Exception) {
                    log.warn("Crossfade: mixing failed, dropping the overlap", e)
                }
            }
        }
        if (!mixed) {
            endOverlap(s.gen, completed = false)
            return passthrough(okB, target)
        }
        recordMix(System.nanoTime() - started)
        counters.mixedFrames.incrementAndGet()

        target.timecode = if (okB) frameB.timecode else frameA.timecode
        target.format = outputFormat
        target.volume = 100
        target.isTerminator = false

        val k1 = k + 1
        if (k1 >= n) endOverlap(s.gen, completed = true) else rampFrame = k1
        return true
    }

    private fun gainB(k: Int, n: Int, base: Double, k0: Int): Double {
        if (n <= k0) return 1.0
        return (base + (1.0 - base) * (k - k0).toDouble() / (n - k0)).coerceIn(0.0, 1.0)
    }

    private fun zero(buffer: ShortBuffer) {
        for (i in 0 until totalSamples) buffer.put(i, 0)
    }

    private fun passthrough(ok: Boolean, target: MutableAudioFrame): Boolean {
        if (!ok) return false
        val length = frameB.dataLength
        frameB.getData(bytes, 0)
        target.store(bytes, 0, length)
        target.timecode = frameB.timecode
        target.format = frameB.format
        target.volume = frameB.volume
        target.isTerminator = false
        return true
    }

    private fun recordMix(nanos: Long) {
        mixLastNanos = nanos
        mixTotalNanos += nanos
        mixCount++
        if (nanos > mixMaxNanos) mixMaxNanos = nanos
    }

    private fun openCodecLocked(gen0: Long): FrameCodec? {
        codec?.let { return it }
        if (gen != gen0) return null
        return try {
            codecFactory().also {
                codec = it
                counters.codecOpens.incrementAndGet()
            }
        } catch (e: Throwable) {
            log.warn("Crossfade: could not create the codec", e)
            null
        }
    }

    private fun closeCodec() {
        synchronized(codecLock) {
            val c = codec ?: return
            codec = null
            try {
                c.close()
            } catch (e: Exception) {
                log.warn("Crossfade: closing the codec failed", e)
            }
        }
    }

    // ---------------------------------------------------------------- transitions

    private fun endOverlap(gen0: Long, completed: Boolean) {
        var gone: Deck? = null
        synchronized(stateLock) {
            if (gen != gen0 || phase != CrossfadePhase.OVERLAP) return
            gone = tail
            tail = null
            tailTrack = null
            phase = CrossfadePhase.IDLE
            gen++
        }
        if (completed) counters.completed.incrementAndGet() else counters.cutShort.incrementAndGet()
        gone?.let { stopQuietly(it) } // swallowed: the deck is not current; also clears its shadow track
        closeCodec()
    }

    private fun disarmFrom(gen0: Long) {
        var spare: Deck? = null
        synchronized(stateLock) {
            if (gen != gen0 || phase != CrossfadePhase.ARMED) return
            spare = next
            next = null
            nextTrack = null
            armedOn = null
            phase = CrossfadePhase.IDLE
            gen++
        }
        counters.disarmed.incrementAndGet()
        spare?.let { stopQuietly(it) }
        closeCodec()
    }

    /** Drops any arm or overlap; the other deck is stopped silently. Returns whether there was one. */
    private fun cancel(allowOverlap: Boolean): Boolean {
        val toStop = ArrayList<Deck>(2)
        var was = CrossfadePhase.IDLE
        synchronized(stateLock) {
            was = phase
            if (was == CrossfadePhase.IDLE) return false
            if (was == CrossfadePhase.OVERLAP && !allowOverlap) {
                throw CrossfadeConflictException("The overlap has started and cannot be disarmed")
            }
            next?.let { toStop.add(it) }
            tail?.let { toStop.add(it) }
            next = null
            tail = null
            nextTrack = null
            tailTrack = null
            armedOn = null
            phase = CrossfadePhase.IDLE
            gen++
        }
        if (was == CrossfadePhase.OVERLAP) counters.cutShort.incrementAndGet() else counters.disarmed.incrementAndGet()
        toStop.forEach { stopQuietly(it) }
        closeCodec()
        return true
    }

    private fun stopQuietly(deck: Deck) {
        try {
            deck.player.stopTrack()
        } catch (e: Exception) {
            log.warn("Crossfade: stopping a deck failed", e)
        }
    }

    private fun resume(deck: Deck) {
        val paused = pausedFlag.get()
        deck.player.isPaused = paused
        // A pause may have landed on this deck between the read and the write.
        if (pausedFlag.get() != paused) deck.player.isPaused = pausedFlag.get()
    }

    // ---------------------------------------------------------------- AudioPlayer

    override fun getPlayingTrack(): AudioTrack? = current.player.playingTrack

    override fun playTrack(track: AudioTrack?) {
        startTrack(track, false)
    }

    override fun startTrack(track: AudioTrack?, noInterrupt: Boolean): Boolean {
        if (noInterrupt && current.player.playingTrack != null) return false
        cancel(allowOverlap = true)
        // An ordinary replace: the deck's own TrackEnd(REPLACED) and TrackStart are forwarded.
        return current.player.startTrack(track, noInterrupt)
    }

    override fun stopTrack() {
        cancel(allowOverlap = true)
        current.player.stopTrack()
    }

    override fun getVolume(): Int = volumeLevel

    override fun setVolume(volume: Int) {
        volumeLevel = volume.coerceIn(0, 1000)
        forEachDeck { it.player.volume = volumeLevel }
    }

    override fun setFilterFactory(factory: PcmFilterFactory?) {
        filters = factory
        forEachDeck { it.player.setFilterFactory(factory) }
    }

    override fun setFrameBufferDuration(duration: Int?) {
        bufferDuration = duration
        forEachDeck { it.player.setFrameBufferDuration(duration) }
    }

    override fun isPaused(): Boolean = pausedFlag.get()

    override fun setPaused(value: Boolean) {
        if (!pausedFlag.compareAndSet(!value, value)) return
        current.player.isPaused = value
        tail?.player?.isPaused = value
        emit(if (value) PlayerPauseEvent(this) else PlayerResumeEvent(this))
    }

    override fun destroy() {
        cancel(allowOverlap = true)
        current.player.destroy()
        val other = if (current === deck0) deck1 else deck0
        other?.player?.destroy()
        closeCodec()
    }

    override fun addListener(listener: AudioEventListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioEventListener) {
        listeners.removeIf { it === listener }
    }

    override fun checkCleanup(threshold: Long) {
        forEachDeck { it.player.checkCleanup(threshold) }
    }
}
