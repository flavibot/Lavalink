package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketServer
import lavalink.server.util.decodeTrack
import lavalink.server.util.existingPlayer
import lavalink.server.util.socketContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Serializable
data class CrossfadeTrackRequest(val encoded: String, val userData: JsonObject? = null)

@Serializable
data class CrossfadeRequest(val track: CrossfadeTrackRequest, val fadeMs: Long)

@Serializable
data class CrossfadeTrackState(
    val identifier: String,
    val title: String,
    val position: Long? = null,
    val length: Long? = null,
    val ready: Boolean? = null,
    val failed: Boolean? = null,
)

@Serializable
data class CrossfadeCounters(
    val armed: Long,
    val overlaps: Long,
    val completed: Long,
    val endSwaps: Long,
    val tailEndedEarly: Long,
    val cutShort: Long,
    val disarmed: Long,
    val seeksRefused: Long,
    val codecOpens: Long,
    val mixedFrames: Long,
    val prerollFrames: Long,
    val seekHeldFrames: Long,
)

@Serializable
data class CrossfadeMixMicros(val last: Long, val avg: Long, val max: Long)

@Serializable
data class CrossfadeState(
    val phase: String,
    val fadeMs: Long,
    val current: CrossfadeTrackState?,
    val next: CrossfadeTrackState?,
    val tail: CrossfadeTrackState?,
    val rampFrame: Int,
    val rampFrames: Int,
    val gainB: Double,
    val counters: CrossfadeCounters,
    val mixMicros: CrossfadeMixMicros,
)

/**
 * Crossfade proof of concept: arm, inspect and disarm the node-triggered crossfade of one
 * player. Registered only when `lavalink.server.crossfade.enabled` is true, so a 404 on these
 * paths tells a client the node cannot crossfade. The /v4 authorization filter applies.
 */
@RestController
@ConditionalOnProperty(name = ["lavalink.server.crossfade.enabled"], havingValue = "true")
class CrossfadeRestHandler(
    private val socketServer: SocketServer,
    private val serverConfig: ServerConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrossfadeRestHandler::class.java)
        const val MIN_FADE_MS = 200L
    }

    @PostMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade")
    fun arm(
        @PathVariable sessionId: String,
        @PathVariable guildId: Long,
        @RequestBody request: CrossfadeRequest,
    ): ResponseEntity<CrossfadeState> {
        val context = socketContext(socketServer, sessionId)
        val wrapper = crossfadePlayer(existingPlayer(context, guildId).audioPlayer)

        val maxFadeMs = serverConfig.crossfade?.maxFadeMs ?: 12000L
        if (request.fadeMs < MIN_FADE_MS || request.fadeMs > maxFadeMs) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fadeMs must be between $MIN_FADE_MS and $maxFadeMs")
        }
        val codecName = context.audioPlayerManager.configuration.outputFormat.codecName()
        if (codecName != OpusAudioDataFormat.CODEC_NAME) {
            throw CrossfadeConflictException("Crossfade needs an Opus output format, not $codecName")
        }
        val track = try {
            decodeTrack(context.audioPlayerManager, request.track.encoded)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not decode the track: ${e.message}")
        }
        request.track.userData?.let { track.userData = it }

        wrapper.arm(track, request.fadeMs)
        log.info("{}: crossfade armed to {} ({} ms) over REST", wrapper.label, track.identifier, request.fadeMs)
        return ResponseEntity.ok(toState(wrapper.state()))
    }

    @GetMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade")
    fun state(@PathVariable sessionId: String, @PathVariable guildId: Long): ResponseEntity<CrossfadeState> {
        val context = socketContext(socketServer, sessionId)
        val wrapper = crossfadePlayer(existingPlayer(context, guildId).audioPlayer)
        return ResponseEntity.ok(toState(wrapper.state()))
    }

    @DeleteMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade")
    fun disarm(@PathVariable sessionId: String, @PathVariable guildId: Long): ResponseEntity<Unit> {
        val context = socketContext(socketServer, sessionId)
        val wrapper = crossfadePlayer(existingPlayer(context, guildId).audioPlayer)
        wrapper.disarm()
        return ResponseEntity.noContent().build()
    }

    private fun crossfadePlayer(player: Any): CrossfadeAudioPlayer =
        player as? CrossfadeAudioPlayer
            ?: throw CrossfadeConflictException("This player was created without crossfade support")

    private fun toState(state: CrossfadeAudioPlayer.State): CrossfadeState {
        val c = state.counters
        return CrossfadeState(
            phase = state.phase.name,
            fadeMs = state.fadeMs,
            current = state.current?.let { track(it, withPosition = true) },
            next = state.next?.let { track(it).copy(ready = state.nextReady, failed = state.nextFailed) },
            tail = state.tail?.let { track(it).copy(position = it.position) },
            rampFrame = state.rampFrame,
            rampFrames = state.rampFrames,
            gainB = state.gainB,
            counters = CrossfadeCounters(
                armed = c.armed.get(),
                overlaps = c.overlaps.get(),
                completed = c.completed.get(),
                endSwaps = c.endSwaps.get(),
                tailEndedEarly = c.tailEndedEarly.get(),
                cutShort = c.cutShort.get(),
                disarmed = c.disarmed.get(),
                seeksRefused = c.seeksRefused.get(),
                codecOpens = c.codecOpens.get(),
                mixedFrames = c.mixedFrames.get(),
                prerollFrames = c.prerollFrames.get(),
                seekHeldFrames = c.seekHeldFrames.get(),
            ),
            mixMicros = CrossfadeMixMicros(state.mixLastMicros, state.mixAvgMicros, state.mixMaxMicros),
        )
    }

    private fun track(track: AudioTrack, withPosition: Boolean = false) = CrossfadeTrackState(
        identifier = track.identifier,
        title = track.info.title,
        position = if (withPosition) track.position else null,
        length = if (withPosition) track.duration else null,
    )
}
