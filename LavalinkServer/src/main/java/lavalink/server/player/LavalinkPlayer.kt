/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import dev.arbjerg.lavalink.protocol.v4.VoiceDiagnostics
import moe.kyokobot.koe.internal.MediaConnectionImpl
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.IPlayer
import io.netty.buffer.ByteBuf
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer.Companion.sendPlayerUpdate
import lavalink.server.player.crossfade.CrossfadeAudioPlayer
import lavalink.server.player.crossfade.OpusFrameCodec
import org.slf4j.LoggerFactory
import org.springframework.web.server.ResponseStatusException
import lavalink.server.player.filters.FilterChain
import lavalink.server.player.rtcp.KoeRtcpTap
import lavalink.server.player.rtcp.RtcpReceiver
import lavalink.server.player.rtcp.RtcpReceiverStats
import lavalink.server.player.rtcp.RtcpRouter
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.media.AudioFrameProvider
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(LavalinkPlayer::class.java)

class LavalinkPlayer(
    override val socketContext: SocketContext,
    override val guildId: Long,
    private val serverConfig: ServerConfig,
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
) : AudioEventAdapter(), IPlayer {
    private val buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val mutableFrame = MutableAudioFrame().apply { setBuffer(buffer) }

    val audioLossCounter = AudioLossCounter()
    var endMarkerHit = false

    // Voice diagnostics (FlaviBot fork): what the last voice READY said, and
    // the cuts the sender saw. Written by the Koe event thread / the poller,
    // read by the update thread: volatile is enough for diagnostics.
    @Volatile var voiceSsrc: Long? = null
    @Volatile var voiceServer: String? = null
    @Volatile var voiceConnectedAt: Long? = null
    @Volatile var cuts: Int = 0
    @Volatile var lastCutAt: Long? = null
    /**
     * Discord's RTCP reports about this player's audio on the current media
     * connection; replaced at every new connection, null before the first.
     */
    @Volatile var rtcp: RtcpReceiverStats? = null
        private set
    private var rtcpRegistration: RtcpRouter.Registration? = null
    /**
     * Since when the OS refuses UDP sends to the voice server of the current
     * connection (epoch ms), null while it accepts them. Written by that
     * connection's poller.
     */
    @Volatile var sendRefusedSince: Long? = null
    /**
     * Since when the poller of the current connection holds the audio because
     * the bot is not admitted to the call's end-to-end encryption (epoch ms),
     * null otherwise. Written by that connection's poller.
     */
    @Volatile var e2eeWaitingSince: Long? = null
    // Not the player's monitor: Koe's event loop takes this one (READY,
    // SESSION_DESCRIPTION), and PlayerRestHandler holds the monitor across a
    // new connection's handshake, which may need that same event loop.
    private val rtcpLock = Any()
    /** Poller thread only: whether the previous poll got a frame. */
    private var providing = false
    var filters: FilterChain = FilterChain()
        set(value) {
            val rampMs = serverConfig.filterRampMs
            if (rampMs > 0 && field.rampTo(value, rampMs)) {
                // The same filters with new settings: the live ones glide there
                // (FilterRamp.kt), nothing is rebuilt, and `field` stays the
                // chain lavaplayer holds, now carrying the new settings.
            } else {
                if (rampMs > 0) value.startNeutralFor(value.kinds - field.kinds, rampMs)
                audioPlayer.setFilterFactory(value.takeIf { it.isEnabled })
                field = value
            }
            if (serverConfig.instantFilters) flushFrameBufferForFilters()
        }

    /**
     * FlaviBot fork (`lavalink.server.instantFilters`): a filter change reaches the member at
     * once. lavaplayer runs the new chain on the frames it decodes from now on, while the frame
     * buffer (frameBufferDurationMs, 5 s in production) still holds frames filtered the old way,
     * so a member heard an equalizer or a speed change seconds late. A seek to the position the
     * member hears asks the decoder to restart there, through the new chain; with seek ghosting
     * the buffered frames play on until the first new one arrives, so nothing goes silent.
     * Only a seekable track can: a stream keeps the buffer's delay. The crossfade player refuses
     * a seek during an overlap (the tail would be lost): the change then lands after the buffer,
     * as before.
     */
    private fun flushFrameBufferForFilters() {
        val track = audioPlayer.playingTrack ?: return
        if (!track.isSeekable) return
        try {
            seekTo(track.position)
        } catch (e: ResponseStatusException) {
            // The crossfade player refuses a seek during an overlap (409): the buffered frames keep the old filters.
            log.debug("Guild {}: no frame buffer flush for the filter change: {}", guildId, e.reason)
        } catch (e: RuntimeException) {
            // "Can't seek when not playing anything": the track ended between the two reads.
            log.debug("Guild {}: no frame buffer flush for the filter change: {}", guildId, e.message)
        }
    }

    // Crossfade proof of concept (FlaviBot fork): a two-deck player when enabled, otherwise
    // the plain lavaplayer player as upstream. The same three listeners either way.
    override val audioPlayer: AudioPlayer = (
        if (serverConfig.crossfade?.enabled == true) {
            CrossfadeAudioPlayer(
                deckFactory = { audioPlayerManager.createPlayer() },
                codecFactory = { OpusFrameCodec(audioPlayerManager.configuration) },
                outputFormat = audioPlayerManager.configuration.outputFormat,
                label = "Guild $guildId (bot ${socketContext.userId})",
            )
        } else {
            audioPlayerManager.createPlayer()
        }
    ).also {
        it.addListener(this)
        it.addListener(EventEmitter(audioPlayerManager, this, pluginInfoModifiers))
        it.addListener(audioLossCounter)
    }

    private var updateFuture: ScheduledFuture<*>? = null

    override val isPlaying: Boolean
        get() = audioPlayer.playingTrack != null && !audioPlayer.isPaused

    override val track: AudioTrack?
        get() = audioPlayer.playingTrack

    fun destroy() {
        audioPlayer.destroy()
        stopRtcp()
    }

    /**
     * Reads Discord's RTCP reports for the media connection [udp], from its
     * session description on. Discord sends them to the address our RTP comes
     * from: Koe's socket until the first frame, the udp-queue's socket after
     * that (see [lavalink.server.player.rtcp.SharedSocketQueueManagerPool]).
     */
    fun readRtcpOf(udp: DiscordUDPConnection, router: RtcpRouter = RtcpRouter.shared) {
        synchronized(rtcpLock) {
            stopRtcp()
            val receiver = RtcpReceiver()
            rtcpRegistration = router.register(udp, receiver)
            KoeRtcpTap.attach(udp, receiver)
            rtcp = receiver.stats
        }
    }

    /** The media connection is gone or replaced: its reports no longer apply. */
    fun stopRtcp() {
        synchronized(rtcpLock) {
            rtcpRegistration?.close()
            rtcpRegistration = null
            rtcp = null
        }
    }

    fun provideTo(connection: MediaConnection) {
        connection.audioSender = Provider()
    }

    /**
     * The voice path of this player right now, for the playerUpdate state and
     * the REST player. `connection` is the Koe media connection of the guild
     * (null when none exists yet).
     */
    fun voiceDiagnostics(connection: MediaConnection?): VoiceDiagnostics {
        val dave = (connection as? MediaConnectionImpl)?.getDAVEManager()
        val buffer = ((audioPlayer.playingTrack as? InternalAudioTrack)?.activeExecutor as? LocalAudioTrackExecutor)?.audioBuffer
        return VoiceDiagnostics(
            ssrc = voiceSsrc,
            server = voiceServer,
            connectedAt = voiceConnectedAt,
            daveVersion = dave?.currentProtocolVersion,
            daveReady = dave?.isReadyToSend,
            cuts = cuts,
            lastCutAt = lastCutAt,
            lossLastMinute = audioLossCounter.lastMinuteLoss,
            sentLastMinute = audioLossCounter.lastMinuteSuccess,
            // 20 ms of audio per frame.
            bufferedMs = buffer?.let { (it.fullCapacity - it.remainingCapacity) * 20L },
            rtcp = rtcp?.snapshot(),
            sendFailuresLastMinute = audioLossCounter.lastMinuteSendFailures,
            sendRefusedSince = sendRefusedSince,
            e2eeWaitingSince = e2eeWaitingSince,
            e2eeHeldLastMinute = audioLossCounter.lastMinuteE2EEHeld,
        )
    }


    override fun play(track: AudioTrack) {
        audioPlayer.playTrack(track)
        sendPlayerUpdate(socketContext, this)
    }

    override fun stop() {
        audioPlayer.stopTrack()
    }

    override fun setPause(pause: Boolean) {
        audioPlayer.isPaused = pause
    }

    override fun seekTo(position: Long) {
        // C2: a crossfade player refuses seeks during an overlap (409); while armed a seek only
        // moves the trigger. Living here, the guard also covers plugins calling IPlayer.seekTo.
        (audioPlayer as? CrossfadeAudioPlayer)?.let {
            it.seek(position)
            return
        }
        val track = audioPlayer.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        track.position = position
    }

    override fun setVolume(volume: Int) {
        audioPlayer.volume = volume
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        // 2025-07-31 changed from !! to ? due to possible race condition (or general condition) where
        // updateFuture has not be initialised yet somehow.
        updateFuture?.cancel(false)
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        if (updateFuture?.isCancelled == false) {
            return
        }

        updateFuture = socketContext.playerUpdateService.scheduleAtFixedRate(
            { sendPlayerUpdate(socketContext, this) },
            0,
            serverConfig.playerUpdateInterval.toLong(),
            TimeUnit.SECONDS
        )
    }

    private inner class Provider : AudioFrameProvider, SendPathListener {

        override fun onCodecChanged(codec: CodecInstance) {
        }

        override fun dispose() {}

        override fun canProvide() = audioPlayer.provide(mutableFrame).also { provided ->
            if (!provided) {
                audioLossCounter.onLoss()
                // A cut: frames were flowing and the buffer is empty while a
                // track plays unpaused. Not a track switch (playingTrack is
                // null between two tracks) and not a pause.
                if (providing && isPlaying) {
                    cuts++
                    lastCutAt = System.currentTimeMillis()
                }
            }
            providing = provided
        }

        override fun provideFrame(buf: ByteBuf): Boolean {
            audioLossCounter.onSuccess()
            buf.writeBytes(buffer.flip())
            return true
        }

        // Asked before canProvide(), which pulls the frame (and advances the track).
        // A track that failed before its first frame only has lavaplayer's end
        // marker left, and its end (LOAD_FAILED) is only sent once that marker
        // is pulled: held, a failed skip would not end until the refusal did,
        // or until the player cleanup, which the engine replays as a voice
        // outage. Pulling the marker sends no packet.
        override fun hasAudioToSend() = isPlaying &&
            (audioPlayer.playingTrack as? InternalAudioTrack)?.activeExecutor?.failedBeforeLoad() != true

        override fun onSendRefused() = audioLossCounter.onSendFailure()

        override fun onSendPathChanged(refusedSince: Long?) {
            sendRefusedSince = refusedSince
        }

        // Pulled as canProvide() pulls, the frame dropped: the track advances
        // and ends as it would audibly, nothing is sent.
        override fun drainHeldFrame() {
            val provided = audioPlayer.provide(mutableFrame)
            if (provided) audioLossCounter.onE2EEHeld() else audioLossCounter.onLoss()
            providing = provided
        }

        // Paused or trackless, provide() pulls nothing and only marks the
        // player as polled; a failed track's end marker ends it.
        override fun keepAlive() {
            providing = audioPlayer.provide(mutableFrame)
        }

        override fun onE2EEWaitChanged(waitingSince: Long?) {
            e2eeWaitingSince = waitingSince
        }
    }
}
