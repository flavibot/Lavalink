package lavalink.server.io

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import dev.arbjerg.lavalink.protocol.v4.Message
import dev.arbjerg.lavalink.protocol.v4.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lavalink.server.config.ServerConfig
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeEventListener
import moe.kyokobot.koe.KoeOptions
import moe.kyokobot.koe.VoiceServerInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
import moe.kyokobot.koe.internal.gateway.MediaGatewayV8Connection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.web.socket.WebSocketSession
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the client sees of a voice gateway close, through Koe's own close path
 * (AbstractMediaGatewayConnection.closeSession and reportClose) and the
 * server's Koe listener.
 *
 * The client rejoins the voice channel on a WebSocketClosedEvent 4006. Since
 * Koe 3.1.0 a 4006 on a session that had been READY makes Koe IDENTIFY a new
 * session first; when it gives up, it calls `gatewayClosed` and then
 * `sessionLost` with the same code. The client must get exactly one event.
 */
class VoiceCloseEventsTest {
    private val guildId = 1L
    private val playerManager = DefaultAudioPlayerManager()
    private val options: KoeOptions = KoeOptions.builder().setDAVEEnabled(false).create()
    private val client = Koe.koe(options).newClient(1234L)
    private val closes = LinkedBlockingQueue<Message.EmittedEvent.WebSocketClosedEvent>()
    private val lostSessions = CopyOnWriteArrayList<Int>()
    private val context: SocketContext
    private val connection: MediaConnectionImpl

    init {
        // Every payload the context sends to its client goes through its plugin event handlers.
        val outgoing = object : PluginEventHandler() {
            override fun onWebSocketMessageOut(context: ISocketContext, message: String) {
                val type = json.parseToJsonElement(message).jsonObject["type"]?.jsonPrimitive?.content
                if (type == Message.EmittedEvent.Type.WebSocketClosed.value) {
                    closes += json.decodeFromString(Message.Serializer, message) as Message.EmittedEvent.WebSocketClosedEvent
                }
            }
        }
        // No websocket client: what the context sends goes nowhere past the handlers.
        val session = Mockito.mock(WebSocketSession::class.java)
        val socketServer = Mockito.mock(SocketServer::class.java)
        context = SocketContext(
            "close-events", playerManager, ServerConfig(), session, socketServer, StatsCollector(socketServer),
            1234L, null, client, listOf(outgoing), emptyList()
        )
        // The connection a voice update creates, with the server's listener on it.
        connection = context.getMediaConnection(context.getPlayer(guildId)) as MediaConnectionImpl
        connection.registerListener(object : KoeEventListener {
            override fun sessionLost(code: Int, reason: String?) {
                lostSessions += code
            }
        })
    }

    @AfterEach
    fun tearDown() {
        context.shutdown()
        options.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
        playerManager.shutdown()
    }

    @Test
    fun `a session Koe gives up on reaches the client as one WebSocketClosedEvent 4006`() {
        // A 4006 before READY: the IDENTIFY of a new session was refused, Koe has nothing left to try.
        val gateway = EstablishedGateway(connection, serverInfo(refusingEndpoint()), ready = false)

        gateway.closedByVoiceServer(4006, "Session is no longer valid.")

        val close = nextClose()
        assertEquals(guildId.toString(), close.guildId)
        assertEquals(4006, close.code)
        assertEquals("Session is no longer valid.", close.reason)
        assertTrue(close.byRemote, "byRemote")
        assertEquals(listOf(4006), lostSessions, "Koe's sessionLost, right after the close")
        assertNull(closes.poll(300, TimeUnit.MILLISECONDS), "a second WebSocketClosedEvent for the lost session")
    }

    @Test
    fun `a 4006 on a session that was READY is not reported, the close Koe ends on is`() {
        // Koe identifies a new session instead of reporting the 4006. The voice
        // server refuses the connections: Koe gives up after its reconnect
        // attempts and reports that close, not the 4006.
        val gateway = EstablishedGateway(connection, serverInfo(refusingEndpoint()), ready = true)

        gateway.closedByVoiceServer(4006, "Session is no longer valid.")

        val close = nextClose()
        assertEquals(1006, close.code)
        assertEquals(false, close.byRemote, "byRemote")
        assertEquals(emptyList<Int>(), lostSessions, "sessionLost")
        assertNull(closes.poll(300, TimeUnit.MILLISECONDS), "a second WebSocketClosedEvent")
    }

    @ParameterizedTest
    @ValueSource(ints = [4014, 4022])
    fun `a close Koe does not retry reaches the client as is`(code: Int) {
        val gateway = EstablishedGateway(connection, serverInfo(refusingEndpoint()), ready = true)

        gateway.closedByVoiceServer(code, "closed")

        val close = nextClose()
        assertEquals(code, close.code)
        assertTrue(close.byRemote, "byRemote")
        assertEquals(emptyList<Int>(), lostSessions, "sessionLost")
        assertNull(closes.poll(300, TimeUnit.MILLISECONDS), "a second WebSocketClosedEvent")
    }

    @Test
    fun `a voice gateway that never connected fails the connect and sends no WebSocketClosedEvent`() {
        // What a voice update meets when the voice server refuses the first
        // connection: PlayerRestHandler sees the connect fail and answers 500.
        val connect = connection.connect(serverInfo(refusingEndpoint())).toCompletableFuture()

        assertThrows(ExecutionException::class.java) { connect.get(5, TimeUnit.SECONDS) }
        assertNull(closes.poll(300, TimeUnit.MILLISECONDS), "a WebSocketClosedEvent")
        assertEquals(emptyList<Int>(), lostSessions, "sessionLost")
    }

    private fun nextClose(): Message.EmittedEvent.WebSocketClosedEvent =
        closes.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no WebSocketClosedEvent within 5 s")

    private fun serverInfo(endpoint: String): VoiceServerInfo = VoiceServerInfo.builder()
        .setSessionId("session")
        .setToken("token")
        .setEndpoint(endpoint)
        .setChannelId(3L)
        .build()

    /** A loopback port nothing listens on: connections to it are refused at once. */
    private fun refusingEndpoint(): String =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { "${it.inetAddress.hostAddress}:${it.localPort}" }

    /**
     * Koe's voice gateway past its WebSocket handshake, as Koe's own
     * GatewayFailureTest builds it: a close goes through Koe's real close path.
     * [ready]: READY was seen, so Koe would resume the session.
     */
    private class EstablishedGateway(connection: MediaConnectionImpl, info: VoiceServerInfo, ready: Boolean) :
        MediaGatewayV8Connection(connection, info) {
        init {
            connectFuture.complete(null)
            resumable = ready
        }

        fun closedByVoiceServer(code: Int, reason: String) = onClose(code, reason, true)
    }
}
