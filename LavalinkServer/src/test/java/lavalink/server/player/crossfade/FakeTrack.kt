package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted track: [frames] are the encoded frames, 20 ms each, frame i has timecode i * 20.
 * [durationMs] is what the track claims, which may differ from the frames (a lying duration).
 * The position is the timecode of the last frame taken, like lavaplayer's executor; a seek
 * moves the next frame to position / 20. With [seekLatencyFrames] > 0 a seek is only queued, as
 * with lavaplayer's seek ghosting: the position reports the queued seek at once, but the next
 * [seekLatencyFrames] frames still come from the old position before the jump.
 */
class FakeTrack(
    private val id: String,
    val frames: List<ByteArray>,
    private val durationMs: Long = frames.size * 20L,
    stream: Boolean = false,
) : AudioTrack {
    private val trackInfo = AudioTrackInfo(id, "test", durationMs, id, stream, "fake://$id")

    @Volatile var nextIndex = 0
    @Volatile private var lastTimecode = 0L
    @Volatile private var data: Any? = null

    /** How many old frames a seek still serves before it lands (0: the seek is instant). */
    @Volatile var seekLatencyFrames = 0
    @Volatile private var queuedSeek: Long? = null
    @Volatile private var ghostFramesLeft = 0

    /** Runs at the start of every setPosition, before the seek is applied or queued. */
    @Volatile var beforeSetPosition: (() -> Unit)? = null

    /** Every setMarker call, in order (null clears the marker). */
    val markers = CopyOnWriteArrayList<String>()

    /** Takes the next frame, or null once the frames are exhausted. */
    fun take(): Pair<Long, ByteArray>? {
        val queued = queuedSeek
        if (queued != null) {
            if (ghostFramesLeft > 0) {
                ghostFramesLeft--
            } else {
                queuedSeek = null
                nextIndex = (queued / 20).toInt()
                lastTimecode = queued
            }
        }
        val index = nextIndex
        if (index >= frames.size) return null
        nextIndex = index + 1
        lastTimecode = index * 20L
        return lastTimecode to frames[index]
    }

    override fun getInfo(): AudioTrackInfo = trackInfo
    override fun getIdentifier(): String = id
    override fun getState(): AudioTrackState = AudioTrackState.PLAYING
    override fun stop() {}
    override fun isSeekable(): Boolean = true
    override fun getPosition(): Long = queuedSeek ?: lastTimecode

    override fun setPosition(position: Long) {
        beforeSetPosition?.invoke()
        if (seekLatencyFrames > 0) {
            ghostFramesLeft = seekLatencyFrames
            queuedSeek = position
            return
        }
        nextIndex = (position / 20).toInt()
        lastTimecode = position
    }

    override fun setMarker(marker: TrackMarker?) {
        markers.add(if (marker == null) "null" else "marker@${marker.timecode}")
    }

    override fun addMarker(marker: TrackMarker?) {}
    override fun removeMarker(marker: TrackMarker?) {}
    override fun getDuration(): Long = durationMs
    override fun makeClone(): AudioTrack = FakeTrack(id, frames, durationMs, trackInfo.isStream)
    override fun getSourceManager(): AudioSourceManager? = null

    override fun setUserData(userData: Any?) {
        data = userData
    }

    override fun getUserData(): Any? = data

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getUserData(klass: Class<T>?): T = data as T

    override fun toString() = "FakeTrack($id)"
}
