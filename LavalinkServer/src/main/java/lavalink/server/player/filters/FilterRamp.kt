package lavalink.server.player.filters

import com.github.natanbc.lavadsp.channelmix.ChannelMixPcmAudioFilter
import com.github.natanbc.lavadsp.karaoke.KaraokePcmAudioFilter
import com.github.natanbc.lavadsp.lowpass.LowPassPcmAudioFilter
import com.github.natanbc.lavadsp.rotation.RotationPcmAudioFilter
import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.github.natanbc.lavadsp.tremolo.TremoloPcmAudioFilter
import com.github.natanbc.lavadsp.vibrato.VibratoPcmAudioFilter
import com.github.natanbc.lavadsp.volume.VolumePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer

/**
 * FlaviBot fork (`lavalink.server.filterRampMs`): a filter change glides
 * instead of switching. Upstream rebuilds the whole lavaplayer pipeline on
 * every PATCH, so a speed, an equalizer or a karaoke level jumped to its new
 * value between two chunks; a member turning a knob heard a step. The live
 * lavadsp and lavaplayer filters all take their settings while running, so
 * when the set of filters does not change, the chain keeps its instances and
 * a [FilterRamp] moves each setting from where it is to where it goes over
 * the ramp, from the audio thread (the only one that touches a live filter).
 * A filter that joins the set starts inaudible and glides in; one that
 * leaves is still a rebuild (its sound stops where the new pipeline begins).
 *
 * A kind's settings are its "knobs": a fixed-order list of numbers, read from
 * its config and written to its filter by a [KnobAdapter]. The knobs a config
 * holds that cannot glide (a karaoke filter's band and width recompute its
 * coefficients) are set once, at the start.
 */
interface KnobAdapter {
    val name: String

    /** The knobs a config of this kind holds, in [apply]'s order. */
    fun knobsOf(config: FilterConfig): DoubleArray

    /** The knobs at which the filter is inaudible; null when the kind has no such point (rotation, distortion). */
    val neutral: DoubleArray?

    /** Write knobs to the live filter. Audio thread only. */
    fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray)

    /** Write the settings that do not glide. Audio thread only. */
    fun applyStatic(filter: FloatPcmAudioFilter, config: FilterConfig) {}
}

object KnobAdapters {
    /** lavadsp refuses a tremolo or vibrato depth of 0; this is as good as silent. */
    private const val NO_DEPTH = 0.001

    val volume = object : KnobAdapter {
        override val name = "volume"
        override fun knobsOf(config: FilterConfig) = doubleArrayOf((config as VolumeConfig).volume.toDouble())
        override val neutral = doubleArrayOf(1.0)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as VolumePcmAudioFilter).volume = knobs[0].toFloat()
        }
    }

    val equalizer = object : KnobAdapter {
        override val name = "equalizer"
        override fun knobsOf(config: FilterConfig) = (config as EqualizerConfig).gains().map { it.toDouble() }.toDoubleArray()
        override val neutral = DoubleArray(Equalizer.BAND_COUNT)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            val eq = filter as Equalizer
            for (band in knobs.indices) eq.setGain(band, knobs[band].toFloat())
        }
    }

    val karaoke = object : KnobAdapter {
        override val name = "karaoke"
        override fun knobsOf(config: FilterConfig) = (config as KaraokeConfig).let { doubleArrayOf(it.level.toDouble(), it.monoLevel.toDouble()) }
        override val neutral = doubleArrayOf(0.0, 0.0)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as KaraokePcmAudioFilter).setLevel(knobs[0].toFloat()).setMonoLevel(knobs[1].toFloat())
        }
        override fun applyStatic(filter: FloatPcmAudioFilter, config: FilterConfig) {
            val c = config as KaraokeConfig
            (filter as KaraokePcmAudioFilter).setFilterBand(c.filterBand).setFilterWidth(c.filterWidth)
        }
    }

    val timescale = object : KnobAdapter {
        override val name = "timescale"
        override fun knobsOf(config: FilterConfig) = (config as TimescaleConfig).let { doubleArrayOf(it.speed, it.pitch, it.rate) }
        override val neutral = doubleArrayOf(1.0, 1.0, 1.0)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as TimescalePcmAudioFilter).setSpeed(knobs[0]).setPitch(knobs[1]).setRate(knobs[2])
        }
    }

    val tremolo = object : KnobAdapter {
        override val name = "tremolo"
        override fun knobsOf(config: FilterConfig) = doubleArrayOf((config as TremoloConfig).depth.toDouble())
        override val neutral = doubleArrayOf(NO_DEPTH)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as TremoloPcmAudioFilter).setDepth(knobs[0].toFloat())
        }
        override fun applyStatic(filter: FloatPcmAudioFilter, config: FilterConfig) {
            (filter as TremoloPcmAudioFilter).setFrequency((config as TremoloConfig).frequency)
        }
    }

    val vibrato = object : KnobAdapter {
        override val name = "vibrato"
        override fun knobsOf(config: FilterConfig) = doubleArrayOf((config as VibratoConfig).depth.toDouble())
        override val neutral = doubleArrayOf(NO_DEPTH)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as VibratoPcmAudioFilter).setDepth(knobs[0].toFloat())
        }
        override fun applyStatic(filter: FloatPcmAudioFilter, config: FilterConfig) {
            (filter as VibratoPcmAudioFilter).setFrequency((config as VibratoConfig).frequency)
        }
    }

    /** The 8D effect pans around a fixed centre at every speed: it glides between speeds, never in or out. */
    val rotation = object : KnobAdapter {
        override val name = "rotation"
        override fun knobsOf(config: FilterConfig) = doubleArrayOf((config as RotationConfig).rotationHz)
        override val neutral: DoubleArray? = null
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as RotationPcmAudioFilter).setRotationSpeed(knobs[0])
        }
    }

    val channelMix = object : KnobAdapter {
        override val name = "channelMix"
        override fun knobsOf(config: FilterConfig) = (config as ChannelMixConfig).let { doubleArrayOf(it.leftToLeft.toDouble(), it.leftToRight.toDouble(), it.rightToLeft.toDouble(), it.rightToRight.toDouble()) }
        override val neutral = doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as ChannelMixPcmAudioFilter)
                .setLeftToLeft(knobs[0].toFloat()).setLeftToRight(knobs[1].toFloat())
                .setRightToLeft(knobs[2].toFloat()).setRightToRight(knobs[3].toFloat())
        }
    }

    val lowPass = object : KnobAdapter {
        override val name = "lowPass"
        override fun knobsOf(config: FilterConfig) = doubleArrayOf((config as LowPassConfig).smoothing.toDouble())
        override val neutral = doubleArrayOf(1.0)
        override fun apply(filter: FloatPcmAudioFilter, knobs: DoubleArray) {
            (filter as LowPassPcmAudioFilter).setSmoothing(knobs[0].toFloat())
        }
    }

    private val all = listOf(volume, equalizer, karaoke, timescale, tremolo, vibrato, rotation, channelMix, lowPass).associateBy { it.name }

    /** The adapter of a kind, null for one whose settings never glide (distortion, plugin filters). */
    fun of(name: String): KnobAdapter? = all[name]
}

/** One filter's glide: linear, from where it is to where it goes, over a number of samples. */
class FilterRamp(
    private val from: Map<String, DoubleArray>,
    private val to: Map<String, DoubleArray>,
    private val totalSamples: Long,
) {
    private var done = 0L

    /** Whether a chunk has gone by since this ramp was set: the settings that do not glide are written on the first one. */
    val started: Boolean get() = done > 0

    val finished: Boolean get() = done >= totalSamples

    /** Another [samples] of audio went by. */
    fun advance(samples: Int) {
        done = minOf(totalSamples, done + samples)
    }

    /** Where every knob is right now. */
    fun current(): Map<String, DoubleArray> {
        val t = if (totalSamples <= 0) 1.0 else done.toDouble() / totalSamples
        return to.mapValues { (name, target) ->
            val start = from[name] ?: target
            DoubleArray(target.size) { i -> val a = start.getOrElse(i) { target[i] }; a + (target[i] - a) * t }
        }
    }
}

/**
 * The filter lavaplayer feeds: the pipeline's head, which advances the
 * chain's ramp by each chunk before passing it on. Its close is the chain's
 * cue that these live filters are gone (a new track builds new ones).
 */
class RampingHead(private val delegate: FloatPcmAudioFilter, private val chain: FilterChain) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        chain.advanceRamp(this, length)
        delegate.process(input, offset, length)
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) = delegate.seekPerformed(requestedTime, providedTime)
    override fun flush() = delegate.flush()
    override fun close() {
        chain.liveClosed(this)
        delegate.close()
    }
}
