package lavalink.server.player

import io.netty.buffer.ByteBuf
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.internal.handler.DiscordUDPConnection
import moe.kyokobot.koe.poller.AbstractFramePoller
import moe.kyokobot.koe.poller.AbstractOpusFramePoller
import moe.kyokobot.koe.poller.FramePollerFactory
import moe.kyokobot.koe.poller.udpqueue.QueueManagerPool
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * What [GuardedUdpQueueFramePoller] needs from the audio sender of a
 * connection (LavalinkPlayer's provider). A sender without it is polled as
 * if it always had audio.
 */
interface SendPathListener {
    /**
     * Whether there is audio to send now: a track playing unpaused that has
     * not failed before its first frame. Must not pull a frame.
     */
    fun hasAudioToSend(): Boolean

    /** One 20 ms frame held back because the OS refuses UDP sends to the voice server. */
    fun onSendRefused()

    /**
     * The OS started refusing those sends ([refusedSince], epoch ms) or accepts
     * them again (null). Also called once with the poller's current state when
     * the poller first sees this listener.
     */
    fun onSendPathChanged(refusedSince: Long?)
}

/** Koe's UdpQueueFramePollerFactory with [GuardedUdpQueueFramePoller] for Opus. */
class GuardedUdpQueueFramePollerFactory(
    private val pool: QueueManagerPool,
    private val probe: UdpSendProbe,
) : FramePollerFactory {
    override fun createFramePoller(codec: CodecInstance, connection: MediaConnection): AbstractFramePoller? =
        if (codec.info is OpusCodecInfo) GuardedUdpQueueFramePoller(pool, probe, codec, connection) else null
}

/**
 * Koe's UdpQueueOpusFramePoller (same queue, same packets), plus one check
 * before a frame is pulled: whether the OS accepts UDP sends to the voice
 * server ([UdpSendProbe]).
 *
 * The udp-queue's native thread sends the packets and keeps a refused send to
 * itself, so a frame the OS refused still looked sent: during a media UDP
 * blackhole (iptables DROP on the voice server's /24) the node logged 3000
 * `[udpqueue] Error sending packet: Operation not permitted` a minute, reported
 * sentLastMinute=3000 / lossLastMinute=0, and the track went on for 75 s that
 * reached nobody. Clients take a moving position for audio going out.
 *
 * While the sends are refused this poller pulls nothing. The track stays where
 * its audio stopped, each held 20 ms is counted as a send failure, and nothing
 * more is queued to the native sender (its error line is bounded to the frames
 * already in flight when the refusal is seen). Accepted again within the
 * player cleanup threshold, the audio resumes where it stopped. Refused for
 * longer, lavaplayer stops the track with CLEANUP, as for a link nothing
 * pulls from, and the client runs its recovery for a dead voice link. A track
 * that failed before its first frame has no audio to hold: it is pulled as
 * without a refusal, so its end (LOAD_FAILED) comes at once.
 *
 * The refusal date is this poller's, so this connection's: a voice update
 * that replaces the connection replaces the poller, and the new one tells the
 * player its own state on its first poll.
 */
class GuardedUdpQueueFramePoller(
    private val pool: QueueManagerPool,
    probe: UdpSendProbe,
    codec: CodecInstance,
    connection: MediaConnection,
    /** Epoch ms. */
    private val clock: () -> Long = System::currentTimeMillis,
) : AbstractOpusFramePoller(connection, codec) {
    companion object {
        private val log = LoggerFactory.getLogger(GuardedUdpQueueFramePoller::class.java)
    }

    private val gate = SendPathGate(probe, clock)
    private var queue: QueueManagerPool.UdpQueueWrapper = pool.nextWrapper
    private var lastAddress: InetSocketAddress? = null
    /** The last listener told this poller's state. */
    private var toldListener: SendPathListener? = null

    override fun getPollsPerTick(): Int = queue.remainingCapacity

    override fun canSendFrame(): Boolean = connection.connectionHandler is DiscordUDPConnection

    override fun pollAndSend(): Boolean {
        val address = (connection.connectionHandler as? DiscordUDPConnection)?.serverAddress as? InetSocketAddress
            ?: return super.pollAndSend()
        val listener = resolveProvider() as? SendPathListener
        // The date is kept by the player, which outlives this poller and gets a
        // new sender on every play. Told only on a change, a new sender would
        // keep what the previous connection's poller said (a refusal that ended
        // with it, while the audio flows here), or never hear of the refusal in
        // progress. Each new listener gets this poller's state first.
        if (listener != null && listener !== toldListener) {
            toldListener = listener
            listener.onSendPathChanged(gate.refusedSince)
        }
        // Nothing to send, nothing to hold: an idle or paused player is not probed.
        if (listener != null && !listener.hasAudioToSend()) {
            // A hold ends with its track (stopped, paused, cleaned up): left set,
            // an idle player would read as refused for hours, and the next track
            // would carry the old date. That one asks the OS again at once.
            if (gate.refusedSince != null) {
                gate.reset()
                listener.onSendPathChanged(null)
            }
            return super.pollAndSend()
        }

        val refusedBefore = gate.refusedSince
        val accepted = gate.mayPull(address)
        if (gate.refusedSince != refusedBefore) onSendPathChanged(address, refusedBefore, listener)
        if (accepted) return super.pollAndSend()

        listener?.onSendRefused()
        return false
    }

    // One line per change, never per frame: a refusal is one warning, its end one info.
    private fun onSendPathChanged(address: InetSocketAddress, refusedBefore: Long?, listener: SendPathListener?) {
        val refusedSince = gate.refusedSince
        if (refusedSince != null) {
            log.warn(
                "Guild {}: the OS refuses UDP sends to the voice server {} ({}). Holding the audio until they are accepted again",
                connection.guildId, address, gate.lastRefusal?.message
            )
        } else if (refusedBefore != null) {
            log.info(
                "Guild {}: UDP sends to the voice server {} are accepted again after {} ms, the audio resumes where it stopped",
                connection.guildId, address, clock() - refusedBefore
            )
        }
        listener?.onSendPathChanged(refusedSince)
    }

    // Koe's UdpQueueOpusFramePoller.sendFramePayload, unchanged.
    override fun sendFramePayload(buf: ByteBuf, len: Int, timestamp: Int) {
        val handler = connection.connectionHandler as? DiscordUDPConnection ?: return
        val packet = handler.createPacket(codec.type, codec.payloadType, timestamp, buf, len, false) ?: return
        try {
            val address = handler.serverAddress as InetSocketAddress
            if (lastAddress != null && lastAddress != address) {
                queue = pool.nextWrapper
            }
            lastAddress = address
            queue.queuePacket(packet.nioBuffer(), address)
        } finally {
            packet.release()
        }
    }
}
