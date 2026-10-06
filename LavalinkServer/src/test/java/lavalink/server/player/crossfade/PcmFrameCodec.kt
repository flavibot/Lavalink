package lavalink.server.player.crossfade

import java.nio.ShortBuffer

/**
 * A codec for tests without natives: a frame is raw little-endian 16-bit PCM, so decode and
 * encode are plain copies and the mixer's arithmetic can be checked sample by sample.
 */
class PcmFrameCodec : FrameCodec {
    @Volatile var decodeACount = 0
    @Volatile var decodeBCount = 0
    @Volatile var encodeCount = 0
    @Volatile var closed = false

    /** Calls made after close(); the wrapper swallows the exception, so tests read this. */
    @Volatile var usedAfterClose = 0

    /** The PCM handed to encode(), one array per call. */
    val encodedFrames = mutableListOf<ShortArray>()

    override fun decodeA(data: ByteArray, length: Int, out: ShortBuffer) {
        decodeACount++
        copy(data, length, out)
    }

    override fun decodeB(data: ByteArray, length: Int, out: ShortBuffer) {
        decodeBCount++
        copy(data, length, out)
    }

    private fun copy(data: ByteArray, length: Int, out: ShortBuffer) {
        checkOpen()
        out.clear()
        val samples = length / 2
        for (i in 0 until out.capacity()) {
            out.put(i, if (i < samples) readShort(data, i) else 0)
        }
    }

    override fun encode(pcm: ShortBuffer, out: ByteArray): Int {
        checkOpen()
        encodeCount++
        val n = pcm.capacity()
        val copy = ShortArray(n)
        for (i in 0 until n) {
            val v = pcm.get(i).toInt()
            copy[i] = v.toShort()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        synchronized(encodedFrames) { encodedFrames.add(copy) }
        return n * 2
    }

    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        if (closed) {
            usedAfterClose++
            throw IllegalStateException("codec used after close")
        }
    }

    companion object {
        fun readShort(data: ByteArray, index: Int): Short =
            ((data[2 * index].toInt() and 0xff) or (data[2 * index + 1].toInt() shl 8)).toShort()

        /** One stereo frame of [samplesPerChannel] samples: sample 0 carries [tag] on both channels, the rest [value]. */
        fun frame(tag: Int, value: Int, samplesPerChannel: Int = 960, channels: Int = 2): ByteArray {
            val total = samplesPerChannel * channels
            val bytes = ByteArray(total * 2)
            for (i in 0 until total) {
                val v = if (i < channels) tag else value
                bytes[2 * i] = v.toByte()
                bytes[2 * i + 1] = (v shr 8).toByte()
            }
            return bytes
        }
    }
}
