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
 * moves the next frame to position / 20.
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

    /** Every setMarker call, in order (null clears the marker). */
    val markers = CopyOnWriteArrayList<String>()

    /** Takes the next frame, or null once the frames are exhausted. */
    fun take(): Pair<Long, ByteArray>? {
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
    override fun getPosition(): Long = lastTimecode

    override fun setPosition(position: Long) {
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
