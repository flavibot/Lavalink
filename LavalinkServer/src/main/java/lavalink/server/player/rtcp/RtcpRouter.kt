package lavalink.server.player.rtcp

import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hands the datagrams read on the udp-queue's sockets to the media connection
 * they are for. One such socket sends the audio of many players, so Discord's
 * reports for all of them arrive on it: the voice server they come from narrows
 * the candidates, the transport key picks the connection (only the right key
 * authenticates the packet), and [RtcpReceiver] keeps the blocks about its SSRC.
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
        for (registration in candidates) {
            val udp = registration.udp
            val opened = registration.receiver.onDatagram(
                udp.encryptionMode?.name,
                udp.secretKey,
                Integer.toUnsignedLong(udp.ssrc),
                data, offset, length
            )
            if (opened) return true
        }
        return false
    }

    internal fun registrations(): Int = byServer.values.sumOf { it.size }
}
