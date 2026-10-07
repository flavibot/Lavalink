package lavalink.server.player.rtcp

import com.sedmelluq.discord.lavaplayer.udpqueue.natives.UdpQueueManager
import com.sedmelluq.discord.lavaplayer.udpqueue.natives.UdpQueueManagerLibrary
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.poller.udpqueue.QueueManagerPool
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProtocolFamily
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Koe's udp-queue pool, except that the sockets the native sender sends the
 * audio from are opened by the JVM, which also reads them.
 *
 * Discord sends its RTCP receiver reports (packet loss, jitter) to the address
 * the RTP comes from. Koe's own pool lets the native sender bind its sockets,
 * and nothing ever reads them: from the first frame of a connection on, the
 * reports pile up in a socket buffer and are dropped (seen on dev: about one a
 * second per connection, playing or not, the receive queue full at 212 KB).
 * Here every manager runs on two sockets of ours (IPv4 and IPv6, as the native
 * sender binds), handed over with the udp-queue's `processWithSocket`, and one
 * thread per socket reads what comes back into [RtcpRouter].
 *
 * The sockets stay blocking, so the native `send_to` behaves exactly as on its
 * own sockets. They live as long as the pool: the native sender never sees a
 * closed descriptor whose number could be reused.
 */
class SharedSocketQueueManagerPool private constructor(
    size: Int,
    bufferDuration: Int,
    private val sockets: List<Pair<DatagramChannel, DatagramChannel?>>,
    private val router: RtcpRouter,
) : QueueManagerPool(1, bufferDuration) {
    companion object {
        private val log = LoggerFactory.getLogger(SharedSocketQueueManagerPool::class.java)
        private const val MAX_DATAGRAM = 2048

        /**
         * @return the pool, or null when this JVM cannot hand its sockets to the
         * native sender: not a Unix, or `java.base/sun.nio.ch` not opened to
         * read a socket's descriptor (the server jar's manifest opens it for
         * `java -jar`). The caller then keeps Koe's pool, without the reports.
         */
        fun createOrNull(
            size: Int,
            bufferDuration: Int,
            router: RtcpRouter = RtcpRouter.shared,
        ): SharedSocketQueueManagerPool? {
            require(size > 0) { "Pool size must be higher or equal to 1." }
            if (System.getProperty("os.name").startsWith("Windows")) return null
            val opened = mutableListOf<DatagramChannel>()
            return try {
                val sockets = List(size) {
                    val v4 = open(StandardProtocolFamily.INET).also { opened += it }
                    // IPv6 is optional for the native sender too (voice servers are IPv4).
                    val v6 = try {
                        open(StandardProtocolFamily.INET6).also { opened += it }
                    } catch (e: Exception) {
                        null
                    }
                    v4 to v6
                }
                opened.forEach { SocketDescriptors.of(it) }
                SharedSocketQueueManagerPool(size, bufferDuration, sockets, router)
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                log.warn("Cannot share the udp-queue's sockets ({}), RTCP reports will not be read during playback", e.toString())
                null
            }
        }

        private fun open(family: ProtocolFamily): DatagramChannel =
            DatagramChannel.open(family).apply {
                bind(InetSocketAddress(if (family == StandardProtocolFamily.INET) "0.0.0.0" else "::", 0))
            }
    }

    private val keySeq = AtomicLong()
    private val managers: List<SharedSocketQueueManager>
    private val threads = mutableListOf<Thread>()
    private val readers = mutableListOf<Thread>()

    @Volatile
    private var closed = false

    init {
        // QueueManagerPool has no constructor without managers: the one it just
        // started (on the native sender's own sockets) is never used. The native
        // destroy waits for that manager's loop to end, even one whose thread has
        // not entered it yet (it then sees the shutdown and returns at once).
        super.close()

        managers = sockets.mapIndexed { i, (v4, v6) ->
            val manager = SharedSocketQueueManager(
                bufferDuration / OpusCodecInfo.FRAME_DURATION,
                TimeUnit.MILLISECONDS.toNanos(OpusCodecInfo.FRAME_DURATION.toLong()),
                QueueManagerPool.MAXIMUM_PACKET_SIZE,
                SocketDescriptors.of(v4),
                v6?.let { SocketDescriptors.of(it) } ?: 0,
            )
            threads += Thread(manager::process, "QueueManagerPool-$i").apply {
                // As Koe's pool: above normal, the sender paces the audio.
                priority = (Thread.NORM_PRIORITY + Thread.MAX_PRIORITY) / 2
                isDaemon = true
                start()
            }
            reader(v4, "rtcp-reader-$i-v4")
            v6?.let { reader(it, "rtcp-reader-$i-v6") }
            manager
        }
        log.info("The udp-queue sends from {} socket pairs the server reads, for Discord's RTCP reports", managers.size)
    }

    /** The descriptors handed to the native sender, IPv4 then IPv6 per manager. */
    internal val descriptors: List<Int>
        get() = sockets.flatMap { (v4, v6) -> listOfNotNull(v4, v6) }.map { SocketDescriptors.of(it) }

    /** Local ports the audio leaves from, IPv4 then IPv6 per manager: for tests and logs. */
    val localPorts: List<Int>
        get() = sockets.flatMap { (v4, v6) -> listOfNotNull(v4, v6) }.map { (it.localAddress as InetSocketAddress).port }

    override fun getNextWrapper(): QueueManagerPool.UdpQueueWrapper = getWrapperForKey(keySeq.getAndIncrement())

    override fun getWrapperForKey(queueKey: Long): QueueManagerPool.UdpQueueWrapper =
        QueueManagerPool.UdpQueueWrapper(managers[Math.floorMod(queueKey, managers.size.toLong()).toInt()], queueKey)

    override fun close() {
        if (closed) return
        closed = true
        managers.forEach { it.close() }
        // The native sender gives the descriptors back when its loop ends; only
        // then can they be closed without it ever writing to a reused number.
        val ended = threads.all { it.join(2_000); !it.isAlive }
        if (!ended) {
            log.warn("A udp-queue sender did not stop within 2 s, its sockets are left open")
            return
        }
        sockets.forEach { (v4, v6) ->
            runCatching { v4.close() }
            runCatching { v6?.close() }
        }
        // A NIO socket a thread is blocked on is released when that thread leaves it.
        readers.forEach { it.join(1_000) }
    }

    private fun reader(channel: DatagramChannel, name: String) {
        Thread({
            val buffer = ByteBuffer.allocate(MAX_DATAGRAM)
            while (true) {
                try {
                    buffer.clear()
                    val from = channel.receive(buffer) as? InetSocketAddress ?: continue
                    buffer.flip()
                    router.onDatagram(from, buffer.array(), 0, buffer.limit())
                } catch (e: ClosedChannelException) {
                    return@Thread
                } catch (e: IOException) {
                    if (!channel.isOpen) return@Thread
                    log.debug("Reading a udp-queue socket failed", e)
                } catch (e: Exception) {
                    log.warn("Dropped a datagram from a udp-queue socket", e)
                }
            }
        }, name).apply {
            isDaemon = true
            start()
            readers += this
        }
    }
}

/** A udp-queue manager whose native sender runs on sockets the JVM opened. */
internal class SharedSocketQueueManager(
    bufferCapacity: Int,
    packetInterval: Long,
    maximumPacketSize: Int,
    private val v4: Int,
    private val v6: Int,
) : UdpQueueManager(bufferCapacity, packetInterval, maximumPacketSize) {
    companion object {
        // UdpQueueManager keeps its native handle private and only offers process(),
        // which binds sockets of its own; processWithSocket needs that handle.
        private val instanceField = UdpQueueManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    }

    private val handle = instanceField.getLong(this)

    override fun process() {
        UdpQueueManagerLibrary.getInstance().processWithSocket(handle, v4.toLong(), v6.toLong())
    }
}

/**
 * The descriptor number of a NIO socket, to hand to native code. The JDK keeps
 * it internal (`sun.nio.ch`): readable when that package is opened to us.
 */
internal object SocketDescriptors {
    fun of(channel: DatagramChannel): Int {
        val type = channel.javaClass
        val value = try {
            type.getMethod("getFDVal").apply { isAccessible = true }.invoke(channel)
        } catch (e: NoSuchMethodException) {
            type.getDeclaredField("fdVal").apply { isAccessible = true }.get(channel)
        }
        val fd = value as Int
        check(fd > 0) { "invalid descriptor $fd" }
        return fd
    }
}
