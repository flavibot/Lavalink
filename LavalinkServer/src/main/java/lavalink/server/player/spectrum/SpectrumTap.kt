package lavalink.server.player.spectrum

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FlaviBot fork: the band energies of what a player sends. Fed the Opus frame
 * the player just handed to Discord (so what is measured is what is heard,
 * filters, crossfade mix and all, and never ahead of the frame buffer), it
 * decodes it, takes the mono mix through a Hann window and a radix-2 FFT, and
 * sums the power of each log-spaced band. Every [everyFrames] frames it
 * answers the bands as levels from 0 (silence) to 1 (full scale), in dB over
 * a 60 dB range. Armed until [armedUntil]; the client renews.
 *
 * Audio thread only (the Koe poller that provides frames), except the arm.
 */
class SpectrumTap(
    private val format: AudioDataFormat,
    private val bandCount: Int,
    rateHz: Int,
    @Volatile var armedUntil: Long,
) : AutoCloseable {
    private val decoder = OpusDecoder(format.sampleRate, format.channelCount)
    private val input: ByteBuffer = ByteBuffer.allocateDirect(4096)
    // The decoded frame: totalSampleCount() samples (maximumChunkSize() is the Opus packet, far smaller).
    private val pcm: ShortBuffer = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val fftSize = nextPowerOfTwo(format.chunkSampleCount)
    private val window = FloatArray(fftSize) { i -> (0.5 - 0.5 * cos(2 * PI * i / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    /** The FFT bin where each band starts; the last entry closes the last band. */
    private val edges: IntArray = bandEdges(bandCount, fftSize, format.sampleRate)
    private val everyFrames = max(1, (1000 / 20) / max(1, rateHz))
    private var framesSeen = 0
    private val levels = FloatArray(bandCount)

    val expired: Boolean get() = System.currentTimeMillis() > armedUntil

    /**
     * One Opus frame the player sent. Null between two measures; the bands
     * when this frame is the one in [everyFrames] (the first frame counts, so
     * a fresh arm answers at once). A frame that does not decode counts as
     * silence.
     */
    fun onFrame(opus: ByteArray, length: Int): FloatArray? {
        framesSeen++
        if ((framesSeen - 1) % everyFrames != 0) {
            return null
        }
        val samples = decode(opus, length)
        return measure(samples)
    }

    private fun decode(opus: ByteArray, length: Int): Int {
        input.clear()
        input.put(opus, 0, min(length, input.capacity()))
        input.flip()
        pcm.clear()
        return try {
            decoder.decode(input, pcm)
        } catch (e: Exception) {
            0
        }
    }

    /** The bands of the frame in [pcm] ([frames] per channel). */
    internal fun measure(frames: Int): FloatArray {
        val channels = format.channelCount
        for (i in 0 until fftSize) {
            if (i < frames) {
                var mono = 0f
                for (c in 0 until channels) mono += pcm.get(i * channels + c) / 32768f
                re[i] = (mono / channels) * window[i]
            } else {
                re[i] = 0f
            }
            im[i] = 0f
        }
        fft(re, im)
        for (band in 0 until bandCount) {
            var power = 0.0
            for (bin in edges[band] until edges[band + 1]) {
                power += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            val bins = max(1, edges[band + 1] - edges[band])
            // Mean power per bin, scaled by the window's energy to full scale, then dB over 60 dB.
            val rms = sqrt(power / bins) / (fftSize / 4.0)
            val db = 20 * log10(max(rms, 1e-6))
            levels[band] = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        }
        return levels.copyOf()
    }

    override fun close() {
        decoder.close()
    }

    companion object {
        fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }

        /** [count] bands between 40 Hz and 16 kHz, log-spaced, as FFT bin bounds (each band at least one bin wide). */
        fun bandEdges(count: Int, fftSize: Int, sampleRate: Int): IntArray {
            val low = 40.0
            val high = min(16_000.0, sampleRate / 2.0)
            val edges = IntArray(count + 1)
            for (i in 0..count) {
                val hz = low * Math.exp(ln(high / low) * i / count)
                edges[i] = (hz * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2)
            }
            for (i in 1..count) {
                if (edges[i] <= edges[i - 1]) edges[i] = min(fftSize / 2, edges[i - 1] + 1)
            }
            return edges
        }

        /** In-place iterative radix-2 FFT. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle).toFloat()
                val wi = sin(angle).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]
                        val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr
                        im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr
                        im[i + k + len / 2] = ui - vi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
