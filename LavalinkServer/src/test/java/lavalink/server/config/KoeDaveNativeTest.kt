package lavalink.server.config

import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.internal.MediaConnectionImpl
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * DAVE (Discord's end-to-end encryption) runs in libdave-jvm's native library,
 * shipped by the `libdave-natives-*` dependencies. When it does not load, Koe
 * only logs a warning and connects without DAVE: this fails instead, on the
 * platform the tests run on.
 */
class KoeDaveNativeTest {
    @Test
    fun `the server's Koe options get a working DAVE implementation`() {
        val options = KoeConfiguration(ServerConfig()).koeOptions()
        val client = Koe.koe(options).newClient(1234L)
        try {
            // What every voice connection does on its gateway: a DAVE session and an
            // encryptor, in the native library. None when the library did not load.
            val connection = client.createConnection(1L) as MediaConnectionImpl
            val dave = connection.createDAVEManager()
            assertNotNull(dave, "DAVE manager of a voice connection: none when libdave's native library did not load")
            assertTrue(dave.maxDAVEProtocolVersion >= 1, "max DAVE protocol version: ${dave.maxDAVEProtocolVersion}")
            client.destroyConnection(1L)
        } finally {
            client.close()
            options.eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
        }
    }
}
