package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusDecoder
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.roundToInt

/**
 * Crossfade proof of concept: turns encoded frames into interleaved 16-bit PCM and back.
 *
 * Deck A (the outgoing track) and deck B (the incoming one) each get their own decoder,
 * because a decoder carries state from one packet to the next. Every buffer handed in is
 * sized for one frame of the output format (`totalSampleCount()` shorts) and is read and
 * written with absolute indices from 0.
 */
interface FrameCodec {
    /** Decodes [length] bytes of [data] from deck A into [out], zero-filling what the packet did not cover. */
    fun decodeA(data: ByteArray, length: Int, out: ShortBuffer)

    /** Same as [decodeA], with deck B's decoder. */
    fun decodeB(data: ByteArray, length: Int, out: ShortBuffer)

    /** Encodes one frame of [pcm] into [out] and returns the number of bytes written. */
    fun encode(pcm: ShortBuffer, out: ByteArray): Int

    fun close()
}

/**
 * The real codec: two libopus decoders and one encoder, built for the player manager's
 * output format. The encoder complexity is `opusEncodingQuality`, as lavaplayer uses for
 * its own transcoding; the bitrate is libopus' default.
 */
class OpusFrameCodec(private val format: AudioDataFormat, quality: Int) : FrameCodec {
    constructor(configuration: AudioConfiguration) : this(configuration.outputFormat, configuration.opusEncodingQuality)

    private val total = format.totalSampleCount()
    private val input: ByteBuffer = ByteBuffer.allocateDirect(4096)
    private val output: ByteBuffer = ByteBuffer.allocateDirect(format.maximumChunkSize())
    private val decoderA: OpusDecoder
    private val decoderB: OpusDecoder
    private val encoder: OpusEncoder

    init {
        val a = OpusDecoder(format.sampleRate, format.channelCount)
        val b = try {
            OpusDecoder(format.sampleRate, format.channelCount)
        } catch (e: Throwable) {
            a.close(); throw e
        }
        val e = try {
            OpusEncoder(format.sampleRate, format.channelCount, quality)
        } catch (t: Throwable) {
            a.close(); b.close(); throw t
        }
        decoderA = a
        decoderB = b
        encoder = e
    }

    override fun decodeA(data: ByteArray, length: Int, out: ShortBuffer) = decode(decoderA, data, length, out)

    override fun decodeB(data: ByteArray, length: Int, out: ShortBuffer) = decode(decoderB, data, length, out)

    private fun decode(decoder: OpusDecoder, data: ByteArray, length: Int, out: ShortBuffer) {
        input.clear()
        input.put(data, 0, length)
        input.flip()
        val samples = decoder.decode(input, out) * format.channelCount
        // decode() leaves the limit at the decoded length; the mixer reads the whole frame.
        out.clear()
        for (i in samples until total) out.put(i, 0)
    }

    override fun encode(pcm: ShortBuffer, out: ByteArray): Int {
        pcm.clear()
        val length = encoder.encode(pcm, format.chunkSampleCount, output)
        output.get(out, 0, length)
        return length
    }

    override fun close() {
        decoderA.close()
        decoderB.close()
        encoder.close()
    }
}

object FrameMixer {
    /**
     * The linear crossfade of ramp frame [k] out of [n]: deck B's gain goes from k/n at the
     * first sample of the frame to (k+1)/n after the last one, deck A's gain is 1 - gB, both
     * channels get the same gain. Over the n frames gB rises from 0 to 1 sample by sample:
     * gB = (k * samplesPerChannel + i) / (n * samplesPerChannel).
     */
    fun mixLinear(
        a: ShortBuffer,
        b: ShortBuffer,
        out: ShortBuffer,
        k: Int,
        n: Int,
        samplesPerChannel: Int = 960,
        channels: Int = 2,
    ) = mixRamp(a, b, out, k.toDouble() / n, (k + 1).toDouble() / n, samplesPerChannel, channels)

    /** Mixes one frame with deck B's gain moving linearly from [fromGainB] to [toGainB]. */
    fun mixRamp(
        a: ShortBuffer,
        b: ShortBuffer,
        out: ShortBuffer,
        fromGainB: Double,
        toGainB: Double,
        samplesPerChannel: Int = 960,
        channels: Int = 2,
    ) {
        val step = (toGainB - fromGainB) / samplesPerChannel
        for (i in 0 until samplesPerChannel) {
            val gB = fromGainB + step * i
            val gA = 1.0 - gB
            for (c in 0 until channels) {
                val index = i * channels + c
                out.put(index, clamp(a.get(index) * gA + b.get(index) * gB))
            }
        }
    }

    fun clamp(value: Double): Short = when {
        value >= Short.MAX_VALUE -> Short.MAX_VALUE
        value <= Short.MIN_VALUE -> Short.MIN_VALUE
        else -> value.roundToInt().toShort()
    }
}
