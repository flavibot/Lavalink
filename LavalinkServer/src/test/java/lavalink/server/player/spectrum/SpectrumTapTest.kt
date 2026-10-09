package lavalink.server.player.spectrum

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.format.transcoder.OpusChunkEncoder
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusDecoder
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The spectrum of what a player sends (SpectrumTap): real Opus frames of a
 * sine and of silence through libopus, the FFT and the bands. Skipped (not
 * failed) when the natives do not load on this platform, like the codec test.
 */
class SpectrumTapTest {
    private val format = StandardAudioDataFormats.DISCORD_OPUS

    private fun nativesLoad(): Boolean = runCatching { OpusDecoder(48000, 2).close() }.isSuccess

    /** One Opus frame of a stereo signal. */
    private fun frame(samples: (Int) -> Short): ByteArray {
        val encoder = OpusChunkEncoder(AudioConfiguration(), format)
        val pcm = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        return try {
            for (i in 0 until format.chunkSampleCount) {
                val s = samples(i)
                pcm.put(i * 2, s)
                pcm.put(i * 2 + 1, s)
            }
            encoder.encode(pcm)
        } finally {
            encoder.close()
        }
    }

    private fun sine(hz: Double) = frame { n -> (12000 * sin(2 * PI * hz * n / 48000.0)).toInt().toShort() }

    @Test
    fun `a sine lands in its band and silence in none`() {
        assumeTrue(nativesLoad(), "libopus natives not available here")
        val tap = SpectrumTap(format, 16, 50, System.currentTimeMillis() + 60_000)
        try {
            val edges = SpectrumTap.bandEdges(16, 1024, 48000)
            val bin = 1000.0 * 1024 / 48000
            val expected = (0 until 16).first { bin >= edges[it] && bin < edges[it + 1] }
            // The first frame of a decoder is its warm-up: measure the second.
            val one = sine(1000.0)
            tap.onFrame(one, one.size)
            val levels = checkNotNull(tap.onFrame(one, one.size)) { "the second frame measures" }
            val loudest = levels.indices.maxByOrNull { levels[it] }!!
            assertEquals(expected, loudest, "the 1 kHz band is the loudest: ${levels.toList()}")
            assertTrue(levels[loudest] > 0.4, "a 12000-amplitude sine reads well above silence: ${levels[loudest]}")

            val quiet = frame { 0 }
            tap.onFrame(quiet, quiet.size)
            val silence = checkNotNull(tap.onFrame(quiet, quiet.size)) { "the second quiet frame measures" }
            assertTrue(silence.all { it < 0.05 }, "silence reads as nothing: ${silence.toList()}")
        } finally {
            tap.close()
        }
    }

    @Test
    fun `at 10 Hz every fifth frame is measured, the first one included`() {
        assumeTrue(nativesLoad(), "libopus natives not available here")
        val tap = SpectrumTap(format, 16, 10, System.currentTimeMillis() + 60_000)
        try {
            val quiet = frame { 0 }
            val answered = (1..11).map { tap.onFrame(quiet, quiet.size) != null }
            assertEquals(listOf(true, false, false, false, false, true, false, false, false, false, true), answered)
        } finally {
            tap.close()
        }
    }

    @Test
    fun `an arm in the past is expired, a renewal is not`() {
        assumeTrue(nativesLoad(), "libopus natives not available here")
        val tap = SpectrumTap(format, 16, 10, System.currentTimeMillis() - 1)
        try {
            assertTrue(tap.expired)
            tap.armedUntil = System.currentTimeMillis() + 10_000
            assertFalse(tap.expired)
        } finally {
            tap.close()
        }
    }

    @Test
    fun `the FFT puts a cosine of k cycles in bin k, and the bands climb without gaps`() {
        val n = 1024
        val re = FloatArray(n) { i -> cos(2 * PI * 7 * i / n).toFloat() }
        val im = FloatArray(n)
        SpectrumTap.fft(re, im)
        val mags = (0 until n / 2).map { Math.hypot(re[it].toDouble(), im[it].toDouble()) }
        assertEquals(7, mags.indices.maxByOrNull { mags[it] })
        assertEquals(n / 2.0, mags[7], 1e-2)

        val edges = SpectrumTap.bandEdges(16, 1024, 48000)
        assertEquals(17, edges.size)
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1], "band ${i - 1} is at least one bin wide")
        assertTrue(edges.last() <= 512)
        assertNull(null)
    }
}
