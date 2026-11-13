package org.fivesevenfive.wearvian.service

/**
 * Burst decision for a key enrolled as a WATCH ([PresenceService.monitorWatchPresence]), kept pure
 * so it is JVM unit-tested. The car does no passive lock/unlock for a watch key, so heartbeats run
 * only during a burst around the drive window; this decides when one runs.
 *
 * Triggers:
 *  - a door-open edge, or an asleep→awake edge, in live 0x1c state;
 *  - the FIRST live status after the key leaves passive or locked (or starts), while the car is
 *    awake and parked with a door open or unlocked — a session that came up late (e.g. after a
 *    passive wake by a one-shot unlock) has missed the door/wake edges, but this is still the
 *    drive window. Only that first one: a link blip in standby beside an unlocked, awake car
 *    must not start a burst each time it comes back;
 *  - a manual command ([lastManualMs], from [org.fivesevenfive.wearvian.ble.CommandBus]: a live-queue
 *    submit or a successful one-shot).
 *
 * A burst holds for [burstMs] after the last trigger. Manual commands burst unconditionally (a
 * queued command is only drained while heartbeats run); the other triggers only while parked and
 * awake. Only LIVE state counts, so the stale last-known state kept across passive/lock can neither
 * trigger nor suppress a burst.
 */
class WatchBurstPolicy(private val burstMs: Long) {
    private var prevDoorOpen = false
    private var prevAsleep = false
    private var prevFresh = false
    private var lastTriggerMs = 0L
    /** The status-live trigger is still available (armed at start and by [reset], used up by the first live status). */
    private var liveTriggerArmed = true

    /** What [evaluate] decided, and the trigger that fired on this sample (for the log), if any. */
    data class Decision(val wantBurst: Boolean, val trigger: String?)

    /** Locked / passive / Bluetooth off: forget the edges (the next live state starts afresh). */
    fun reset() {
        prevDoorOpen = false
        prevAsleep = false
        prevFresh = false
        liveTriggerArmed = true
    }

    fun evaluate(
        fresh: Boolean, doorOpen: Boolean, asleep: Boolean, unlocked: Boolean, inGear: Boolean,
        lastManualMs: Long, nowMs: Long,
    ): Decision {
        var trigger: String? = null
        if (fresh) {
            trigger = when {
                doorOpen && !prevDoorOpen -> "door opened"
                !asleep && prevAsleep -> "vehicle woke"
                liveTriggerArmed && !prevFresh && !asleep && !inGear && (doorOpen || unlocked) -> "status live, car awake + open"
                else -> null
            }
            liveTriggerArmed = false // the first live status after a reset has been seen
            prevDoorOpen = doorOpen
            prevAsleep = asleep
        }
        prevFresh = fresh
        if (trigger != null) lastTriggerMs = nowMs
        val carAsleep = fresh && asleep
        val driving = fresh && inGear
        val recentManual = lastManualMs > 0L && nowMs - lastManualMs < burstMs
        val recentPassive = lastTriggerMs > 0L && nowMs - lastTriggerMs < burstMs
        return Decision(recentManual || (recentPassive && !carAsleep && !driving), trigger)
    }
}
