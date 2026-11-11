package org.fivesevenfive.wearvian.service

/**
 * Pure decision logic for the parked-idle → passive drop ([PresenceService.monitorStableIdle]),
 * extracted so it is unit-testable without the Android service (like [StartupPolicy]).
 */
object ParkedIdlePolicy {

    /** In a driving gear. [VehicleStatus.Gear.UNKNOWN] (no telemetry yet) is not treated as in gear. */
    fun inGear(gear: VehicleStatus.Gear): Boolean =
        gear == VehicleStatus.Gear.DRIVE || gear == VehicleStatus.Gear.REVERSE || gear == VehicleStatus.Gear.NEUTRAL

    /**
     * True iff [s] may count toward the parked-idle window. A stale/absent state can't be judged,
     * and being in gear (or in driving-doze) is activity: a steady drive must never idle the key to
     * passive, because passive tears down the 0x1c stream that catches the return to Park.
     */
    fun eligible(s: VehicleStatus.State, driving: Boolean): Boolean =
        s.valid && s.live && !driving && !inGear(s.gear)

    /** Fields that signal user activity; SoC / range / cabin-temp drift passively and are excluded. */
    fun snapshot(s: VehicleStatus.State): List<Any?> =
        listOf(s.locked, s.anyDoorOpen, s.anyWindowOpen, s.frunkOpen, s.liftgateOpen, s.chargeState, s.asleep, s.gear)
}

/**
 * Tracks which of the vehicle's advertising nodes the passive proximity scan currently sees, so a
 * departure is only counted when ALL of them are lost (one edge-of-range sensor dropping out while
 * the others are still visible is not a departure).
 *
 * Also time-stamps the moment the set became empty with a caller-supplied `elapsedRealtime()`, so
 * the departure decision does not depend on a coroutine timer: those count CPU-awake time only and
 * stall while the watch suspends in passive mode. A FIRST_MATCH after the set has been empty for at
 * least [debounceMs] is a genuine return even if the debounce timer never got to fire.
 *
 * Backstop for a dropped MATCH_LOST: if any node was lost and nothing has been sighted since for at
 * least [strandMs], the next FIRST_MATCH is a return even though some node is still (believed) seen.
 * Without it, one MATCH_LOST the radio never delivers leaves that node in the set forever, the set
 * never empties, the car never counts as gone, and its return is ignored, stranding the key in
 * "Parked nearby" with passive unlock dead until the app is opened. Like the debounce, it is judged
 * lazily from timestamps at the next sighting, so it costs no timer or wake lock. The price is that a
 * genuinely edge-of-range node that drops out for longer than [strandMs] and comes back reads as a
 * return (one wake to active, which the parked-idle drop undoes), the same as before the all-nodes
 * rule but [strandMs] later.
 *
 * Not thread-safe: confined to the service's single-thread scope.
 */
class ProximityTracker(private val debounceMs: Long, private val strandMs: Long) {

    enum class Found {
        /** The car came back after a confirmed (or elapsed) departure → wake to active. */
        RETURN,
        /** Still parked nearby (or back within the debounce) → stay passive, cancel any countdown. */
        PRESENT,
    }

    private val seen = HashSet<String>()
    private var emptySinceMs: Long? = null
    /** First loss (while other nodes were still seen) with no sighting since; see [strandMs]. */
    private var partialLossSinceMs: Long? = null

    val size: Int get() = seen.size

    fun reset() {
        seen.clear()
        emptySinceMs = null
        partialLossSinceMs = null
    }

    /**
     * A node matched (FIRST_MATCH). [departureConfirmed] is the service's `seenDeparture`.
     */
    fun found(address: String, nowMs: Long, departureConfirmed: Boolean): Found {
        val wasEmpty = seen.isEmpty()
        seen.add(address)
        val emptyFor = emptySinceMs?.let { nowMs - it }
        val strandedFor = partialLossSinceMs?.let { nowMs - it }
        emptySinceMs = null
        partialLossSinceMs = null // any sighting is fresh evidence; a later loss restarts the clock
        val gone = departureConfirmed ||
            (wasEmpty && emptyFor != null && emptyFor >= debounceMs) ||
            (strandedFor != null && strandedFor >= strandMs)
        return if (gone) Found.RETURN else Found.PRESENT
    }

    /**
     * A node was lost (MATCH_LOST). @return true iff this emptied the set, i.e. the departure
     * countdown should start now. A loss for a node we never saw is ignored.
     */
    fun lost(address: String, nowMs: Long): Boolean {
        if (!seen.remove(address)) return false
        if (seen.isNotEmpty()) {
            if (partialLossSinceMs == null) partialLossSinceMs = nowMs
            return false
        }
        partialLossSinceMs = null // the normal all-lost debounce takes over
        emptySinceMs = nowMs
        return true
    }

    /**
     * Visibility is unknown (the scan was down, e.g. across a Bluetooth restart): treat every node
     * as lost as of [nowMs], so the fresh scan's first sighting within the debounce reads as
     * "still here" and silence for the debounce reads as a departure.
     */
    fun assumeAllLost(nowMs: Long) {
        seen.clear()
        partialLossSinceMs = null
        emptySinceMs = nowMs
    }

    /** True iff every node has been gone for at least [debounceMs] as of [nowMs]. */
    fun departureDue(nowMs: Long): Boolean {
        val since = emptySinceMs ?: return false
        return seen.isEmpty() && nowMs - since >= debounceMs
    }
}
