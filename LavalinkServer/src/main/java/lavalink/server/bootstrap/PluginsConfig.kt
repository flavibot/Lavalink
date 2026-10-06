package lavalink.server.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "lavalink")
@Component
class PluginsConfig {
    var plugins: List<PluginDeclaration> = emptyList()
    var pluginsDir: String = "./plugins"
    var defaultPluginRepository: String = "https://maven.lavalink.dev/releases"
    var defaultPluginSnapshotRepository: String = "https://maven.lavalink.dev/snapshots"
    /**
     * Ask each plugin's repository for a newer version at every start (an HTTP
     * GET per plugin, only for a warning). Off for a node that must boot with no
     * third-party host reachable.
     */
    var pluginsUpdateCheck: Boolean = true
    /** Connect and read timeouts of the plugin downloads and update checks, ms. */
    var pluginsHttpTimeoutMs: Int = 15_000
}

data class PluginDeclaration(
    var dependency: String? = null,
    var repository: String? = null,
    var snapshot: Boolean = false
)