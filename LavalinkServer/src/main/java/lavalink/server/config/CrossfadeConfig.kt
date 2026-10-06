package lavalink.server.config

/**
 * Crossfade proof of concept (FlaviBot fork). Off by default: when off, players are plain
 * lavaplayer players and the /crossfade endpoints do not exist (404).
 */
class CrossfadeConfig {
    var enabled: Boolean = false
    var maxFadeMs: Long = 12000
}
