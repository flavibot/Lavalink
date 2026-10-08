package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import java.io.DataInput
import java.io.DataOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * The container track a delegated track hands its playback to: its decode loop
 * runs until the track is stopped, like a long stream whose frame buffer the
 * voice connection drains in real time. [started] opens once the playback
 * thread is in its read loop (so inside `DelegatedAudioTrack.processDelegate`,
 * and interruptible for a seek); [seeked] once a seek has reached it.
 */
class EndlessInnerTrack(info: AudioTrackInfo) : BaseAudioTrack(info) {
    val started = CountDownLatch(1)
    val seeked = CountDownLatch(1)
    val seekedTo = AtomicLong(-1)

    override fun process(executor: LocalAudioTrackExecutor) {
        executor.executeProcessingLoop(
            LocalAudioTrackExecutor.ReadExecutor {
                started.countDown()
                Thread.sleep(Long.MAX_VALUE)
            },
            LocalAudioTrackExecutor.SeekExecutor { position ->
                seekedTo.set(position)
                seeked.countDown()
            },
        )
    }
}

/**
 * Same shape as obsidian's (and lavaplayer's) HttpAudioTrack, the source of the
 * tracks that hung on dev: `process()` builds the container track (the HTTP
 * stream opens lazily, inside it) and hands the playback to it through
 * `processDelegate`.
 */
class TestDelegatedTrack(
    info: AudioTrackInfo,
    private val source: AudioSourceManager,
) : DelegatedAudioTrack(info) {
    @Volatile
    var inner: EndlessInnerTrack? = null

    override fun process(executor: LocalAudioTrackExecutor) {
        val track = EndlessInnerTrack(trackInfo)
        inner = track
        processDelegate(track, executor)
    }

    override fun makeShallowClone(): AudioTrack = TestDelegatedTrack(trackInfo, source)

    override fun getSourceManager(): AudioSourceManager = source
}

/** Encodes and decodes [TestDelegatedTrack]s, so a PATCH can carry one as `encoded`. */
class TestDelegatedSourceManager : AudioSourceManager {
    override fun getSourceName() = SOURCE_NAME

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? =
        if (reference.identifier.startsWith("$SOURCE_NAME:")) TestDelegatedTrack(info(reference.identifier), this) else null

    override fun isTrackEncodable(track: AudioTrack) = true

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {}

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack = TestDelegatedTrack(trackInfo, this)

    override fun shutdown() {}

    companion object {
        const val SOURCE_NAME = "delegated-test"

        fun info(identifier: String) =
            AudioTrackInfo("Long stream", "Test artist", 1_412_501, identifier, false, "https://example.com/$identifier")
    }
}
