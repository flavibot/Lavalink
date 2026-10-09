package lavalink.server.player.filters

import com.github.natanbc.lavadsp.karaoke.KaraokePcmAudioFilter
import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.nio.ShortBuffer

/**
 * The glide between two filter settings (FilterRamp.kt): the live lavaplayer
 * and lavadsp filters move to the new settings over the ramp, chunk by chunk,
 * instead of the pipeline being rebuilt. Pure-Java filters only (the
 * timescale's native converter is mocked), on lavaplayer's own format.
 */
class FilterRampTest {
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_BE
    private val rate = format.sampleRate

    /** The end of the pipeline: keeps the last chunk it was given. */
    private class Sink : UniversalPcmAudioFilter {
        var last: Array<FloatArray>? = null
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            last = Array(input.size) { c -> input[c].copyOfRange(offset, offset + length) }
        }
        override fun process(input: ShortArray, offset: Int, length: Int) {}
        override fun process(buffer: ShortBuffer) {}
        override fun process(input: Array<ShortArray>, offset: Int, length: Int) {}
        override fun seekPerformed(requestedTime: Long, providedTime: Long) {}
        override fun flush() {}
        override fun close() {}
    }

    private fun eq(gain: Float) = EqualizerConfig(listOf(Band(0, gain)))

    /** Push [ms] of a constant, centre-panned signal through the chain's head. */
    private fun pump(chain: List<AudioFilter>, ms: Int, level: Float = 1f) {
        val samples = rate * ms / 1000
        val input = arrayOf(FloatArray(samples) { level }, FloatArray(samples) { level })
        (chain[0] as FloatPcmAudioFilter).process(input, 0, samples)
    }

    private fun equalizerOf(chain: FilterChain) = chain.liveFilter("equalizer") as Equalizer

    @Test
    fun `the same filters with new settings glide there, nothing rebuilt, and the chain reports the new settings`() {
        val chain = FilterChain(equalizer = eq(0.1f))
        val pipeline = chain.buildChain(null, format, Sink())
        val equalizer = equalizerOf(chain)
        assertEquals(0.1f, equalizer.getGain(0), 1e-6f)

        assertTrue(chain.rampTo(FilterChain(equalizer = eq(0.5f)), 300))
        assertEquals(0.5f, chain.toFilters().equalizer.let { (it as dev.arbjerg.lavalink.protocol.v4.Omissible.Present).value!![0].gain }, 1e-6f)

        // 20 ms chunks over a 300 ms ramp: a chunk gets the settings the ramp stood at before it.
        pump(pipeline, 20)
        assertEquals(0.1f, equalizer.getGain(0), 1e-6f, "the first chunk starts where the filter was")
        repeat(7) { pump(pipeline, 20) }
        assertEquals(0.1f + 0.4f * (7f * 960 / 14_400), equalizer.getGain(0), 0.005f, "the eighth chunk, seven chunks in")
        repeat(7) { pump(pipeline, 20) }
        assertEquals(0.5f, equalizer.getGain(0), 1e-6f, "the chunk that ends the ramp lands the target")
        assertNull(chain.rampProgress(), "the ramp is over")
        pump(pipeline, 20)
        assertEquals(0.5f, equalizer.getGain(0), 1e-6f)
    }

    @Test
    fun `a second change during a glide continues from where the first stood`() {
        val chain = FilterChain(equalizer = eq(0.1f))
        val pipeline = chain.buildChain(null, format, Sink())
        val equalizer = equalizerOf(chain)
        chain.rampTo(FilterChain(equalizer = eq(0.5f)), 300)
        repeat(8) { pump(pipeline, 20) }
        val standing = 0.1 + 0.4 * (8.0 * 960 / 14_400)
        assertTrue(chain.rampTo(FilterChain(equalizer = eq(0.1f)), 300))
        assertEquals(standing, chain.rampProgress()!!.getValue("equalizer")[0], 1e-6, "the new ramp starts from the interrupted one's point")
        repeat(8) { pump(pipeline, 20) }
        assertEquals((standing - (standing - 0.1) * (7.0 * 960 / 14_400)).toFloat(), equalizer.getGain(0), 0.005f, "on its way back")
        repeat(8) { pump(pipeline, 20) }
        assertEquals(0.1f, equalizer.getGain(0), 1e-6f)
    }

    @Test
    fun `a different set of filters, or nothing live, is a rebuild`() {
        val chain = FilterChain(equalizer = eq(0.1f))
        assertFalse(chain.rampTo(FilterChain(equalizer = eq(0.5f)), 300), "nothing built yet")
        val pipeline = chain.buildChain(null, format, Sink())
        assertFalse(chain.rampTo(FilterChain(equalizer = eq(0.5f), karaoke = KaraokeConfig()), 300), "a filter joins")
        assertFalse(chain.rampTo(FilterChain(), 300), "a filter leaves")
        pipeline[0].close()
        assertFalse(chain.rampTo(FilterChain(equalizer = eq(0.5f)), 300), "the pipeline is closed")
    }

    @Test
    fun `a filter that joins the set starts inaudible and glides in`() {
        val sink = Sink()
        val chain = FilterChain(equalizer = eq(0.1f), karaoke = KaraokeConfig(level = 1f, monoLevel = 0f))
        chain.startNeutralFor(setOf("karaoke"), 300)
        val pipeline = chain.buildChain(null, format, sink)
        assertTrue(chain.liveFilter("karaoke") is KaraokePcmAudioFilter)

        pump(pipeline, 20)
        // The first chunk: a karaoke at level 0 passes the centre through, so the constant 1.0 comes out as 1.0.
        assertEquals(1f, sink.last!![0][0], 0.05f, "the karaoke starts inaudible")
        pump(pipeline, 300)
        pump(pipeline, 100)
        // Level 1: the centre is removed (l - r), and the constant has no band-pass component left.
        assertEquals(0f, sink.last!![0].last(), 0.05f, "the karaoke is fully in after the ramp")
        assertEquals(0.1f, equalizerOf(chain).getGain(0), 1e-6f, "a filter already in the set starts where it is set")
    }

    @Test
    fun `without a ramp a build is upstream's, the settings at once`() {
        val sink = Sink()
        val chain = FilterChain(karaoke = KaraokeConfig(level = 1f, monoLevel = 0f))
        val pipeline = chain.buildChain(null, format, sink)
        pump(pipeline, 20)
        assertEquals(0f, sink.last!![0][0], 0.05f)
        assertNull(chain.rampProgress())
    }

    @Test
    fun `the timescale glides its speed, pitch and rate through the live filter's setters`() {
        val filter = Mockito.mock(TimescalePcmAudioFilter::class.java, Mockito.RETURNS_SELF)
        KnobAdapters.timescale.apply(filter, doubleArrayOf(1.15, 1.0, 1.0))
        Mockito.verify(filter).setSpeed(1.15)
        Mockito.verify(filter).setPitch(1.0)
        Mockito.verify(filter).setRate(1.0)
        assertEquals(listOf(1.0, 1.0, 1.0), KnobAdapters.timescale.neutral!!.toList())
        assertEquals(listOf(1.3, 1.3, 1.0), KnobAdapters.timescale.knobsOf(TimescaleConfig(1.3, 1.3, 1.0)).toList())
    }

    @Test
    fun `a ramp of nothing is the target at once`() {
        val ramp = FilterRamp(mapOf("equalizer" to doubleArrayOf(0.1)), mapOf("equalizer" to doubleArrayOf(0.5)), 0)
        ramp.advance(1)
        assertEquals(0.5, ramp.current().getValue("equalizer")[0], 1e-9)
        assertTrue(ramp.finished)
    }
}
