package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedIdlePolicyTest {

    private val parked = VehicleStatus.State(valid = true, live = true, locked = true, gear = VehicleStatus.Gear.PARK)

    @Test
    fun parkedLiveStateIsEligible() {
        assertTrue(ParkedIdlePolicy.eligible(parked, driving = false))
    }

    @Test
    fun inGearNeverGoesPassive() {
        for (g in listOf(VehicleStatus.Gear.DRIVE, VehicleStatus.Gear.REVERSE, VehicleStatus.Gear.NEUTRAL)) {
            assertFalse("gear $g", ParkedIdlePolicy.eligible(parked.copy(gear = g), driving = false))
        }
    }

    @Test
    fun drivingDozeNeverGoesPassive() {
        assertFalse(ParkedIdlePolicy.eligible(parked, driving = true))
    }

    @Test
    fun staleOrAbsentStateIsNotEligible() {
        assertFalse(ParkedIdlePolicy.eligible(parked.copy(live = false), driving = false))
        assertFalse(ParkedIdlePolicy.eligible(VehicleStatus.State(), driving = false))
    }

    @Test
    fun unknownGearIsNotInGear() {
        assertTrue(ParkedIdlePolicy.eligible(parked.copy(gear = VehicleStatus.Gear.UNKNOWN), driving = false))
    }

    @Test
    fun gearChangeBreaksTheStableSnapshot() {
        assertNotEquals(
            ParkedIdlePolicy.snapshot(parked),
            ParkedIdlePolicy.snapshot(parked.copy(gear = VehicleStatus.Gear.DRIVE)),
        )
    }

    @Test
    fun passiveDriftDoesNotBreakTheSnapshot() {
        assertEquals(
            ParkedIdlePolicy.snapshot(parked),
            ParkedIdlePolicy.snapshot(parked.copy(socPercent = 80, rangeKm = 300, cabinTempC = 21)),
        )
    }
}

class ProximityTrackerTest {

    private val debounce = 30_000L
    private val strand = 180_000L
    private val t = ProximityTracker(debounce, strand)

    @Test
    fun losingOneOfSeveralNodesIsNotADeparture() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertEquals(1, t.size)
        assertFalse(t.departureDue(100_000))
    }

    @Test
    fun losingTheLastNodeStartsTheCountdown() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertTrue(t.lost("B", 2_000))
        assertFalse(t.departureDue(2_000 + debounce - 1))
        assertTrue(t.departureDue(2_000 + debounce))
    }

    @Test
    fun quickReturnWithinDebounceIsStillParkedNearby() {
        t.found("A", 0, departureConfirmed = false)
        assertTrue(t.lost("A", 1_000))
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000 + debounce - 1, departureConfirmed = false))
        assertFalse(t.departureDue(1_000_000))
    }

    @Test
    fun returnAfterElapsedGapWakesEvenIfTheTimerNeverFired() {
        // The debounce coroutine stalled while the CPU slept; the elapsedRealtime gap still decides.
        t.found("A", 0, departureConfirmed = false)
        assertTrue(t.lost("A", 1_000))
        assertEquals(ProximityTracker.Found.RETURN, t.found("A", 1_000 + debounce, departureConfirmed = false))
    }

    @Test
    fun confirmedDepartureAlwaysWakes() {
        assertEquals(ProximityTracker.Found.RETURN, t.found("A", 0, departureConfirmed = true))
    }

    @Test
    fun firstSightingWithoutPriorLossIsParkedNearby() {
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 10_000_000, departureConfirmed = false))
    }

    @Test
    fun secondNodeAppearingWhileOneIsSeenIsNotAReturn() {
        t.found("A", 0, departureConfirmed = false)
        assertEquals(ProximityTracker.Found.PRESENT, t.found("B", 10_000_000, departureConfirmed = false))
    }

    @Test
    fun lossOfUnknownNodeIsIgnored() {
        assertFalse(t.lost("Z", 0))
        assertFalse(t.departureDue(1_000_000))
    }

    @Test
    fun assumeAllLostAfterABluetoothRestart() {
        t.found("A", 0, departureConfirmed = false)
        t.assumeAllLost(5_000)
        // Seen again quickly → still parked; never seen → departure due after the debounce.
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 5_000 + debounce - 1, departureConfirmed = false))
        t.assumeAllLost(100_000)
        assertTrue(t.departureDue(100_000 + debounce))
    }

    @Test
    fun resetClearsTheEmptyTimestamp() {
        t.found("A", 0, departureConfirmed = false)
        assertTrue(t.lost("A", 0))
        t.reset()
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000_000, departureConfirmed = false))
    }

    @Test
    fun droppedLostEventStillLetsALateReturnWake() {
        // B's MATCH_LOST never arrived, so the set never empties; A's return after the strand
        // window must still wake instead of stranding the key in "Parked nearby".
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertEquals(ProximityTracker.Found.RETURN, t.found("A", 1_000 + strand, departureConfirmed = false))
    }

    @Test
    fun droppedLostEventReturnViaTheStuckNode() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertEquals(ProximityTracker.Found.RETURN, t.found("B", 1_000 + strand, departureConfirmed = false))
    }

    @Test
    fun partialLossBackWithinStrandWindowIsParkedNearby() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000 + strand - 1, departureConfirmed = false))
        // That sighting cleared the clock: a much later sighting is not a return on its own.
        assertEquals(ProximityTracker.Found.PRESENT, t.found("C", 10_000_000, departureConfirmed = false))
    }

    @Test
    fun strandClockStartsAtTheFirstPartialLoss() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        t.found("C", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertFalse(t.lost("B", 100_000)) // a second loss doesn't push the clock back
        assertEquals(ProximityTracker.Found.RETURN, t.found("A", 1_000 + strand, departureConfirmed = false))
    }

    @Test
    fun emptyingTheSetHandsOverToTheDebounce() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 1_000))
        assertTrue(t.lost("B", 2_000))
        // Back within the debounce of the LAST loss → still parked, even though A went first.
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 2_000 + debounce - 1, departureConfirmed = false))
    }

    @Test
    fun resetAndAssumeAllLostClearTheStrandClock() {
        t.found("A", 0, departureConfirmed = false)
        t.found("B", 0, departureConfirmed = false)
        assertFalse(t.lost("A", 0))
        t.reset()
        t.found("B", 1, departureConfirmed = false)
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000_000, departureConfirmed = false))
        t.found("C", 1_000_000, departureConfirmed = false)
        assertFalse(t.lost("C", 1_000_001))
        t.assumeAllLost(1_000_002)
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000_002 + debounce - 1, departureConfirmed = false))
    }

    @Test
    fun sightingSoonAfterArmingIsParkedNearby() {
        t.reset(nowMs = 1_000L)
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 1_000L + strand - 1, departureConfirmed = false))
    }

    @Test
    fun blindArmOnlyCountsUntilTheFirstSighting() {
        t.reset(nowMs = 0L)
        t.found("A", 5_000L, departureConfirmed = false) // seen: the arm is no longer blind
        assertEquals(ProximityTracker.Found.PRESENT, t.found("B", 10_000_000L, departureConfirmed = false))
    }

    @Test
    fun unarmedResetKeepsTheOldRule() {
        t.reset()
        assertEquals(ProximityTracker.Found.PRESENT, t.found("A", 10_000_000L, departureConfirmed = false))
    }

    @Test
    fun assumeAllLostWithALongerWindow() {
        val window = 90_000L
        t.reset(nowMs = 0L)
        t.assumeAllLost(0L, window)
        assertFalse(t.departureDue(window - 1))
        assertTrue(t.departureDue(window))
        assertEquals("seen within the window → still here", ProximityTracker.Found.PRESENT, t.found("A", debounce + 1, departureConfirmed = false))
    }

    @Test
    fun aRealLossAfterALongWindowUsesThePlainDebounce() {
        t.reset(nowMs = 0L)
        t.assumeAllLost(0L, 90_000L)
        t.found("A", 10_000L, departureConfirmed = false)
        t.lost("A", 20_000L)
        assertTrue(t.departureDue(20_000L + debounce))
        assertEquals(ProximityTracker.Found.RETURN, t.found("A", 20_000L + debounce, departureConfirmed = false))
    }
}
