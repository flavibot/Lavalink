package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.format.transcoder.OpusChunkEncoder
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusDecoder
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real libopus path: 1 s of a 440 Hz sine and 1 s of silence, encoded to 50 Opus frames
 * each, played through two decks and the real [OpusFrameCodec]; the output stream is decoded
 * with one decoder and the loudness of each mixed frame is compared with the linear ramp.
 * Skipped (not failed) when the natives do not load on this platform.
 */
class OpusFrameCodecTest {
    private val format = StandardAudioDataFormats.DISCORD_OPUS
    private val total = format.totalSampleCount()

    private fun nativesLoad(): Boolean = runCatching { OpusDecoder(48000, 2).close() }.isSuccess

    private fun encode(samples: (Int) -> Short): List<ByteArray> {
        val encoder = OpusChunkEncoder(AudioConfiguration(), format)
        val pcm = ByteBuffer.allocateDirect(total * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        return try {
            List(50) { frame ->
                pcm.clear()
                for (i in 0 until format.chunkSampleCount) {
                    val s = samples(frame * format.chunkSampleCount + i)
                    pcm.put(i * 2, s)
                    pcm.put(i * 2 + 1, s)
                }
                encoder.encode(pcm)
            }
        } finally {
            encoder.close()
        }
    }

    private fun sine(): List<ByteArray> = encode { n -> (12000 * sin(2 * PI * 440 * n / 48000.0)).toInt().toShort() }

    private fun silence(): List<ByteArray> = encode { 0 }

    private class Result(val rms: List<Double>, val firstMixed: Int, val n: Int, val micros: CrossfadeAudioPlayer.State)

    private fun crossfade(a: List<ByteArray>, b: List<ByteArray>, fadeMs: Long): Result {
        val decks = mutableListOf<FakeDeck>()
        val player = CrossfadeAudioPlayer(
            deckFactory = { FakeDeck("deck${decks.size}", format).also { decks.add(it) } },
            codecFactory = { OpusFrameCodec(format, 10) },
            outputFormat = format,
            isReady = { true },
        )
        player.playTrack(FakeTrack("A", a))
        player.arm(FakeTrack("B", b), fadeMs)
        val target = MutableAudioFrame(ByteBuffer.allocate(format.maximumChunkSize()))

        val decoder = OpusDecoder(48000, 2)
        val input = ByteBuffer.allocateDirect(4096)
        val pcm = ByteBuffer.allocateDirect(total * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        val rms = mutableListOf<Double>()
        var firstMixed = -1
        try {
            for (poll in 0 until 80) { // 50 polls of A (30 passthrough + 20 mixed), then 30 of B
                val mixedBefore = player.counters.mixedFrames.get()
                assertTrue(player.provide(target), "poll $poll")
                if (firstMixed < 0 && player.counters.mixedFrames.get() > mixedBefore) firstMixed = poll
                input.clear()
                input.put(target.data)
                input.flip()
                val count = decoder.decode(input, pcm) * 2
                rms.add(rms(pcm, count))
            }
        } finally {
            decoder.close()
        }
        val state = player.state()
        player.destroy()
        return Result(rms, firstMixed, player.counters.mixedFrames.get().toInt(), state)
    }

    private fun rms(pcm: ShortBuffer, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm.get(i).toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    @Test
    fun `a sine fading out into silence falls along the linear ramp`() {
        assumeTrue(nativesLoad(), "Opus natives do not load here")
        val r = crossfade(sine(), silence(), 400)
        assertEquals(20, r.n)
        assertEquals(30, r.firstMixed, "1000 ms track, 400 ms fade: polls 0..29 are A")
        val rmsA = r.rms.subList(10, 29).average()
        for (k in 0 until r.n) {
            val expected = rmsA * (1 - (k + 0.5) / r.n)
            val actual = r.rms[r.firstMixed + k]
            assertTrue(abs(actual - expected) <= 0.15 * rmsA, "frame $k: expected %.0f, got %.0f (A %.0f)".format(expected, actual, rmsA))
        }
        println(
            "opus fade-out: rmsA=%.0f mixed=%s, mix cost per frame: avg %d us, max %d us".format(
                rmsA, r.rms.subList(r.firstMixed, r.firstMixed + r.n).map { it.toInt() },
                r.micros.mixAvgMicros, r.micros.mixMaxMicros,
            )
        )
    }

    @Test
    fun `silence fading into a sine rises along the linear ramp`() {
        assumeTrue(nativesLoad(), "Opus natives do not load here")
        val r = crossfade(silence(), sine(), 400)
        assertEquals(20, r.n)
        assertEquals(30, r.firstMixed)
        val rmsB = r.rms.subList(r.firstMixed + r.n + 2, r.firstMixed + r.n + 20).average()
        for (k in 0 until r.n) {
            val expected = rmsB * (k + 0.5) / r.n
            val actual = r.rms[r.firstMixed + k]
            assertTrue(abs(actual - expected) <= 0.15 * rmsB, "frame $k: expected %.0f, got %.0f (B %.0f)".format(expected, actual, rmsB))
        }
        println(
            "opus fade-in: rmsB=%.0f mixed=%s, mix cost per frame: avg %d us, max %d us".format(
                rmsB, r.rms.subList(r.firstMixed, r.firstMixed + r.n).map { it.toInt() },
                r.micros.mixAvgMicros, r.micros.mixMaxMicros,
            )
        )
    }
}
