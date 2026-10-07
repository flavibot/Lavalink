package lavalink.server.player.rtcp

import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hands the datagrams read on the udp-queue's sockets to the media connection
 * they are for. One such socket sends the audio of many players, so Discord's
 * reports for all of them arrive on it: the voice server they come from narrows
 * the candidates, the clear sender SSRC picks the connection (Discord puts our
 * own SSRC there, seen on dev), the transport key confirms it (only the right
 * key authenticates the packet), and [RtcpReceiver] keeps the blocks about its SSRC.
 */
class RtcpRouter {
    companion object {
        /** The router of the server's udp-queue pool (KoeConfiguration) and players. */
        val shared = RtcpRouter()
    }

    inner class Registration internal constructor(
        val server: InetSocketAddress,
        val udp: DiscordUDPConnection,
        val receiver: RtcpReceiver,
    ) : AutoCloseable {
        override fun close() {
            byServer.computeIfPresent(server) { _, list -> list.apply { remove(this@Registration) }.takeIf { it.isNotEmpty() } }
        }
    }

    private val byServer = ConcurrentHashMap<InetSocketAddress, CopyOnWriteArrayList<Registration>>()

    /** Routes the reports of the voice server [udp] sends to into [receiver] until the registration is closed. */
    fun register(udp: DiscordUDPConnection, receiver: RtcpReceiver): Registration? {
        val server = udp.serverAddress as? InetSocketAddress ?: return null
        val registration = Registration(server, udp, receiver)
        byServer.compute(server) { _, list -> (list ?: CopyOnWriteArrayList()).apply { add(registration) } }
        return registration
    }

    /** @return whether a registered connection could open the datagram */
    fun onDatagram(from: InetSocketAddress, data: ByteArray, offset: Int, length: Int): Boolean {
        val candidates = byServer[from] ?: return false
        if (!RtcpParser.isRtcp(data, offset, length)) return false
        // The connection whose SSRC is the clear sender first: one decryption per
        // report instead of one per connection on this voice server, each wrong key
        // costing a failed tag check, all under the receivers' locks that Koe's
        // event loop takes too. The others after it, should a voice server put its
        // own SSRC there as RFC 3550 says: then only the keys tell them apart.
        val sender = RtcpParser.senderSsrc(data, offset)
        val owner = candidates.firstOrNull { it.ssrc == sender }
        if (owner != null && owner.open(data, offset, length)) return true
        for (registration in candidates) {
            if (registration !== owner && registration.open(data, offset, length)) return true
        }
        return false
    }

    private val Registration.ssrc get() = Integer.toUnsignedLong(udp.ssrc)

    private fun Registration.open(data: ByteArray, offset: Int, length: Int) =
        receiver.onDatagram(udp.encryptionMode?.name, udp.secretKey, ssrc, data, offset, length)

    internal fun registrations(): Int = byServer.values.sumOf { it.size }
}
