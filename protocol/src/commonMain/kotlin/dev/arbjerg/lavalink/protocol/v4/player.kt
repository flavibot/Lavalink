package dev.arbjerg.lavalink.protocol.v4

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

inline fun <reified T> JsonObject.deserialize(): T =
    deserialize(json.serializersModule.serializer<T>())

fun <T> JsonObject.deserialize(deserializer: DeserializationStrategy<T>): T =
    json.decodeFromJsonElement(deserializer, this)

@Serializable
@JvmInline
value class Players(val players: List<Player>)

@Serializable
data class Player(
    val guildId: String,
    val track: Track?,
    val volume: Int,
    val paused: Boolean,
    val state: PlayerState,
    val voice: VoiceState,
    val filters: Filters
)

@Serializable
data class Track(
    val encoded: String,
    val info: TrackInfo,
    @EncodeDefault
    val pluginInfo: JsonObject = JsonObject(emptyMap()),
    @EncodeDefault
    val userData: JsonObject = JsonObject(emptyMap())
) : LoadResult.Data {

    /**
     * Deserialize the plugin info into a specific type.
     * This method is a convenience method meant to be used in Java,
     * since Kotlin extension methods are painful to use in Java.
     *
     * @param deserializer The deserializer to use. (e.g. `T.Companion.serializer()`)
     *
     * @return the deserialized plugin info as type T
     */
    fun <T> deserializePluginInfo(deserializer: DeserializationStrategy<T>): T = pluginInfo.deserialize(deserializer)

    /**
     * Deserialize the user data into a specific type.
     * This method is a convenience method meant to be used in Java,
     * since Kotlin extension methods are painful to use in Java.
     *
     * @param deserializer The deserializer to use. (e.g. `T.Companion.serializer()`)
     *
     * @return the deserialized user data as type T
     */
    fun <T> deserializeUserData(deserializer: DeserializationStrategy<T>): T = userData.deserialize(deserializer)

    /**
     * Copy this track with a new user data json.
     *
     * @param userData The new user data json.
     *
     * @return A copy of this track with the new user data json.
     */
    fun copyWithUserData(userData: JsonObject): Track {
        return copy(userData = userData)
    }
}

@Serializable
@JvmInline
value class Tracks(val tracks: List<Track>)

@Serializable
@JvmInline
value class EncodedTracks(val tracks: ArrayList<String>)

@Serializable
data class TrackInfo(
    val identifier: String,
    val isSeekable: Boolean,
    val author: String,
    val length: Long,
    val isStream: Boolean,
    val position: Long,
    val title: String,
    val uri: String?,
    val sourceName: String,
    val artworkUrl: String?,
    val isrc: String?
)

@Serializable
data class VoiceState(
    val token: String,
    val endpoint: String,
    val sessionId: String,
    val channelId: String?
)

@Serializable
data class PlayerState(
    val time: Long,
    val position: Long,
    val connected: Boolean,
    val ping: Long,
    /** Per-player voice diagnostics (FlaviBot fork); absent on servers that do not produce them. */
    val voice: VoiceDiagnostics? = null,
)

/**
 * What the voice path of one player looks like right now, readable on the player
 * (REST GET and every playerUpdate) instead of a per-player metric. Cheap:
 * counters and fields the server already holds.
 */
@Serializable
data class VoiceDiagnostics(
    /** SSRC Discord assigned at the last voice READY (unsigned). */
    val ssrc: Long?,
    /** The voice server the RTP goes to (ip:port from READY): the server behind the Cloudflare gateway. */
    val server: String?,
    /** Epoch ms of the last voice READY. */
    val connectedAt: Long?,
    /** DAVE (E2EE) protocol version in use, 0 = transport-only. */
    val daveVersion: Int?,
    /** Whether the sender key ratchet is in place: false while frames would be replaced by silence. */
    val daveReady: Boolean?,
    /** Provide → no-provide transitions while a track plays unpaused, since the player was created: each one is at least 100 ms of silence. */
    val cuts: Int,
    val lastCutAt: Long?,
    /** Frames not provided / provided during the last whole minute (the stats counter). */
    val lossLastMinute: Int,
    val sentLastMinute: Int,
    /** Audio buffered ahead of the sender in the lavaplayer frame buffer (ms); drains before a cut. */
    val bufferedMs: Long?,
    /**
     * Frames held back during the last whole minute because the OS refused UDP sends to the voice server
     * (an OUTPUT DROP rule, no route): the track does not advance meanwhile. Apart from lossLastMinute, which is
     * the source running dry. Defaults keep clients that build or read this without them working.
     */
    val sendFailuresLastMinute: Int = 0,
    /** Epoch ms since which the OS refuses those sends, null while it accepts them. */
    val sendRefusedSince: Long? = null,
)

@Serializable
data class PlayerUpdateTrack(
    val encoded: Omissible<String?> = Omissible.Omitted(),
    val identifier: Omissible<String> = Omissible.Omitted(),
    val userData: Omissible<JsonObject> = Omissible.Omitted()
)

@Serializable
data class PlayerUpdate(
    @Deprecated("Use PlayerUpdateTrack#encoded instead", ReplaceWith("encoded"))
    val encodedTrack: Omissible<String?> = Omissible.Omitted(),
    @Deprecated("Use PlayerUpdateTrack#identifier instead")
    val identifier: Omissible<String> = Omissible.Omitted(),
    val track: Omissible<PlayerUpdateTrack> = Omissible.Omitted(),
    val position: Omissible<Long> = Omissible.Omitted(),
    val endTime: Omissible<Long?> = Omissible.Omitted(),
    val volume: Omissible<Int> = Omissible.Omitted(),
    val paused: Omissible<Boolean> = Omissible.Omitted(),
    val filters: Omissible<Filters> = Omissible.Omitted(),
    val voice: Omissible<VoiceState> = Omissible.Omitted()
)
