package lavalink.server.player.filters

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.AudioFilterExtension
import dev.arbjerg.lavalink.protocol.v4.*
import kotlinx.serialization.json.JsonElement
import dev.arbjerg.lavalink.protocol.v4.Band as Bandv4

class FilterChain(
    private var volume: VolumeConfig? = null,
    private var equalizer: EqualizerConfig? = null,
    private var karaoke: KaraokeConfig? = null,
    private var timescale: TimescaleConfig? = null,
    private var tremolo: TremoloConfig? = null,
    private var vibrato: VibratoConfig? = null,
    private var distortion: DistortionConfig? = null,
    private var rotation: RotationConfig? = null,
    private var channelMix: ChannelMixConfig? = null,
    private var lowPass: LowPassConfig? = null,
) : PcmFilterFactory {

    @Volatile
    var pluginFilters: List<PluginConfig> = emptyList()

    companion object {
        fun parse(
            filters: Filters,
            extensions: List<AudioFilterExtension>,
        ): FilterChain {
            return FilterChain(
                filters.volume.ifPresent(::VolumeConfig),
                filters.equalizer.ifPresentAndNotNull {
                    EqualizerConfig(it.map { band -> Band(band.band, band.gain) })
                },
                filters.karaoke.ifPresentAndNotNull { KaraokeConfig(it.level, it.monoLevel, it.filterBand, it.filterWidth) },
                filters.timescale.ifPresentAndNotNull { TimescaleConfig(it.speed, it.pitch, it.rate) },
                filters.tremolo.ifPresentAndNotNull { TremoloConfig(it.frequency, it.depth) },
                filters.vibrato.ifPresentAndNotNull { VibratoConfig(it.frequency, it.depth) },
                filters.distortion.ifPresentAndNotNull {
                    DistortionConfig(
                        it.sinOffset,
                        it.sinScale,
                        it.cosOffset,
                        it.cosScale,
                        it.tanOffset,
                        it.tanScale,
                        it.offset,
                        it.scale
                    )
                },
                filters.rotation.ifPresentAndNotNull { RotationConfig(it.rotationHz) },
                filters.channelMix.ifPresentAndNotNull {
                    ChannelMixConfig(
                        it.leftToLeft,
                        it.leftToRight,
                        it.rightToLeft,
                        it.rightToRight
                    )
                },
                filters.lowPass.ifPresentAndNotNull { LowPassConfig(it.smoothing) },
            ).apply {
                parsePluginConfigs(filters.pluginFilters, extensions)
            }
        }
    }

    fun parsePluginConfigs(dynamicValues: Map<String, JsonElement>, extensions: List<AudioFilterExtension>) {
        pluginFilters = extensions.mapNotNull {
            val json = dynamicValues[it.name] ?: return@mapNotNull null
            PluginConfig(it, json)
        }
    }

    private fun buildList() = listOfNotNull(
        volume,
        equalizer,
        karaoke,
        timescale,
        tremolo,
        vibrato,
        distortion,
        rotation,
        channelMix,
        lowPass,
        *pluginFilters.toTypedArray()
    )

    val isEnabled get() = buildList().any { it.isEnabled }

    /** The kinds that are on: what the built pipeline holds. Same set = the live filters can take the new settings. */
    val kinds: Set<String> get() = buildList().map { it.name }.toSet()

    // ─── FlaviBot fork: glides (FilterRamp.kt) ──────────────────────────────

    /** The filters of the pipeline lavaplayer runs right now, by kind, the head it feeds, and the format's rate; null between tracks. */
    private class Live(val filters: Map<String, FloatPcmAudioFilter>, val head: RampingHead, val sampleRate: Int)

    @Volatile
    private var live: Live? = null

    @Volatile
    private var ramp: FilterRamp? = null

    @Volatile
    private var rampMs: Long = 0

    /** The kinds that start inaudible when the next pipeline is built (they joined the set) and glide in over [rampMs]. */
    @Volatile
    private var startNeutral: Set<String> = emptySet()

    /**
     * Take [next]'s settings over the live filters, gliding over [rampMs]:
     * true when the pipeline keeps running as it is (same kinds, same plugin
     * filters, something playing through it), false when the caller must
     * rebuild. On true this chain now carries [next]'s settings: it stays the
     * factory lavaplayer holds, and its next build uses them.
     */
    fun rampTo(next: FilterChain, rampMs: Long): Boolean {
        val running = live ?: return false
        if (next.kinds != kinds || next.pluginFilters.map { it.name to it.json } != pluginFilters.map { it.name to it.json }) {
            return false
        }
        val from = ramp?.current() ?: knobs()
        adopt(next)
        this.rampMs = rampMs
        ramp = FilterRamp(from, knobs(), samplesOf(rampMs, running.sampleRate))
        return true
    }

    /** The kinds that start inaudible when the pipeline is next built, and glide in over [rampMs]. */
    fun startNeutralFor(kinds: Set<String>, rampMs: Long) {
        startNeutral = kinds.filter { KnobAdapters.of(it)?.neutral != null }.toSet()
        this.rampMs = rampMs
    }

    /** Where the ramp stands, for the tests: null when nothing glides. */
    internal fun rampProgress(): Map<String, DoubleArray>? = ramp?.current()

    /** A live filter by kind, for the tests (the head hides the one it wraps from the list). */
    internal fun liveFilter(kind: String): FloatPcmAudioFilter? = live?.filters?.get(kind)

    private fun adopt(next: FilterChain) {
        volume = next.volume
        equalizer = next.equalizer
        karaoke = next.karaoke
        timescale = next.timescale
        tremolo = next.tremolo
        vibrato = next.vibrato
        distortion = next.distortion
        rotation = next.rotation
        channelMix = next.channelMix
        lowPass = next.lowPass
        pluginFilters = next.pluginFilters
    }

    /** Every enabled kind's knobs, as its config holds them. */
    private fun knobs(): Map<String, DoubleArray> =
        buildList().mapNotNull { config -> KnobAdapters.of(config.name)?.let { config.name to it.knobsOf(config) } }.toMap()

    private fun samplesOf(ms: Long, sampleRate: Int): Long = ms * sampleRate / 1000

    /** Audio thread, from the head, once per chunk: move the live filters along the ramp, settings that do not glide first. */
    internal fun advanceRamp(head: RampingHead, samples: Int) {
        val running = live ?: return
        if (running.head !== head) return
        val r = ramp ?: return
        if (!r.started) {
            for (config in buildList()) {
                val filter = running.filters[config.name] ?: continue
                KnobAdapters.of(config.name)?.applyStatic(filter, config)
            }
        }
        // The chunk about to be processed gets the settings the ramp stands at
        // before it (a filter that joins starts exactly inaudible); the target
        // lands with the chunk that ends the ramp.
        apply(running, r.current())
        r.advance(samples)
        if (r.finished) {
            apply(running, r.current())
            ramp = null
        }
    }

    private fun apply(running: Live, knobs: Map<String, DoubleArray>) {
        for ((kind, values) in knobs) {
            val filter = running.filters[kind] ?: continue
            KnobAdapters.of(kind)?.apply(filter, values)
        }
    }

    /** The pipeline this head led is closed (the track ended, or lavaplayer rebuilt): nothing live to glide any more. */
    internal fun liveClosed(head: RampingHead) {
        if (live?.head === head) {
            live = null
            ramp = null
        }
    }

    override fun buildChain(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter
    ): MutableList<AudioFilter> {
        val enabledFilters = buildList().takeIf { it.isNotEmpty() }
            ?: return mutableListOf()

        val pipeline = mutableListOf<FloatPcmAudioFilter>()
        val built = LinkedHashMap<String, FloatPcmAudioFilter>()

        for (filter in enabledFilters) {
            val outputTo = pipeline.lastOrNull() ?: output
            val builtFilter = filter.build(format, outputTo)
            if (builtFilter != null) {
                pipeline.add(builtFilter)
                built[filter.name] = builtFilter
            }
        }
        if (pipeline.isEmpty()) {
            return mutableListOf()
        }

        // FlaviBot fork: the pipeline's head advances the glide. The kinds that
        // joined the set start inaudible and glide to their settings from the
        // first chunk (startNeutralFor); the others start where they are set.
        val neutralKinds = startNeutral
        startNeutral = emptySet()
        val head = RampingHead(pipeline.last(), this)
        live = Live(built, head, format.sampleRate)
        ramp = if (neutralKinds.isNotEmpty() && rampMs > 0) {
            val target = knobs()
            val from = target.mapValues { (kind, values) -> if (kind in neutralKinds) KnobAdapters.of(kind)?.neutral ?: values else values }
            for (kind in neutralKinds) {
                val adapter = KnobAdapters.of(kind) ?: continue
                val neutral = adapter.neutral ?: continue
                built[kind]?.let { adapter.apply(it, neutral) }
            }
            FilterRamp(from, target, samplesOf(rampMs, format.sampleRate))
        } else {
            null
        }

        val chain = pipeline.reversed().toMutableList<AudioFilter>() // Output last
        chain[0] = head
        return chain
    }

    fun toFilters(): Filters {
        return Filters(
            volume?.volume.toOmissible(),
            equalizer?.bands?.map { Bandv4(it.band, it.gain) }.toOmissible(),
            karaoke?.let { Karaoke(it.level, it.monoLevel, it.filterBand, it.filterWidth) }.toOmissible(),
            timescale?.let { Timescale(it.speed, it.pitch, it.rate) }.toOmissible(),
            tremolo?.let { Tremolo(it.frequency, it.depth) }.toOmissible(),
            vibrato?.let { Vibrato(it.frequency, it.depth) }.toOmissible(),
            distortion?.let {
                Distortion(
                    it.sinOffset,
                    it.sinScale,
                    it.cosOffset,
                    it.cosScale,
                    it.tanOffset,
                    it.tanScale,
                    it.offset,
                    it.scale
                )
            }.toOmissible(),
            rotation?.let { Rotation(it.rotationHz) }.toOmissible(),
            channelMix?.let { ChannelMix(it.leftToLeft, it.leftToRight, it.rightToLeft, it.rightToRight) }
                .toOmissible(),
            lowPass?.let { LowPass(it.smoothing) }.toOmissible(),
            pluginFilters.associate { it.extension.name to it.json }
        )
    }

    class PluginConfig(val extension: AudioFilterExtension, val json: JsonElement) : FilterConfig() {
        override fun build(format: AudioDataFormat, output: FloatPcmAudioFilter): FloatPcmAudioFilter? =
            extension.build(json, format, output)

        override val isEnabled = extension.isEnabled(json)
        override val name: String = extension.name
    }

}
