package org.fivesevenfive.wearvian.service

/**
 * What the presence service should do when it reads the keyguard ([PresenceService] syncLockState),
 * kept pure so it is JVM unit-tested. The read happens on a screen/user-present broadcast, on the
 * 2 s backstop poll, and at the top of every transition that used to trust the polled field
 * (a passive wake, a departure, Bluetooth coming back) — so a stale field never decides anything.
 */
object LockSyncPolicy {
    enum class Action {
        /** The keyguard agrees with what the service believes: nothing to do. */
        NONE,
        /** Newly locked: tear down all BLE (anti-theft), remembering whether we were passive. */
        LOCK,
        /** Newly unlocked, were passive when the lock came: come back up passive. */
        UNLOCK_TO_PASSIVE,
        /** Newly unlocked otherwise: restore active presence. */
        UNLOCK_TO_ACTIVE,
    }

    /**
     * A manual "go passive" request (the button) reaching an already-running service. While the
     * watch is locked the key is unavailable and stays off (all BLE torn down): the request is
     * ignored — no passive scan, autoConnect or session — and not remembered for the unlock.
     * [keyguardLocked] must be a fresh keyguard read (after [decide]'s transition has run).
     */
    fun acceptManualPassive(keyguardLocked: Boolean): Boolean = !keyguardLocked

    fun decide(keyguardLocked: Boolean, believedLocked: Boolean, wasPassive: Boolean): Action = when {
        keyguardLocked == believedLocked -> Action.NONE
        keyguardLocked -> Action.LOCK
        wasPassive -> Action.UNLOCK_TO_PASSIVE
        else -> Action.UNLOCK_TO_ACTIVE
    }
}
