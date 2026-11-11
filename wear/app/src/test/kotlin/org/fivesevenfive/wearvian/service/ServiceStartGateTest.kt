package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.ServiceStartGate.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStartGateTest {

    private fun check(restart: Boolean = false, armed: Boolean = true, perms: Boolean = true) =
        ServiceStartGate.check(isSystemRestart = restart, keyArmed = armed, hasBlePermissions = perms)

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
}
