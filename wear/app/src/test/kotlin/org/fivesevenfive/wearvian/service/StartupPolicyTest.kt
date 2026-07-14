package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fresh-start mode decision (extracted from PresenceService.onStartCommand). The behaviour that
 * matters most here: an app upgrade resumes the persisted passive idle, while EVERY other start
 * ignores it and comes up active — the rule that keeps a parked-nearby key from snapping back to
 * active (wake lock + BLE) across the near-instant upgrade swap, without a stale "parked nearby"
 * surviving a longer gap (OS reboot / manual open). A locked watch overrides everything.
 */
class StartupPolicyTest {

    private fun resolve(
        trigger: StartTrigger,
        locked: Boolean = false,
        passiveIdle: Boolean = false,
        seenDeparture: Boolean = false,
    ) = StartupPolicy.resolve(trigger, locked, passiveIdle, seenDeparture)

    // --- the upgrade-restore rule (the A concern) ---

    @Test
    fun upgradeWithPersistedPassiveResumesPassive() {
        // Was idling passively when the upgrade hit → come back passive, not active.
        assertEquals(
            StartupDecision.Passive(restoreSeenDeparture = false),
            resolve(StartTrigger.UPGRADE_RESTORE, passiveIdle = true, seenDeparture = false),
        )
    }

    @Test
    fun upgradePassiveCarriesSeenDeparture() {
        // The departure sub-state is RESTORED (not recomputed) so a parked-nearby key (seenDeparture
        // = false) doesn't arm a wake that bounces straight back to active.
        assertEquals(
            StartupDecision.Passive(restoreSeenDeparture = true),
            resolve(StartTrigger.UPGRADE_RESTORE, passiveIdle = true, seenDeparture = true),
        )
        assertEquals(
            StartupDecision.Passive(restoreSeenDeparture = false),
            resolve(StartTrigger.UPGRADE_RESTORE, passiveIdle = true, seenDeparture = false),
        )
    }

    @Test
    fun upgradeWithoutPersistedPassiveComesUpActive() {
        // Was active at upgrade time → stays active. (This is why active-after-upgrade is only a bug
        // if passiveIdle was actually persisted true.)
        assertEquals(
            StartupDecision.Active,
            resolve(StartTrigger.UPGRADE_RESTORE, passiveIdle = false),
        )
    }

    @Test
    fun normalStartIgnoresPersistedPassiveAndComesUpActive() {
        // OS reboot / app open must NOT resume passive even if passiveIdle is stale-true: after a
        // longer gap the car may have come and gone, and a stale "parked nearby" would ignore its
        // real return.
        assertEquals(
            StartupDecision.Active,
            resolve(StartTrigger.NORMAL, passiveIdle = true, seenDeparture = true),
        )
    }

    // --- manual passive button ---

    @Test
    fun goPassiveIsManualPassiveRegardlessOfPersistedState() {
        assertEquals(StartupDecision.ManualPassive, resolve(StartTrigger.GO_PASSIVE))
        assertEquals(StartupDecision.ManualPassive, resolve(StartTrigger.GO_PASSIVE, passiveIdle = true))
    }

    // --- locked (anti-theft) overrides everything ---

    @Test
    fun lockedUpgradeWithPassiveRemembersToRestorePassiveOnUnlock() {
        // Locked wins (BLE stays down), but wasPassive is set so unlock resumes passive, not active.
        assertEquals(
            StartupDecision.Locked(wasPassive = true),
            resolve(StartTrigger.UPGRADE_RESTORE, locked = true, passiveIdle = true),
        )
    }

    @Test
    fun lockedUpgradeWithoutPassiveDoesNotRestorePassive() {
        assertEquals(
            StartupDecision.Locked(wasPassive = false),
            resolve(StartTrigger.UPGRADE_RESTORE, locked = true, passiveIdle = false),
        )
    }

    @Test
    fun lockedNormalStartNeverRestoresPassiveEvenWithStalePassiveIdle() {
        assertEquals(
            StartupDecision.Locked(wasPassive = false),
            resolve(StartTrigger.NORMAL, locked = true, passiveIdle = true),
        )
    }

    @Test
    fun lockedWinsOverGoPassive() {
        // Locked is checked first, so a manual go-passive while locked still just stays locked.
        assertEquals(
            StartupDecision.Locked(wasPassive = false),
            resolve(StartTrigger.GO_PASSIVE, locked = true),
        )
    }
}
