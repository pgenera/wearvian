package org.fivesevenfive.wearvian.service

/**
 * Whether [PresenceService.onStartCommand] may go foreground at all. Pure (no Android types) so the
 * rules are JVM-testable; the service just enacts the result.
 *
 * - From Android 14 a `connectedDevice` foreground service needs a granted Bluetooth runtime
 *   permission, or `startForeground` throws `SecurityException` and kills the process. The key also
 *   can't work without SCAN + CONNECT, so both are required.
 * - A sticky restart by the system (null intent, after a low-memory kill) is only honored while the
 *   key is still armed. That restart comes up ACTIVE, the same as an app open: after an unknown gap a
 *   persisted "parked nearby" can't be trusted (see [StartupPolicy]).
 * - Nor is it honored during Wear OS bedtime mode ([BedtimePolicy.allowBackgroundStart]): the
 *   service then disarms the key, matching the bedtime key-off of a running service. Explicit starts
 *   are the user's own action and ignore bedtime.
 */
object ServiceStartGate {

    enum class Result { PROCEED, NO_BLE_PERMISSION, NOT_ARMED, BEDTIME }

    fun check(isSystemRestart: Boolean, keyArmed: Boolean, hasBlePermissions: Boolean, bedtime: BedtimeState): Result = when {
        !hasBlePermissions -> Result.NO_BLE_PERMISSION
        isSystemRestart && !keyArmed -> Result.NOT_ARMED
        isSystemRestart && !BedtimePolicy.allowBackgroundStart(bedtime) -> Result.BEDTIME
        else -> Result.PROCEED
    }
}
