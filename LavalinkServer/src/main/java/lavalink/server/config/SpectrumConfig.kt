package lavalink.server.config

/**
 * FlaviBot fork: `lavalink.server.spectrum`. Off (the default) means no
 * /spectrum route (404) and nothing advertised in /v4/info. On, a client can
 * arm a player's spectrum for a while: the player then decodes each Opus frame
 * it sends, measures [bands] band energies and pushes them [rateHz] times a
 * second as `op: "spectrum"` messages. Nothing is computed for a player
 * nobody watches.
 */
class SpectrumConfig {
    var enabled: Boolean = false
    /** How many bands, log-spaced from 40 Hz to 16 kHz. */
    var bands: Int = 16
    /** How many messages a second while armed (every 50 / rateHz frames). */
    var rateHz: Int = 10
    /** The longest a single arm lasts; the client renews before it runs out. */
    var maxTtlMs: Long = 120_000
}
