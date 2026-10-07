package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer
import lavalink.server.io.StatsCollector
import lavalink.server.util.encodeTrack
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.web.socket.WebSocketSession
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The play PATCH of the campaign (dev, 06/10): the engine's
 * `PATCH /v4/sessions/{s}/players/{g}` with an encoded track got no answer for
 * 20 s although the node had started the track (TrackStartEvent sent, audio
 * playing), and the node logged its completion only when the next PATCH for
 * that player, a stop, arrived. Production sees the same on about 1 play
 * PATCH in 800 (27/09). Driven here through the real handler, player,
 * lavaplayer playback thread and delegated track; only the client's websocket
 * is a stub (closed: the context drops what it would send).
 */
@ExtendWith(SpringExtension::class)
@AutoConfigureMockMvc
// Thousands of requests: the request log would only fill the test output.
@SpringBootTest(properties = ["logging.request.enabled=false"])
@ActiveProfiles("test")
class PlayPatchDelegatedTrackTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var serverConfig: ServerConfig

    @Autowired
    lateinit var socketServer: SocketServer

    @Autowired
    lateinit var statsCollector: StatsCollector

    @Autowired
    lateinit var audioPlayerManager: AudioPlayerManager

    @Autowired
    lateinit var koeOptions: KoeOptions

    private lateinit var context: SocketContext
    private lateinit var requests: ExecutorService
    private val source = TestDelegatedSourceManager()

    @BeforeEach
    fun setUp() {
        if (audioPlayerManager.source(TestDelegatedSourceManager::class.java) == null) {
            audioPlayerManager.registerSourceManager(source)
        }
        context = SocketContext(
            SESSION_ID,
            audioPlayerManager,
            serverConfig,
            Mockito.mock(WebSocketSession::class.java),
            socketServer,
            statsCollector,
            1L,
            "patch-hang-test",
            Koe.koe(koeOptions).newClient(1L),
            emptyList(),
            emptyList(),
        )
        socketServer.sessions[SESSION_ID] = context
        requests = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun tearDown() {
        socketServer.sessions.remove(SESSION_ID)
        context.shutdown()
        requests.shutdownNow()
    }

    @Test
    fun `neither a play PATCH nor a GET of the player waits for the track it started to end`() {
        val hung = Collections.synchronizedList(mutableListOf<String>())
        val polling = AtomicBoolean(true)
        // Clients reading the player back (the engine after a slow PATCH, a
        // dashboard): their reads keep the starting track's monitor busy, so
        // the race shows up in seconds instead of once in ~800 plays.
        val pollers = List(GET_POLLERS) { n ->
            thread(name = "get-poller-$n") {
                while (polling.get()) {
                    val startedAt = System.nanoTime()
                    getPlayer()
                    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    if (tookMs >= HANG_MS) hung += "a GET of the player took $tookMs ms"
                }
            }
        }

        try {
            repeat(ITERATIONS) { i ->
                val encoded = encodeTrack(audioPlayerManager, TestDelegatedTrack(TestDelegatedSourceManager.info("patch-$i"), source))
                val play = requests.submit<MvcResult> { patchPlayer("""{"track":{"encoded":"$encoded"}}""") }

                val answered = try {
                    play.get(HANG_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    null
                }

                if (answered == null) {
                    // As on dev: the next request for the player, a stop, frees it.
                    val stoppedAt = System.nanoTime()
                    patchPlayer(STOP)
                    val late = play.get(5, TimeUnit.SECONDS)
                    hung += "play PATCH $i unanswered after $HANG_MS ms, answered ${late.response.status} " +
                        "${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stoppedAt)} ms after the stop PATCH"
                } else {
                    assertEquals(200, answered.response.status, answered.response.contentAsString)
                    patchPlayer(STOP)
                }
            }
        } finally {
            polling.set(false)
            pollers.forEach { it.join(10_000) }
        }

        assertEquals(emptyList<String>(), hung.toList(), "${hung.size} requests hung over $ITERATIONS plays")
    }

    private fun patchPlayer(body: String): MvcResult = mvc.perform(
        patch("/v4/sessions/$SESSION_ID/players/$GUILD_ID")
            .header("Authorization", serverConfig.password)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    ).andReturn()

    private fun getPlayer(): MvcResult = mvc.perform(
        get("/v4/sessions/$SESSION_ID/players/$GUILD_ID")
            .header("Authorization", serverConfig.password)
    ).andReturn()

    companion object {
        private const val SESSION_ID = "patchhangtest001"
        private const val GUILD_ID = 123456789012345678L
        private const val ITERATIONS = 1500
        private const val GET_POLLERS = 3
        private const val HANG_MS = 3_000L
        private const val STOP = """{"track":{"encoded":null}}"""
    }
}
