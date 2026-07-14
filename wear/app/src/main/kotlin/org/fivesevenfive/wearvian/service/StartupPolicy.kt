package org.fivesevenfive.wearvian.service

/**
 * What kicked off a [PresenceService] start. Mapped from the start intent's action by the service;
 * kept Android-free here so the startup decision is unit-testable without a real Intent.
 */
enum class StartTrigger {
    /** App upgrade — a near-instant `MY_PACKAGE_REPLACED` process swap (`ACTION_UPGRADE_RESTORE`). */
    UPGRADE_RESTORE,

    /** Manual "go passive" button (`ACTION_GO_PASSIVE`). */
    GO_PASSIVE,

    /** Any other start: fresh app open, OS reboot, bond auto-start. */
    NORMAL,
}

/** The mode a fresh [PresenceService] start should come up in. */
sealed interface StartupDecision {
    /** Bring the full active presence session up (wake lock + BLE). */
    data object Active : StartupDecision

    /**
     * Come up passive (stay-alive service + proximity watch, no wake lock). [restoreSeenDeparture]
     * carries the persisted departure sub-state so a parked-nearby key doesn't wrongly treat itself
     * as already-departed (which would arm a wake that bounces straight back to active).
     */
    data class Passive(val restoreSeenDeparture: Boolean) : StartupDecision

    /** Manual "go passive" button: passive with no upgrade-restore semantics. */
    data object ManualPassive : StartupDecision

    /**
     * Watch is locked (off wrist) at startup — keep BLE down until unlock. [wasPassive] records
     * whether unlock should restore passive (true only when this start would otherwise have restored
     * passive idle), so the anti-theft path doesn't snap a parked-nearby key back to active on unlock.
     */
    data class Locked(val wasPassive: Boolean) : StartupDecision
}

/**
 * Pure decision for a fresh [PresenceService] start, extracted from `onStartCommand` so it can be
 * unit-tested without the Android service.
 *
 * The upgrade-restore rule (see [org.fivesevenfive.wearvian.store.SettingsStore.passiveIdle]): only
 * an app-upgrade restart resumes the persisted passive idle — that swap is near-instant, so a
 * parked-nearby key must not snap back to active (re-acquiring the wake lock + BLE and losing the
 * parked-idle battery win). EVERY other start deliberately ignores the persisted passive and comes
 * up active: after a longer gap the car may have come and gone, and a stale "parked nearby" would
 * wrongly ignore its real return. A locked watch overrides all of it (BLE stays down until unlock).
 */
object StartupPolicy {
    fun resolve(
        trigger: StartTrigger,
        locked: Boolean,
        passiveIdle: Boolean,
        seenDeparture: Boolean,
    ): StartupDecision {
        // passiveIdle is only meaningful on an upgrade restore; any other trigger ignores it.
        val restorePassive = trigger == StartTrigger.UPGRADE_RESTORE && passiveIdle
        return when {
            locked -> StartupDecision.Locked(wasPassive = restorePassive)
            trigger == StartTrigger.GO_PASSIVE -> StartupDecision.ManualPassive
            restorePassive -> StartupDecision.Passive(restoreSeenDeparture = seenDeparture)
            else -> StartupDecision.Active
        }
    }
}
