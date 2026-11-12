package org.fivesevenfive.wearvian.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class LockSecretPolicyTest {
    private var locked = false
    private var clears = 0
    private var prefetches = 0
    private val scheduled = ArrayList<Pair<Long, () -> Unit>>()
    private val cancelled = HashSet<Int>()

    private val policy = LockSecretPolicy(
        isLocked = { locked },
        clear = { clears++ },
        prefetch = { prefetches++ },
        schedule = { d, a ->
            val id = scheduled.size
            scheduled.add(d to a)
            LockSecretPolicy.Cancellable { cancelled.add(id) }
        },
        recheckDelaysMs = listOf(5L, 15L, 60L),
    )

    /** Run the most recently scheduled (still live) check. */
    private fun fireLast() {
        val id = scheduled.lastIndex
        check(id !in cancelled) { "fired a cancelled check" }
        scheduled[id].second()
    }

    @Test
    fun screenOffIsNotTheLockSignal() {
        policy.onScreenOff()
        assertEquals(0, clears)
        assertEquals(1, scheduled.size)
        assertEquals(5L, scheduled[0].first)
    }

    @Test
    fun clearsOnceARecheckSeesTheLock() {
        policy.onScreenOff()
        fireLast() // still on the wrist
        assertEquals(0, clears)
        assertEquals(15L, scheduled.last().first)
        locked = true // off-body lock lands later
        fireLast()
        assertEquals(1, clears)
        assertEquals("no further checks once cleared", 2, scheduled.size)
    }

    @Test
    fun keepsRecheckingAtTheLastDelayWhileTheScreenStaysOff() {
        policy.onScreenOff()
        repeat(6) { fireLast() } // still worn, screen off for a long time
        assertEquals(listOf(5L, 15L, 60L, 60L, 60L, 60L, 60L), scheduled.map { it.first })
        assertEquals(0, clears)
        locked = true // taken off long after the screen went off
        fireLast()
        assertEquals(1, clears)
        assertEquals("stops once cleared", 7, scheduled.size)
    }

    @Test
    fun userPresentAfterALongScreenOffCancelsTheRepeat() {
        policy.onScreenOff()
        repeat(5) { fireLast() }
        policy.onUserPresent()
        assertEquals(setOf(5), cancelled)
    }

    @Test
    fun userPresentCancelsRechecksAndPrefetches() {
        policy.onScreenOff()
        policy.onUserPresent()
        assertEquals(setOf(0), cancelled)
        assertEquals(1, prefetches)
        // A check that was already running when cancelled must not act or reschedule.
        locked = true
        scheduled[0].second()
        assertEquals(0, clears)
        assertEquals(1, scheduled.size)
    }

    @Test
    fun userPresentWhileStillLockedDoesNotPrefetch() {
        locked = true
        policy.onUserPresent()
        assertEquals(0, prefetches)
    }

    @Test
    fun screenOnClearsOnlyWhenLocked() {
        policy.onScreenOn()
        assertEquals(0, clears)
        locked = true
        policy.onScreenOn()
        assertEquals(1, clears)
    }

    @Test
    fun repeatedScreenOffRestartsTheSchedule() {
        policy.onScreenOff()
        policy.onScreenOff()
        assertEquals(setOf(0), cancelled)
        assertEquals(5L, scheduled.last().first)
    }
}
