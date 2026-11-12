package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBurstPolicyTest {
    private val burstMs = 180_000L
    private val p = WatchBurstPolicy(burstMs)

    private fun eval(
        now: Long, fresh: Boolean = true, door: Boolean = false, asleep: Boolean = false,
        unlocked: Boolean = false, inGear: Boolean = false, manual: Long = 0L,
    ) = p.evaluate(fresh, door, asleep, unlocked, inGear, manual, now)

    @Test
    fun oneShotUnlockThenLateSessionBurstsViaTheManualSignal() {
        // Unlocked by a one-shot at t=10 s (noteManual), session up at t=25 s, door already shut.
        val d = eval(now = 25_000L, door = false, unlocked = false, manual = 10_000L)
        assertTrue(d.wantBurst)
        assertTrue("manual bursts even with the car asleep", eval(now = 26_000L, asleep = true, manual = 10_000L).wantBurst)
        assertFalse("and ends after the burst window", eval(now = 10_000L + burstMs, asleep = true, manual = 10_000L).wantBurst)
    }

    @Test
    fun statusComingLiveWithTheCarAwakeAndUnlockedIsATrigger() {
        assertFalse(eval(now = 1_000L, fresh = false).wantBurst)
        val d = eval(now = 2_000L, unlocked = true) // first fresh frame: car awake, parked, unlocked
        assertTrue(d.wantBurst)
        assertEquals("status live, car awake + open", d.trigger)
        assertTrue(eval(now = 2_000L + burstMs - 1, unlocked = true).wantBurst)
        assertFalse(eval(now = 2_000L + burstMs, unlocked = true).wantBurst)
    }

    @Test
    fun statusComingLiveLockedAndShutIsNotATrigger() {
        val d = eval(now = 2_000L, unlocked = false, door = false)
        assertFalse(d.wantBurst)
        assertNull(d.trigger)
    }

    @Test
    fun statusComingLiveAsleepOrInGearIsNotATrigger() {
        assertFalse(eval(now = 2_000L, unlocked = true, asleep = true).wantBurst)
        p.reset()
        assertFalse(eval(now = 3_000L, unlocked = true, inGear = true).wantBurst)
    }

    @Test
    fun onlyTheFirstLiveFrameCountsNotEverySteadyFrame() {
        eval(now = 1_000L, unlocked = true) // trigger
        val later = 1_000L + burstMs + 1
        val d = eval(now = later, unlocked = true)
        assertFalse("steady unlocked state after the window is not a new trigger", d.wantBurst)
        assertNull(d.trigger)
    }

    @Test
    fun goingStaleThenLiveAgainRetriggers() {
        eval(now = 1_000L, unlocked = true)
        eval(now = 1_000L + burstMs + 1, fresh = false)
        val d = eval(now = 1_000L + burstMs + 2, unlocked = true)
        assertTrue(d.wantBurst)
    }

    @Test
    fun doorOpenEdgeStillTriggers() {
        eval(now = 1_000L) // live, shut + locked: nothing
        val d = eval(now = 2_000L, door = true)
        assertEquals("door opened", d.trigger)
        assertTrue(d.wantBurst)
    }

    @Test
    fun wakeEdgeStillTriggers() {
        eval(now = 1_000L, asleep = true)
        val d = eval(now = 2_000L, asleep = false)
        assertEquals("vehicle woke", d.trigger)
        assertTrue(d.wantBurst)
    }

    @Test
    fun passiveTriggersStopInGearOrAsleep() {
        eval(now = 1_000L)
        eval(now = 2_000L, door = true)
        assertFalse(eval(now = 3_000L, door = true, inGear = true).wantBurst)
        assertFalse(eval(now = 4_000L, door = true, asleep = true).wantBurst)
    }

    @Test
    fun staleStateNeitherTriggersNorSuppresses() {
        assertFalse(eval(now = 1_000L, fresh = false, door = true, unlocked = true).wantBurst)
        // Stale "asleep" must not suppress a manual burst (it never does) nor a recent passive one.
        eval(now = 2_000L, door = false)
        eval(now = 3_000L, door = true)
        assertTrue(eval(now = 4_000L, fresh = false, asleep = true).wantBurst)
    }

    @Test
    fun resetForgetsEdges() {
        eval(now = 1_000L, door = true) // first live frame with door open
        p.reset()
        // After a reset the first live frame is "coming live" again.
        assertEquals("door opened", eval(now = burstMs * 2, door = true).trigger)
    }
}
