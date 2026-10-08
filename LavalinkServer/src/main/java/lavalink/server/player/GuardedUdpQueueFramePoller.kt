package lavalink.server.player

import io.netty.buffer.ByteBuf
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.codec.OpusCodecInfo
import moe.kyokobot.koe.internal.MediaConnectionImpl
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

    /**
     * Pull one 20 ms frame and drop it: the bot is not admitted to the call's
     * end-to-end encryption, so nothing may be sent, but the track goes on. A
     * sender without it is held instead (its track does not advance).
     */
    fun drainHeldFrame() {}

    /**
     * A tick with nothing to send (paused, no track, or a track that failed
     * before its first frame) while the bot is not admitted to the call's
     * end-to-end encryption: ask lavaplayer for a frame anyway, as Koe does on
     * every tick once admitted. Paused or trackless, that pulls nothing but
     * keeps the player from lavaplayer's cleanup; a failed track gives its end
     * marker, so its end is reported. Never a frame of audio.
     */
    fun keepAlive() {}

    /**
     * The poller started holding the audio for end-to-end encryption
     * ([waitingSince], epoch ms) or sends it again (null). Also called once with
     * the poller's current state when the poller first sees this listener.
     */
    fun onE2EEWaitChanged(waitingSince: Long?) {}
}

/** How long a connection may wait for its end-to-end encryption before its track goes on without sending. */
const val E2EE_HOLD_GRACE_MS = 3_000L

/**
 * Koe's own gate (AbstractOpusFramePoller.isE2EEReady): a connection without
 * DAVE, or whose sender key ratchet is in place, may send.
 */
fun koeE2EEReady(connection: MediaConnection): Boolean =
    (connection as? MediaConnectionImpl)?.getDAVEManager()?.isReadyToSend ?: true

/** Koe's UdpQueueFramePollerFactory with [GuardedUdpQueueFramePoller] for Opus. */
class GuardedUdpQueueFramePollerFactory(
    private val pool: QueueManagerPool,
    private val probe: UdpSendProbe,
    private val e2eeReady: (MediaConnection) -> Boolean = ::koeE2EEReady,
    private val e2eeGraceMs: Long = E2EE_HOLD_GRACE_MS,
) : FramePollerFactory {
    override fun createFramePoller(codec: CodecInstance, connection: MediaConnection): AbstractFramePoller? =
        if (codec.info is OpusCodecInfo) {
            GuardedUdpQueueFramePoller(pool, probe, codec, connection, e2eeReady = e2eeReady, e2eeGraceMs = e2eeGraceMs)
        } else {
            null
        }
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
 *
 * Second check, end-to-end encryption: Koe 3.1 sends nothing until the call's
 * DAVE group has admitted the bot (its sender key ratchet), and pulls nothing
 * either. A bot nobody admits had its track frozen at 0 until lavaplayer's
 * cleanup, which the client took for a dead voice link and rejoined, again and
 * again. Past [e2eeGraceMs] (a group is normally joined within a second, and a
 * frame pulled before it would be lost from the start of the song) the track
 * goes on: one frame pulled and dropped per 20 ms tick, never sent. Plaintext
 * is no way out: clients drop it in an encrypted call, and sending it was
 * Koe's GHSA-pw4q-x846-j45m. Once admitted, the audio is sent from wherever
 * the track is.
 */
class GuardedUdpQueueFramePoller(
    private val pool: QueueManagerPool,
    probe: UdpSendProbe,
    codec: CodecInstance,
    connection: MediaConnection,
    /** Epoch ms. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val e2eeReady: (MediaConnection) -> Boolean = ::koeE2EEReady,
    private val e2eeGraceMs: Long = E2EE_HOLD_GRACE_MS,
) : AbstractOpusFramePoller(connection, codec) {
    companion object {
        private val log = LoggerFactory.getLogger(GuardedUdpQueueFramePoller::class.java)
    }

    private val gate = SendPathGate(probe, clock)
    private var queue: QueueManagerPool.UdpQueueWrapper = pool.nextWrapper
    private var lastAddress: InetSocketAddress? = null
    /** The last listener told this poller's state. */
    private var toldListener: SendPathListener? = null
    /** Epoch ms of the first poll with audio to send that found the encryption not ready; null once ready. */
    private var e2eeWaitSeenAt: Long? = null
    /** Epoch ms since which the track goes on without sending (the grace is over); null otherwise. */
    private var e2eeWaitingSince: Long? = null

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
            listener.onE2EEWaitChanged(e2eeWaitingSince)
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
            // Still not admitted, the wait goes on through a pause or between
            // two tracks: the next one is not held for another grace. Koe pulls
            // nothing until admitted, not even a paused player's provide(), and
            // lavaplayer would clean a track paused for a minute up.
            if (!e2eeReady(connection)) {
                listener.keepAlive()
                return false
            }
            if (e2eeWaitSeenAt != null) endE2EEWait(listener)
            return super.pollAndSend()
        }

        if (listener != null && holdForE2EE(listener)) return false

        val refusedBefore = gate.refusedSince
        val accepted = gate.mayPull(address)
        if (gate.refusedSince != refusedBefore) onSendPathChanged(address, refusedBefore, listener)
        if (accepted) return super.pollAndSend()

        listener?.onSendRefused()
        return false
    }

    /** Whether this tick is held for end-to-end encryption (a frame drained past the grace). */
    private fun holdForE2EE(listener: SendPathListener): Boolean {
        if (e2eeReady(connection)) {
            if (e2eeWaitSeenAt != null) endE2EEWait(listener)
            return false
        }
        val now = clock()
        val seenAt = e2eeWaitSeenAt ?: now.also { e2eeWaitSeenAt = it }
        if (now - seenAt < e2eeGraceMs) return true
        if (e2eeWaitingSince == null) {
            e2eeWaitingSince = now
            log.warn(
                "Guild {}: not admitted to the call's end-to-end encryption after {} ms. The track goes on without sending audio until Discord admits the bot",
                connection.guildId, now - seenAt
            )
            listener.onE2EEWaitChanged(now)
        }
        listener.drainHeldFrame()
        return true
    }

    private fun endE2EEWait(listener: SendPathListener) {
        val since = e2eeWaitingSince
        e2eeWaitSeenAt = null
        e2eeWaitingSince = null
        if (since != null) {
            log.info(
                "Guild {}: admitted to the call's end-to-end encryption after {} ms without sending, the audio is sent again",
                connection.guildId, clock() - since
            )
            listener.onE2EEWaitChanged(null)
        }
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
