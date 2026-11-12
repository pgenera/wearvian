package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reliability audit 2026-11-12, finding R3. FAILS on 5271d02.
 *
 * PresenceService arms the passive scan in the "parked nearby" sub-state (seenDeparture = false)
 * with proximity.reset() and nothing else. If the scan then reports no node before the watch leaves
 * range (weak signal, a sleeping car advertising slowly, the wearer walking off right away), there is
 * never a MATCH_LOST, so the departure is never confirmed and the PK autoConnect is never armed.
 * The tracker has no "armed at" time, so the first sighting is read as "the car we are parked beside"
 * however long ago the scan was armed: the car's real return is ignored and the key stays in
 * "Parked nearby" until the app is opened or a command is tapped.
 *
 * Only the Bluetooth-on rebuild calls assumeAllLost(); every other arm in this sub-state is blind.
 */
class ReliabilityAuditProximityTest {

    private val debounceMs = 30_000L
    private val strandMs = 180_000L

    @Test
    fun firstSightingHoursAfterArmingWithNothingSeenSinceIsAReturn() {
        val t = ProximityTracker(debounceMs, strandMs)
        t.reset(nowMs = 0L) // what armProximityScan() does when the scan is (re)armed while parked nearby (armed at t=0)
        // No FIRST_MATCH and no MATCH_LOST for two hours: the scan never saw the car at all.
        val twoHoursMs = 2 * 60 * 60_000L
        assertEquals(
            ProximityTracker.Found.RETURN,
            t.found("AA:BB:CC:DD:EE:01", twoHoursMs, departureConfirmed = false),
        )
    }
}
