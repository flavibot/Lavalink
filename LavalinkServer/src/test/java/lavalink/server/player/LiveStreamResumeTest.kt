package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream
import lavalink.server.player.FakeRadio.Mode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

/**
 * The node fetches every HTTP source through an external proxy that closes each
 * tunnel ~121 s after it opens. A radio's body has no Content-Length and never
 * ends, so upstream lavaplayer read that cut as the end of the track (a clean
 * end of body: TrackEnd "finished"; a chunked body cut mid-chunk: an exception
 * it does not retry) and the client restarted the radio after ~2 s of silence,
 * every two minutes. The fork's PersistentHttpStream reopens the URL and
 * resumes right after the last byte it delivered, found in the new
 * connection's burst; whatever cannot be resumed byte for byte ends as before.
 */
class LiveStreamResumeTest {
    private val interfaces: HttpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()

    @BeforeEach
    fun fastCuts() {
        LiveStreamResume.reset()
        // The fake radio cuts after a few hundred milliseconds, not two minutes.
        LiveStreamResume.setMinConnectionMs(0)
        LiveStreamResume.setBackoffMs(20, 40)
    }

    @AfterEach
    fun tearDown() {
        LiveStreamResume.reset()
        interfaces.close()
    }

    @Test
    fun `a live stream whose connection is closed resumes right after the last byte it delivered`() {
        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000, gap = 3_000).use { radio ->
            val read = readUpTo(radio.url, 650_000)

            assertContiguous(read, 650_000)
            assertTrue(radio.requests.size >= 7, "cuts every 100 000 bytes, ${radio.requests.size} requests")
            // A live body has no byte positions: the reopen asks for the stream, not a range.
            radio.requests.drop(1).forEach { request ->
                assertNull(request.firstOrNull { it.startsWith("Range:", ignoreCase = true) }, "reopened with $request")
            }
        }
    }

    @Test
    fun `a chunked live stream cut in the middle of a chunk resumes the same way`() {
        FakeRadio(Mode.CHUNKED_CUT, burst = 64_000, cutAfter = 100_500, gap = 3_000).use { radio ->
            val read = readUpTo(radio.url, 650_000)

            assertContiguous(read, 650_000)
            assertTrue(radio.requests.size >= 7, "cuts every 100 500 bytes, ${radio.requests.size} requests")
        }
    }

    @Test
    fun `a body with a Content-Length still ends at its end`() {
        FakeRadio(Mode.CONTENT_LENGTH, burst = 64_000, cutAfter = 300_000).use { radio ->
            stream(radio.url).use { stream ->
                val read = readAll(stream)

                assertContiguous(read, 300_000)
                assertEquals(-1, stream.read())
            }
            assertEquals(1, radio.requests.size)
        }
    }

    @Test
    fun `a server that no longer has the bytes heard last ends the stream as before`() {
        // No burst: the new connection starts past the old one's last bytes. A real
        // radio keeps that connection open; this one would close it after 100 000 bytes,
        // so the scan stops first.
        LiveStreamResume.setMaxScanBytes(50_000)

        FakeRadio(Mode.CLOSE_DELIMITED, burst = 0, cutAfter = 100_000, gap = 3_000).use { radio ->
            stream(radio.url).use { stream ->
                val read = readAll(stream)

                assertContiguous(read, 100_000)
                // Still the end: no new request either.
                assertEquals(-1, stream.read())
                assertEquals(-1, stream.read(ByteArray(16), 0, 16))
            }
            assertEquals(2, radio.requests.size)
        }
    }

    @Test
    fun `a chunked stream that cannot be resumed fails as it did before`() {
        LiveStreamResume.setMaxScanBytes(50_000)

        FakeRadio(Mode.CHUNKED_CUT, burst = 0, cutAfter = 100_500, gap = 3_000).use { radio ->
            stream(radio.url).use { stream ->
                assertThrows(IOException::class.java) { readAll(stream) }
            }
            assertEquals(2, radio.requests.size)
        }
    }

    @Test
    fun `a connection that ends sooner than a live stream would is not reopened`() {
        LiveStreamResume.setMinConnectionMs(60_000)

        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000).use { radio ->
            stream(radio.url).use { stream ->
                assertContiguous(readAll(stream), 100_000)
            }
            assertEquals(1, radio.requests.size)
        }
    }

    @Test
    fun `a reopen the server refuses ends the stream`() {
        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000).use { radio ->
            radio.statusFor = { if (it == 0) 200 else 404 }

            stream(radio.url).use { stream ->
                assertContiguous(readAll(stream), 100_000)
            }
            assertEquals(2, radio.requests.size)
        }
    }

    @Test
    fun `a reopen that keeps failing is retried a few times, then ends the stream`() {
        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000).use { radio ->
            radio.statusFor = { if (it == 0) 200 else 503 }

            stream(radio.url).use { stream ->
                assertContiguous(readAll(stream), 100_000)
            }
            assertEquals(1 + LiveStreamResume.getAttempts(), radio.requests.size)
        }
    }

    @Test
    fun `with the resume off a live stream ends at its first cut, as upstream`() {
        LiveStreamResume.setEnabled(false)

        FakeRadio(Mode.CLOSE_DELIMITED, burst = 64_000, cutAfter = 100_000, gap = 3_000).use { radio ->
            stream(radio.url).use { stream ->
                assertContiguous(readAll(stream), 100_000)
            }
            assertEquals(1, radio.requests.size)
        }
    }

    private fun stream(url: String) =
        PersistentHttpStream(interfaces.`interface`, URI(url), Units.CONTENT_LENGTH_UNKNOWN)

    /** What the stream delivers until [limit] bytes or its end, the way a frame reader reads. */
    private fun readUpTo(url: String, limit: Int): ByteArray =
        stream(url).use { stream -> readAll(stream, limit) }

    private fun readAll(stream: PersistentHttpStream, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)

        while (out.size() < limit) {
            // Single bytes too: Mp3FrameReader scans for a frame header with read().
            if (out.size() % 3 == 0) {
                val b = stream.read()
                if (b < 0) break
                out.write(b)
                continue
            }

            val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }

        return out.toByteArray()
    }

    private fun assertContiguous(read: ByteArray, expected: Int) {
        assertEquals(expected, read.size, "bytes delivered")

        for (i in read.indices) {
            if (read[i] != liveRadioByte(i.toLong())) {
                throw AssertionError("byte $i is not the stream's byte $i: a rewind or a hole at the cut")
            }
        }
    }
}
