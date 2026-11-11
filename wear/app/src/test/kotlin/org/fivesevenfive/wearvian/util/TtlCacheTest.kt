package org.fivesevenfive.wearvian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtlCacheTest {
    private var clock = 1_000L
    private var source = false
    private var reads = 0
    private val cache = TtlCache(ttlMs = 1_000L, now = { clock }) { reads++; source }

    @Test
    fun repeatedGetsWithinTtlReadOnce() {
        repeat(17) { cache.get(); clock += 50 } // 17 beats over 850 ms
        assertEquals(1, reads)
    }

    @Test
    fun lockIsSeenWithinOneTtl() {
        assertFalse(cache.get())
        source = true // watch comes off the wrist
        clock += 999
        assertFalse("still within TTL", cache.get())
        clock += 1
        assertTrue("stale after TTL → re-read sees the lock", cache.get())
        assertEquals(2, reads)
    }

    @Test
    fun clockGoingBackwardsForcesReread() {
        cache.get()
        clock -= 10
        cache.get()
        assertEquals(2, reads)
    }

    @Test
    fun invalidateForcesReread() {
        cache.get()
        cache.invalidate()
        source = true
        assertTrue(cache.get())
        assertEquals(2, reads)
    }
}
