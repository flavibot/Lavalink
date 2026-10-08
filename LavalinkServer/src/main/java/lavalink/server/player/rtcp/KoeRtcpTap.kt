package lavalink.server.player.rtcp

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

/**
 * Reads the RTCP arriving on the UDP socket Koe opened for a media connection
 * (the one it ran IP discovery on and announced in SELECT_PROTOCOL).
 *
 * Koe builds that socket's pipeline privately and ends it with an `RTCPHandler`
 * that drops every packet, so the handler goes in front of it, and passes every
 * datagram on unchanged.
 */
object KoeRtcpTap {
    private val log = LoggerFactory.getLogger(KoeRtcpTap::class.java)
    private const val KOE_RTCP_HANDLER = "rtcp"
    const val HANDLER_NAME = "lavalink-rtcp"

    // Koe keeps the channel private, the pinned Koe version is the one this reads.
    private val channelField: Field? = try {
        DiscordUDPConnection::class.java.getDeclaredField("channel").apply { isAccessible = true }
    } catch (e: Exception) {
        log.warn("Cannot read Koe's media socket, RTCP reports will not be read", e)
        null
    }

    fun channelOf(udp: DiscordUDPConnection): Channel? = channelField?.get(udp) as? Channel

    /**
     * Feeds [receiver] from now on. A second call for the same connection (a
     * repeated SESSION_DESCRIPTION) replaces the handler: the player now reads
     * the new receiver, and the old one would keep the reports to itself.
     *
     * @return false when Koe's socket or its RTCP handler is not there (closed, or another Koe).
     */
    fun attach(udp: DiscordUDPConnection, receiver: RtcpReceiver): Boolean {
        val pipeline = channelOf(udp)?.pipeline() ?: return false
        val handler = Handler(udp, receiver)
        return try {
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.replace(HANDLER_NAME, HANDLER_NAME, handler)
            } else {
                pipeline.addBefore(KOE_RTCP_HANDLER, HANDLER_NAME, handler)
            }
            true
        } catch (e: NoSuchElementException) {
            false
        }
    }

    private class Handler(private val udp: DiscordUDPConnection, private val receiver: RtcpReceiver) :
        ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is DatagramPacket) {
                val content = msg.content()
                val length = content.readableBytes()
                if (length >= RtcpParser.HEADER_LENGTH) {
                    val bytes = ByteArray(length)
                    content.getBytes(content.readerIndex(), bytes)
                    receiver.onDatagram(
                        udp.encryptionMode?.name,
                        udp.secretKey,
                        Integer.toUnsignedLong(udp.ssrc),
                        bytes, 0, length
                    )
                }
            }
            ctx.fireChannelRead(msg)
        }
    }
}
