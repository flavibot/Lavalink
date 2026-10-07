package lavalink.server.player

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ProtocolFamily
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/**
 * Asks the OS whether it accepts a UDP send to an address, by sending it an
 * empty datagram.
 *
 * The native udp-queue sends the audio from its own thread and only prints a
 * refused send (`[udpqueue] Error sending packet: ...` on stderr): the result
 * never comes back to the JVM. Any socket of this network namespace gets the
 * same answer for the same destination, synchronously: EPERM from an OUTPUT
 * DROP rule or a network policy, ENETUNREACH or EHOSTUNREACH without a route,
 * EACCES for a broadcast address. A refused probe never leaves the host; an
 * accepted one is 28 bytes the voice server drops (an RTP packet has at least
 * a 12-byte header, and it comes from another port than the audio).
 *
 * What happens past the host (a router or the server dropping the packets)
 * makes no send fail, here or in the udp-queue: this sees local refusals only.
 *
 * Shared by every poller of the node; [DatagramChannel.send] is thread-safe.
 */
open class UdpSendProbe {
    companion object {
        private val log = LoggerFactory.getLogger(UdpSendProbe::class.java)
    }

    // One per family, like the udp-queue's own sockets (an explicit IPv4 one,
    // IPv6 optional): a dual-stack socket behaves differently where IPv6 is off.
    private val v4: DatagramChannel? by lazy { open(StandardProtocolFamily.INET) }
    private val v6: DatagramChannel? by lazy { open(StandardProtocolFamily.INET6) }

    /**
     * The OS's refusal of a send to [address], or null when it accepted it or
     * could not be asked. Doubt never counts as a refusal: a refusal holds the
     * audio, so only an error thrown by the send itself is one.
     */
    open fun refusal(address: InetSocketAddress): IOException? {
        if (address.isUnresolved) return null
        val channel = (if (address.address is Inet6Address) v6 else v4) ?: return null
        return try {
            // Non-blocking: 0 is returned both for this empty datagram and when
            // the socket buffer is full, neither of which is a refusal.
            channel.send(ByteBuffer.allocate(0), address)
            null
        } catch (e: ClosedChannelException) {
            null
        } catch (e: IOException) {
            e
        }
    }

    private fun open(family: ProtocolFamily): DatagramChannel? = try {
        DatagramChannel.open(family).apply { configureBlocking(false) }
    } catch (e: Exception) {
        log.warn("Cannot open a {} UDP socket to check that voice sends are accepted; those sends go unchecked", family, e)
        null
    }
}

/**
 * One frame poller's view of the path to its voice server. While the OS
 * accepts sends, one probe per [acceptedProbeIntervalMs] and one on every
 * change of server; once it refuses, one per poll (20 ms), so the audio
 * resumes as soon as the sends are accepted again. A refused probe never
 * reaches the wire, so probing that often costs no traffic.
 *
 * Not thread-safe: a poller runs on one event loop thread.
 */
class SendPathGate(
    private val probe: UdpSendProbe,
    /** Epoch ms. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val acceptedProbeIntervalMs: Long = 1_000,
) {
    /** Epoch ms of the first refused probe of the current refusal, null while sends are accepted. */
    var refusedSince: Long? = null
        private set

    /** What the OS answered to the last refused probe. */
    var lastRefusal: IOException? = null
        private set

    private var lastAddress: InetSocketAddress? = null
    private var lastProbeAt = 0L

    /** Whether a frame may be pulled for [address] now. */
    fun mayPull(address: InetSocketAddress): Boolean {
        val now = clock()
        // A clock that went back probes too (negative elapsed time).
        if (refusedSince == null && address == lastAddress && now - lastProbeAt in 0 until acceptedProbeIntervalMs) {
            return true
        }
        lastAddress = address
        lastProbeAt = now
        val refusal = probe.refusal(address)
        if (refusal == null) {
            refusedSince = null
            return true
        }
        lastRefusal = refusal
        if (refusedSince == null) refusedSince = now
        return false
    }
}
