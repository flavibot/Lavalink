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
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A deck that plays [FakeTrack]s the way lavaplayer's DefaultAudioPlayer does, minus the
 * threads: events are dispatched under the deck's lock (its trackSwitchLock), a replaced track
 * ends REPLACED, stopTrack ends STOPPED (nothing when empty), the provide after the last frame
 * returns false, clears the track and ends it FINISHED, and a paused deck provides nothing.
 */
class FakeDeck(val name: String, private val format: AudioDataFormat) : AudioPlayer {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<AudioEventListener>()
    @Volatile private var active: FakeTrack? = null
    @Volatile private var paused = false
    @Volatile private var vol = 100
    @Volatile var filterFactory: PcmFilterFactory? = null
        private set
    @Volatile var frameBufferDuration: Int? = null
        private set

    /** play:<id>, stop, pause, resume, destroy — in call order. */
    val calls = CopyOnWriteArrayList<String>()

    /** Every event this deck dispatched, as "start:A", "end:A:STOPPED", ... */
    val dispatched = CopyOnWriteArrayList<String>()

    @Volatile var provideCount = 0
    @Volatile var beforeProvide: (() -> Unit)? = null

    override fun getPlayingTrack(): AudioTrack? = active

    override fun playTrack(track: AudioTrack?) {
        startTrack(track, false)
    }

    override fun startTrack(track: AudioTrack?, noInterrupt: Boolean): Boolean {
        calls.add("play:${track?.identifier}")
        val next = track as FakeTrack?
        synchronized(lock) {
            val previous = active
            if (noInterrupt && previous != null) return false
            active = next
            if (previous != null) {
                dispatch(TrackEndEvent(this, previous, if (next == null) AudioTrackEndReason.STOPPED else AudioTrackEndReason.REPLACED))
            }
        }
        if (next == null) return false
        dispatch(TrackStartEvent(this, next))
        return true
    }

    override fun stopTrack() {
        calls.add("stop")
        stopWith(AudioTrackEndReason.STOPPED)
    }

    fun stopWith(reason: AudioTrackEndReason) {
        synchronized(lock) {
            val previous = active
            active = null
            if (previous != null) dispatch(TrackEndEvent(this, previous, reason))
        }
    }

    /** What lavaplayer dispatches when a track's executor throws. */
    fun fail(message: String) {
        val track = active ?: return
        dispatch(TrackExceptionEvent(this, track, FriendlyException(message, FriendlyException.Severity.COMMON, null)))
    }

    override fun provide(targetFrame: MutableAudioFrame): Boolean {
        provideCount++
        beforeProvide?.invoke()
        if (paused) return false
        val track = active ?: return false
        val frame = track.take()
        if (frame == null) {
            synchronized(lock) {
                if (active === track) {
                    active = null
                    dispatch(TrackEndEvent(this, track, AudioTrackEndReason.FINISHED))
                }
            }
            return false
        }
        targetFrame.timecode = frame.first
        targetFrame.volume = vol
        targetFrame.format = format
        targetFrame.isTerminator = false
        targetFrame.store(frame.second, 0, frame.second.size)
        return true
    }

    override fun provide(targetFrame: MutableAudioFrame, timeout: Long, unit: TimeUnit): Boolean = provide(targetFrame)
    override fun provide(): AudioFrame? = throw UnsupportedOperationException()
    override fun provide(timeout: Long, unit: TimeUnit): AudioFrame? = throw UnsupportedOperationException()

    override fun getVolume(): Int = vol

    override fun setVolume(volume: Int) {
        vol = volume
    }

    override fun setFilterFactory(factory: PcmFilterFactory?) {
        filterFactory = factory
    }

    override fun setFrameBufferDuration(duration: Int?) {
        frameBufferDuration = duration
    }

    override fun isPaused(): Boolean = paused

    override fun setPaused(value: Boolean) {
        synchronized(lock) {
            if (paused == value) return
            paused = value
            calls.add(if (value) "pause" else "resume")
        }
        dispatch(if (value) PlayerPauseEvent(this) else PlayerResumeEvent(this))
    }

    override fun destroy() {
        calls.add("destroy")
        stopWith(AudioTrackEndReason.STOPPED)
    }

    override fun addListener(listener: AudioEventListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioEventListener) {
        listeners.removeIf { it === listener }
    }

    override fun checkCleanup(threshold: Long) {}

    private fun dispatch(event: AudioEvent) {
        synchronized(lock) {
            dispatched.add(describe(event))
            for (listener in listeners) listener.onEvent(event)
        }
    }

    override fun toString() = "FakeDeck($name)"

    companion object {
        fun describe(event: AudioEvent): String = when (event) {
            is TrackStartEvent -> "start:${event.track.identifier}"
            is TrackEndEvent -> "end:${event.track.identifier}:${event.endReason}"
            is TrackExceptionEvent -> "exception:${event.track.identifier}"
            is PlayerPauseEvent -> "pause"
            is PlayerResumeEvent -> "resume"
            else -> event.javaClass.simpleName
        }
    }
}
