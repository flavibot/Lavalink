package lavalink.server.io

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import dev.arbjerg.lavalink.protocol.v4.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lavalink.server.assertCollected
import lavalink.server.config.ServerConfig
import lavalink.server.player.LavalinkPlayer
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.VoiceServerInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.gateway.MediaGatewayV8Connection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.socket.WebSocketSession
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A destroyed player leaves nothing behind in Koe (prod, 19/10: about 89,000
 * destroyed players on one node, each held by a Koe connection without a voice
 * gateway, for 8.4 GB of live heap and 309 real voice connections).
 *
 * Koe 3.1.0's `destroyConnection` removes the guild's connection from the
 * client, then closes it; the close reports `gatewayClosed` to the server's
 * listener on the same thread, and that listener sends a playerUpdate. The
 * update used to read the connection through
 * [SocketContext.getMediaConnection], which found none for the guild and
 * created one, with a listener holding the destroyed player. Nothing ever
 * closed that connection.
 */
class DestroyedPlayerTest {
    private val guildId = 1L
    private val playerManager = DefaultAudioPlayerManager()
    private val options: KoeOptions = KoeOptions.builder().setDAVEEnabled(false).create()
    private val client = Koe.koe(options).newClient(1234L)

    /** What the context sent its client, as `playerUpdate` or `event:<type>` (stats left out). */
    private val sent = CopyOnWriteArrayList<String>()
    private val context: SocketContext

    init {
        val outgoing = object : PluginEventHandler() {
            override fun onWebSocketMessageOut(context: ISocketContext, message: String) {
                val payload = json.parseToJsonElement(message).jsonObject
                when (val op = payload["op"]?.jsonPrimitive?.content) {
                    "stats" -> {}
                    "event" -> sent += "event:${payload["type"]?.jsonPrimitive?.content}"
                    else -> sent += "$op"
                }
            }
        }
        // No websocket client: what the context sends goes nowhere past the handlers.
        val session = Mockito.mock(WebSocketSession::class.java)
        val socketServer = Mockito.mock(SocketServer::class.java)
        context = SocketContext(
            "destroyed-player", playerManager, ServerConfig(), session, socketServer, StatsCollector(socketServer),
            1234L, null, client, listOf(outgoing), emptyList()
        )
    }

    @AfterEach
    fun tearDown() {
        context.shutdown()
        options.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
        playerManager.shutdown()
    }

    @Test
    fun `a destroyed player leaves no voice connection, and nothing in Koe keeps it`() {
        val player = playerWithVoice()
        sent.clear()

        context.destroyPlayer(guildId)

        assertNull(client.getConnection(guildId), "a voice connection for the guild of the destroyed player")
        assertEquals(emptySet<Long>(), client.connections.keys, "the guilds Koe still has a voice connection for")
        // The close still reaches the client as before; the update about a player that is gone does not.
        assertEquals(listOf("event:WebSocketClosedEvent"), sent)
        assertCollected(mapOf("the destroyed player" to player))
    }

    @Test
    fun `a voice update that replaces the connection leaves one connection, with the server's listener on it once`() {
        val player = context.getPlayer(guildId)
        val old = context.getMediaConnection(player) as MediaConnectionImpl
        establish(old)

        // What PlayerRestHandler does for a voice update with new server details.
        context.koe.destroyConnection(guildId)
        val conn = context.getMediaConnection(player) as MediaConnectionImpl

        assertNotSame(old, conn, "the connection the voice update connects")
        assertSame(conn, client.getConnection(guildId))
        assertEquals(setOf(guildId), client.connections.keys)
        // The server's listener is on the new connection exactly once: its READY reaches the player once.
        sent.clear()
        conn.dispatcher.gatewayReady(InetSocketAddress("127.0.0.1", 50000), 42)
        assertEquals(42L, player.voiceSsrc)
        assertEquals(listOf("playerUpdate"), sent)
    }

    /**
     * The player of the guild on a voice connection past its gateway's
     * handshake, as after a voice update. Only a weak reference to it leaves.
     */
    private fun playerWithVoice(): WeakReference<LavalinkPlayer> {
        val player = context.getPlayer(guildId)
        val connection = context.getMediaConnection(player) as MediaConnectionImpl
        establish(connection)
        player.provideTo(connection)
        return WeakReference(player)
    }

    /** Gives [connection] a gateway past its handshake: closing it goes through Koe's real close path. */
    private fun establish(connection: MediaConnectionImpl) {
        val gateway = EstablishedGateway(connection, serverInfo())
        MediaConnectionImpl::class.java.getDeclaredField("gatewayConnection").apply { isAccessible = true }
            .set(connection, gateway)
    }

    private fun serverInfo(): VoiceServerInfo = VoiceServerInfo.builder()
        .setSessionId("session")
        .setToken("token")
        .setEndpoint("127.0.0.1:1")
        .setChannelId(3L)
        .build()

    private class EstablishedGateway(connection: MediaConnectionImpl, info: VoiceServerInfo) :
        MediaGatewayV8Connection(connection, info) {
        init {
            connectFuture.complete(null)
        }
    }
}
