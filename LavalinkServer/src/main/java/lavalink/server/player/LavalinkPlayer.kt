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
import lavalink.server.player.filters.FilterChain
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.media.AudioFrameProvider
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    /** Poller thread only: whether the previous poll got a frame. */
    private var providing = false
    var filters: FilterChain = FilterChain()
        set(value) {
            audioPlayer.setFilterFactory(value.takeIf { it.isEnabled })
            field = value
        }

    // Crossfade proof of concept (FlaviBot fork): a two-deck player when enabled, otherwise
    // the plain lavaplayer player as upstream. The same three listeners either way.
    override val audioPlayer: AudioPlayer = (
        if (serverConfig.crossfade?.enabled == true) {
            CrossfadeAudioPlayer(
                deckFactory = { audioPlayerManager.createPlayer() },
                codecFactory = { OpusFrameCodec(audioPlayerManager.configuration) },
                outputFormat = audioPlayerManager.configuration.outputFormat,
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

    private inner class Provider : AudioFrameProvider {

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
    }
}
