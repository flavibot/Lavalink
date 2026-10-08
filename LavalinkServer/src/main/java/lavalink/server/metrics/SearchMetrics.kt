package lavalink.server.metrics

import io.prometheus.client.Counter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty("metrics.prometheus.enabled")
class SearchMetrics {
    
    companion object {
        // By source only. The counter used to carry a guild_id label too: a
        // child per guild that ever played, never expired, rendered in full
        // at every scrape (~2.5 M series, 81 % of the TSDB at the 26/09/2026
        // audit) and readable by anyone who reaches the metrics port, since
        // that endpoint is anonymous. Per-guild play counts live in the
        // engine's own analytics, not in a Prometheus label.
        private val playCounter: Counter = Counter.build()
            .name("lavalink_tracks_played_total")
            .help("Total number of tracks played by source")
            .labelNames("source")
            .register()

        private val loadResultCounter: Counter = Counter.build()
            .name("lavalink_item_load_results_total")
            .help("Total number of audio item load results by source and result type")
            .labelNames("source", "result")
            .register()
    }
    
    fun recordPlay(source: String) {
        playCounter.labels(source).inc()
    }

    fun recordLoadResult(source: String, result: String) {
        loadResultCounter.labels(source, result).inc()
    }
}

