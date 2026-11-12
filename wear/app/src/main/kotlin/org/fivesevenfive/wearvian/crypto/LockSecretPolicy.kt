package org.fivesevenfive.wearvian.crypto

/**
 * What to do with the cached ECDH secret ([SharedSecretCache]) on screen / keyguard broadcasts,
 * independent of whether PresenceService is running. Pure (no Android), so it is JVM unit-tested;
 * [org.fivesevenfive.wearvian.WearvianApp] wires it to the broadcasts.
 *
 * The broadcasts are prompts to RE-READ the keyguard, never the lock signal itself:
 *  - screen off: the watch is not necessarily locked yet (off-body lock follows removal by a few
 *    seconds, or never, while worn), so re-check [isLocked] on a short schedule and clear once it
 *    reads locked;
 *  - screen on: someone is about to interact — if the keyguard is up, clear now;
 *  - user present (keyguard dismissed): stop re-checking and start recomputing the secret
 *    ([prefetch]) so the StrongBox ECDH overlaps the reconnect / the next tap.
 *
 * The schedule can stall while the SoC sleeps; that's fine, because nothing can use the secret
 * without the process running, and every path that uses it (commands, tile, presence) re-reads
 * the keyguard and clears on a locked reading too.
 */
class LockSecretPolicy(
    private val isLocked: () -> Boolean,
    private val clear: () -> Unit,
    private val prefetch: () -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Cancellable,
    private val recheckDelaysMs: List<Long> = RECHECK_DELAYS_MS,
) {
    fun interface Cancellable { fun cancel() }

    private val lock = Any()
    private var pending: Cancellable? = null
    private var generation = 0

    fun onScreenOff() = synchronized(lock) {
        cancelLocked()
        val gen = generation
        scheduleRecheckLocked(gen, 0)
    }

    fun onScreenOn() {
        if (isLocked()) clear()
    }

    fun onUserPresent() {
        synchronized(lock) { cancelLocked() }
        if (!isLocked()) prefetch()
    }

    private fun scheduleRecheckLocked(gen: Int, index: Int) {
        if (index >= recheckDelaysMs.size) { pending = null; return }
        pending = schedule(recheckDelaysMs[index]) { recheck(gen, index) }
    }

    private fun recheck(gen: Int, index: Int) {
        synchronized(lock) { if (gen != generation) return }
        if (isLocked()) {
            clear()
            synchronized(lock) { if (gen == generation) pending = null }
            return
        }
        synchronized(lock) { if (gen == generation) scheduleRecheckLocked(gen, index + 1) }
    }

    private fun cancelLocked() {
        generation++
        pending?.cancel()
        pending = null
    }

    companion object {
        /** Re-check offsets after each screen-off (each delay is relative to the previous check). */
        val RECHECK_DELAYS_MS = listOf(5_000L, 15_000L, 60_000L, 300_000L)
    }
}
