package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.ServiceStartGate.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStartGateTest {

    private fun check(
        restart: Boolean = false,
        armed: Boolean = true,
        perms: Boolean = true,
        bedtime: BedtimeState = BedtimeState.INACTIVE,
    ) = ServiceStartGate.check(isSystemRestart = restart, keyArmed = armed, hasBlePermissions = perms, bedtime = bedtime)

    @Test
    fun normalStartWithPermissionsProceeds() = assertEquals(Result.PROCEED, check())

    @Test
    fun armedSystemRestartProceeds() = assertEquals(Result.PROCEED, check(restart = true))

    @Test
    fun missingPermissionNeverGoesForeground() {
        assertEquals(Result.NO_BLE_PERMISSION, check(perms = false))
        assertEquals(Result.NO_BLE_PERMISSION, check(restart = true, perms = false))
        assertEquals(Result.NO_BLE_PERMISSION, check(restart = true, armed = false, perms = false))
    }

    @Test
    fun systemRestartAfterKeyTurnedOffStops() = assertEquals(Result.NOT_ARMED, check(restart = true, armed = false))

    @Test
    fun explicitStartDoesNotRequireArmedFlag() {
        // Explicit starts set keyArmed themselves (or are the user's own action); only the system's
        // sticky restart is gated on it.
        assertEquals(Result.PROCEED, check(armed = false))
    }

    @Test
    fun systemRestartDuringBedtimeStops() {
        assertEquals(Result.BEDTIME, check(restart = true, bedtime = BedtimeState.ACTIVE))
        // A key already off stays NOT_ARMED; a missing permission still wins.
        assertEquals(Result.NOT_ARMED, check(restart = true, armed = false, bedtime = BedtimeState.ACTIVE))
        assertEquals(Result.NO_BLE_PERMISSION, check(restart = true, perms = false, bedtime = BedtimeState.ACTIVE))
        // Unreadable bedtime fails safe to the old behavior.
        assertEquals(Result.PROCEED, check(restart = true, bedtime = BedtimeState.UNKNOWN))
    }

    @Test
    fun explicitStartIgnoresBedtime() = assertEquals(Result.PROCEED, check(bedtime = BedtimeState.ACTIVE))
}
