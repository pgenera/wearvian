package org.fivesevenfive.wearvian.util

/**
 * A value that is expensive to read (e.g. a binder call) but changes rarely, cached for
 * [ttlMs] of a monotonic [now] clock. [get] returns the cached value while it is younger
 * than [ttlMs] and re-reads via [read] otherwise, so a caller polling it many times a
 * second does at most one real read per TTL. Pure (clock + reader injected) so the
 * staleness bound is JVM-testable. Safe to call from several threads: a race costs at
 * most one extra [read], never a torn value.
 */
class TtlCache<T>(
    private val ttlMs: Long,
    private val now: () -> Long,
    private val read: () -> T,
) {
    private class Entry<T>(val value: T, val at: Long)

    @Volatile private var entry: Entry<T>? = null

    fun get(): T {
        val t = now()
        val e = entry
        // `age < 0` only if the clock went backwards — treat as stale rather than trust it.
        if (e != null && t - e.at in 0 until ttlMs) return e.value
        return read().also { entry = Entry(it, now()) }
    }

    /** Drop the cached value so the next [get] reads afresh. */
    fun invalidate() {
        entry = null
    }
}
