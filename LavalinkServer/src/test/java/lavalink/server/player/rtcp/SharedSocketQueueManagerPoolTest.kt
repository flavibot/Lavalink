package lavalink.server.player.rtcp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The native udp-queue on sockets the JVM opened (its `processWithSocket`,
 * which upstream says was never run from Java before).
 */
class SharedSocketQueueManagerPoolTest {
    private val receiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 5_000 }
    private val destination = receiver.localSocketAddress as InetSocketAddress
    private var pool: SharedSocketQueueManagerPool? = null

    @AfterEach
    fun tearDown() {
        pool?.close()
        receiver.close()
    }

    private fun pool(size: Int) = SharedSocketQueueManagerPool.createOrNull(size, 400, RtcpRouter())
        .also { assertNotNull(it, "the test JVM opens java.base/sun.nio.ch like the jar does") }!!
        .also { pool = it }

    @Test
    fun `the native sender sends from the pool's sockets, one queue paced at 20 ms`() {
        val pool = pool(2)
        val queue = pool.getNextWrapper()
        repeat(10) {
            assertTrue(queue.queuePacket(ByteBuffer.allocateDirect(4).putInt(0, it), destination), "packet $it queued")
        }

        val buf = ByteArray(64)
        val started = System.nanoTime()
        val sources = (0 until 10).map {
            val datagram = DatagramPacket(buf, buf.size)
            receiver.receive(datagram)
            assertEquals(it, ByteBuffer.wrap(datagram.data, 0, 4).int, "packets in order")
            (datagram.socketAddress as InetSocketAddress).port
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(1, sources.toSet().size, "one queue, one socket")
        assertTrue(sources.first() in pool.localPorts, "sent from ${sources.first()}, the pool's ports are ${pool.localPorts}")
        assertTrue(elapsedMs >= 150, "10 packets paced at 20 ms took $elapsedMs ms")
    }

    @Test
    fun `queues are spread over the managers and their sockets`() {
        val pool = pool(2)
        val first = pool.getNextWrapper()
        val second = pool.getNextWrapper()
        first.queuePacket(ByteBuffer.allocateDirect(1), destination)
        second.queuePacket(ByteBuffer.allocateDirect(1), destination)

        val ports = (0 until 2).map {
            val datagram = DatagramPacket(ByteArray(8), 8)
            receiver.receive(datagram)
            datagram.port
        }.toSet()

        assertEquals(2, ports.size, "two managers, two sockets: $ports")
        assertEquals(4, pool.localPorts.size, "an IPv4 and an IPv6 socket per manager")
    }

    @Test
    fun `the sockets stay blocking, as the native sender's own`() {
        val fdinfo = java.io.File("/proc/self/fdinfo")
        org.junit.jupiter.api.Assumptions.assumeTrue(fdinfo.isDirectory, "Linux only")
        val pool = pool(1)
        // A reader is blocked in receive() on each of them by now.
        Thread.sleep(100)

        for (fd in pool.descriptors) {
            val flags = java.io.File(fdinfo, fd.toString()).readLines().first { it.startsWith("flags:") }
                .substringAfter(":").trim().toInt(8)
            assertEquals(0, flags and 0x800, "O_NONBLOCK on descriptor $fd (flags ${flags.toString(8)})")
        }
    }

    @Test
    fun `close stops the senders and then closes the sockets`() {
        val pool = pool(1)
        val ports = pool.localPorts
        val queue = pool.getNextWrapper()

        pool.close()

        assertFalse(queue.queuePacket(ByteBuffer.allocateDirect(1), destination), "a closed manager queues nothing")
        // The ports are free again: the sockets were closed after the sender gave them back.
        DatagramSocket(ports.first(), InetAddress.getByName("0.0.0.0")).close()
    }
}
