package lavalink.server

import org.junit.jupiter.api.Assertions.fail
import java.lang.ref.WeakReference

/**
 * Fails unless every referent becomes unreachable within [timeoutMs]. A leak
 * keeps a strong path from something alive, so no number of collections clears
 * its reference; anything else is collected after a few.
 */
fun assertCollected(refs: Map<String, WeakReference<*>>, timeoutMs: Long = 5_000) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (refs.values.any { it.get() != null } && System.nanoTime() < deadline) {
        System.gc()
        Thread.sleep(20)
    }
    val kept = refs.filterValues { it.get() != null }.keys
    if (kept.isNotEmpty()) fail<Unit>("still reachable after ${timeoutMs} ms of collections: ${kept.joinToString()}")
}
