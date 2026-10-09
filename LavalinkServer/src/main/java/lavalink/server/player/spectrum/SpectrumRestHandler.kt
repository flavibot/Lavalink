package lavalink.server.player.spectrum

import kotlinx.serialization.Serializable
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketServer
import lavalink.server.util.existingPlayer
import lavalink.server.util.socketContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Serializable
data class SpectrumRequest(val ttlMs: Long)

@Serializable
data class SpectrumState(val armed: Boolean, val untilMs: Long?, val bands: Int, val rateHz: Int)

/**
 * FlaviBot fork: arm a player's spectrum for a while, or stop it. Registered
 * only when `lavalink.server.spectrum.enabled` is true (the node advertises
 * `flavibot-spectrum` in /v4/info then). The /v4 authorization filter applies.
 */
@RestController
@ConditionalOnProperty(name = ["lavalink.server.spectrum.enabled"], havingValue = "true")
class SpectrumRestHandler(
    private val socketServer: SocketServer,
    private val serverConfig: ServerConfig,
) {
    private val config get() = serverConfig.spectrum ?: lavalink.server.config.SpectrumConfig()

    @PutMapping("/v4/sessions/{sessionId}/players/{guildId}/spectrum")
    fun arm(
        @PathVariable sessionId: String,
        @PathVariable guildId: Long,
        @RequestBody request: SpectrumRequest,
    ): ResponseEntity<SpectrumState> {
        val context = socketContext(socketServer, sessionId)
        val player = existingPlayer(context, guildId)
        if (request.ttlMs <= 0 || request.ttlMs > config.maxTtlMs) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ttlMs must be between 1 and ${config.maxTtlMs}")
        }
        val until = System.currentTimeMillis() + request.ttlMs
        player.armSpectrum(until, config.bands, config.rateHz)
        return ResponseEntity.ok(SpectrumState(true, until, config.bands, config.rateHz))
    }

    @DeleteMapping("/v4/sessions/{sessionId}/players/{guildId}/spectrum")
    fun stop(@PathVariable sessionId: String, @PathVariable guildId: Long): ResponseEntity<Unit> {
        val context = socketContext(socketServer, sessionId)
        existingPlayer(context, guildId).stopSpectrum()
        return ResponseEntity.noContent().build()
    }
}
