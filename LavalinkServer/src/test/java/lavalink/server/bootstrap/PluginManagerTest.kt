package lavalink.server.bootstrap

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginManagerTest {
    @Test
    fun `a commit-built jar's -SNAPSHOT manifest matches the bare commit it was declared with`() {
        assertTrue(PluginManager.sameVersion("595a7906d070039236270d72c81073c84e82d58b-SNAPSHOT", "595a7906d070039236270d72c81073c84e82d58b"))
        assertTrue(PluginManager.sameVersion("4.8.1", "4.8.1"))
        assertTrue(PluginManager.sameVersion("71ba160", "71ba160-SNAPSHOT"))
    }

    @Test
    fun `a different version is still a different version`() {
        assertFalse(PluginManager.sameVersion("66cf4a6", "71ba160"))
        assertFalse(PluginManager.sameVersion("4.8.1", "4.8.3"))
        assertFalse(PluginManager.sameVersion("4.8.1-SNAPSHOT", "4.8.2"))
    }
}
