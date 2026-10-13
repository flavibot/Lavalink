package lavalink.server.player

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A live radio on 127.0.0.1, as Icecast serves one: every listener gets a burst
 * of the last [burst] bytes before the live edge, then the live bytes, and the
 * body has no Content-Length. Unlike a real radio, each connection is cut after
 * [cutAfter] bytes, like the node's HTTP proxy cutting every tunnel at ~121 s,
 * and the live edge moves [gap] bytes on while nobody listens (the time a
 * reconnect takes). The byte at absolute position p is always `content(p)`, so
 * a reader can tell a seamless resume from a rewind or a hole.
 */
class FakeRadio(
    private val mode: Mode,
    private val burst: Int,
    private val cutAfter: Int,
    private val gap: Int = 0,
    private val content: (Long) -> Byte = { liveRadioByte(it) },
) : AutoCloseable {
    enum class Mode {
        /** No Content-Length, no chunking: the body ends when the socket closes (a clean EOF). */
        CLOSE_DELIMITED,
        /** Chunked, the socket closed in the middle of a chunk (an IOException for the reader). */
        CHUNKED_CUT,
        /** A finite file of [cutAfter] bytes with its Content-Length. */
        CONTENT_LENGTH,
        /** Like CLOSE_DELIMITED, but the connection stalls (open, silent) instead of closing. */
        STALL,
    }

    /** The header lines of every request received, in order. */
    val requests = CopyOnWriteArrayList<List<String>>()

    /** The status answered to the request at that index (0 = the first). */
    @Volatile
    var statusFor: (Int) -> Int = { 200 }

    @Volatile
    private var edge = 0L
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(name = "fake-radio", isDaemon = true) { acceptLoop() }

    val url: String get() = "http://127.0.0.1:${server.localPort}/live.mp3"

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                return
            }
            thread(name = "fake-radio-listener", isDaemon = true) {
                try {
                    socket.use { serve(it) }
                } catch (e: IOException) {
                    // The listener hung up first (a probe that read the head only).
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val lines = mutableListOf<String>()

        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            lines += line
        }

        val index = synchronized(requests) {
            requests += lines
            requests.size - 1
        }
        val out = socket.getOutputStream()
        val status = statusFor(index)

        if (status != 200) {
            out.write("HTTP/1.1 $status Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        when (mode) {
            Mode.CONTENT_LENGTH -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: $cutAfter\r\nConnection: close\r\n\r\n".toByteArray())
                writeRange(out, 0, cutAfter.toLong())
            }

            Mode.CLOSE_DELIMITED, Mode.STALL -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nicy-name: fake\r\nConnection: close\r\n\r\n".toByteArray())
                writeRange(out, nextStart(), cutAfter.toLong())

                if (mode == Mode.STALL) {
                    out.flush()
                    Thread.sleep(STALL_MS)
                }
            }

            Mode.CHUNKED_CUT -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                var position = nextStart()
                var left = cutAfter.toLong()

                while (left > 0) {
                    val size = minOf(CHUNK, left.toInt())
                    // The cut: the last chunk announces CHUNK bytes but carries fewer.
                    out.write("${Integer.toHexString(CHUNK)}\r\n".toByteArray())
                    writeRange(out, position, size.toLong())
                    if (size == CHUNK) out.write("\r\n".toByteArray())
                    position += size
                    left -= size
                }
            }
        }

        out.flush()
    }

    /** Where a new listener starts: the burst before the edge, which moved on by [gap]. */
    private fun nextStart(): Long = synchronized(this) {
        if (edge > 0) edge += gap
        val start = maxOf(0L, edge - burst)
        edge = start + cutAfter
        start
    }

    private fun writeRange(out: OutputStream, start: Long, length: Long) {
        val buffer = ByteArray(8192)
        var position = start
        val end = start + length

        while (position < end) {
            val n = minOf(buffer.size.toLong(), end - position).toInt()
            for (i in 0 until n) buffer[i] = content(position + i)
            out.write(buffer, 0, n)
            position += n
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()

        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    override fun close() {
        server.close()
        acceptor.join(1000)
    }

    private companion object {
        const val CHUNK = 1000
        const val STALL_MS = 3000L
    }
}

/** The fake radio's byte at [position]: splitmix64, no short period, so 4 KiB never match elsewhere. */
fun liveRadioByte(position: Long): Byte {
    var z = position + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return (z xor (z ushr 31)).toByte()
}
