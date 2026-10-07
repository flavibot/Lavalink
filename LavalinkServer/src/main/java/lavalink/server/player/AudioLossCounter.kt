/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.event.*

class AudioLossCounter(
    /** Epoch ms; a parameter so the minute buckets can be tested without waiting for minutes. */
    private val clock: () -> Long = System::currentTimeMillis,
) : AudioEventListener {
    companion object {
        const val EXPECTED_PACKET_COUNT_PER_MIN = 60 * 1000 / 20 // 20ms packets
        private const val ACCEPTABLE_TRACK_SWITCH_TIME = 100 //ms
    }

    private var playingSince = Long.MAX_VALUE
    private var lastTrackStarted = Long.MAX_VALUE / 2
    private var lastTrackEnded = Long.MAX_VALUE

    // Written by the frame poller (one thread), read by the stats and player
    // update threads: volatile is enough for statistics.
    @Volatile private var curMinute: Long = 0
    @Volatile private var curLoss = 0
    @Volatile private var curSucc = 0
    @Volatile private var curSendFailures = 0

    // The minute right before curMinute, zero when no frame was counted in it.
    @Volatile private var prevLoss = 0
    @Volatile private var prevSucc = 0
    @Volatile private var prevSendFailures = 0

    /** Polls that found no frame to send (the source ran dry), last whole minute. */
    val lastMinuteLoss: Int
        get() = lastWholeMinute(curLoss, prevLoss)

    /** Frames handed to the sender, last whole minute. */
    val lastMinuteSuccess: Int
        get() = lastWholeMinute(curSucc, prevSucc)

    /**
     * Frames held back because the OS refused UDP sends to the voice server
     * (FlaviBot fork, see GuardedUdpQueueFramePoller), last whole minute. Not
     * counted in [lastMinuteLoss]: that one is the source running dry, this one
     * the network path, and they call for different remedies.
     */
    val lastMinuteSendFailures: Int
        get() = lastWholeMinute(curSendFailures, prevSendFailures)

    fun onLoss() {
        checkTime()
        curLoss++
    }

    fun onSuccess() {
        checkTime()
        curSucc++
    }

    fun onSendFailure() {
        checkTime()
        curSendFailures++
    }

    val isDataUsable: Boolean
        get() {
            // Check that there isn't a significant gap in playback. If no track has ended yet, we can look past that
            if (lastTrackStarted - lastTrackEnded > ACCEPTABLE_TRACK_SWITCH_TIME && lastTrackEnded != Long.MAX_VALUE) {
                return false
            }

            // Check that we have at least stats for the last minute
            val lastMin = clock() / 60000 - 1
            return playingSince < lastMin * 60000
        }

    /**
     * Read against the clock rather than the last rollover: the buckets only
     * roll when a frame is counted, so a player nothing polls (voice down) kept
     * reporting the last minute it had polled, however long ago.
     */
    private fun lastWholeMinute(cur: Int, prev: Int): Int {
        val minute = clock() / 60000
        return when (curMinute) {
            minute -> prev
            minute - 1 -> cur
            else -> 0
        }
    }

    private fun checkTime() {
        val actualMinute = clock() / 60000
        if (curMinute != actualMinute) {
            // Only the minute right before this one is the last minute. After a
            // gap (voice down for minutes) the counted minute is older, and it
            // was reported as the last one once polling resumed: sent=1807 three
            // seconds after a rejoin that followed two minutes without voice.
            val contiguous = curMinute == actualMinute - 1
            prevLoss = if (contiguous) curLoss else 0
            prevSucc = if (contiguous) curSucc else 0
            prevSendFailures = if (contiguous) curSendFailures else 0
            curLoss = 0
            curSucc = 0
            curSendFailures = 0
            curMinute = actualMinute
        }
    }

    override fun onEvent(event: AudioEvent) {
        when (event) {
            is PlayerPauseEvent,
            is TrackEndEvent,
            -> lastTrackEnded = clock()

            is PlayerResumeEvent,
            is TrackStartEvent,
            -> {
                lastTrackStarted = clock()
                if (lastTrackStarted - lastTrackEnded > ACCEPTABLE_TRACK_SWITCH_TIME || playingSince == Long.MAX_VALUE) {
                    playingSince = clock()
                    lastTrackEnded = Long.MAX_VALUE
                }
            }
        }
    }

    override fun toString(): String = buildString {
        append("AudioLossCounter{")
        append("lastLoss=$lastMinuteLoss, ")
        append("lastSucc=$lastMinuteSuccess, ")
        append("lastSendFailures=$lastMinuteSendFailures, ")
        append("total=${lastMinuteSuccess + lastMinuteLoss + lastMinuteSendFailures}")
        append('}')
    }
}
