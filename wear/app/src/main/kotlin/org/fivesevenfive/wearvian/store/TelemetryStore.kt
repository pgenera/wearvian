package org.fivesevenfive.wearvian.store

import android.content.Context

/**
 * Last-known vehicle telemetry (battery state-of-charge and estimated range), persisted to plain
 * prefs. [org.fivesevenfive.wearvian.service.VehicleStatus] is in-memory only, but the watch-face
 * complications ([org.fivesevenfive.wearvian.complication]) are queried by the system at arbitrary
 * times — usually when our foreground-service process is dead — so they read the last value from
 * here instead. Written (debounced) from PresenceService whenever the live status changes.
 *
 * Not secret (SoC/range), so plain SharedPreferences like [SettingsStore]. A sentinel of -1 means
 * "never recorded"; [updatedAtMs] is wall-clock time of the last write, for an age display if wanted.
 */
class TelemetryStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_telemetry", Context.MODE_PRIVATE)

    /** Last-known state of charge, integer percent; null if never recorded. */
    val socPercent: Int? get() = prefs.getInt(KEY_SOC, UNSET).takeIf { it != UNSET }

    /** Last-known estimated range, km; null if never recorded. */
    val rangeKm: Int? get() = prefs.getInt(KEY_RANGE, UNSET).takeIf { it != UNSET }

    /** Wall-clock time (epoch ms) of the last write; 0 if never recorded. */
    val updatedAtMs: Long get() = prefs.getLong(KEY_UPDATED_AT, 0L)

    /** Persist the latest telemetry. Either value may be null (frame too short to carry it). */
    fun save(socPercent: Int?, rangeKm: Int?) {
        prefs.edit()
            .putInt(KEY_SOC, socPercent ?: UNSET)
            .putInt(KEY_RANGE, rangeKm ?: UNSET)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val KEY_SOC = "soc_percent"
        const val KEY_RANGE = "range_km"
        const val KEY_UPDATED_AT = "updated_at_ms"
        const val UNSET = -1
    }
}
