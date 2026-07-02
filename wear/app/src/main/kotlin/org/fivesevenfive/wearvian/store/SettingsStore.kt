package org.fivesevenfive.wearvian.store

import android.content.Context

/** User-facing app settings (plain prefs; nothing secret). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_settings", Context.MODE_PRIVATE)

    /**
     * Whether the user has the mobile key armed (operational intent, not a preference).
     * Distinct from "presence service currently running": when auto power-save drops the
     * service to passive, the key is still armed — the UI shows "Key passive", not "off".
     *
     * Defaults to ARMED when there's no stored value: with no prior state (fresh install / first
     * launch) the key should come up active, not off. Once the user explicitly toggles it, that
     * choice (true or false) is persisted and honored.
     */
    var keyArmed: Boolean
        get() = prefs.getBoolean(KEY_KEY_ARMED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEY_ARMED, value).apply()

    /**
     * Debug-only override: force the UI into R1T (truck) mode regardless of the enrolled VIN, so the
     * tailgate UI can be exercised on a non-R1T vehicle. Only togglable from the debug Settings page;
     * defaults OFF so production (no Settings page) is unaffected. See [VehicleModel].
     */
    var forceR1t: Boolean
        get() = prefs.getBoolean(KEY_FORCE_R1T, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_R1T, value).apply()

    private companion object {
        const val KEY_KEY_ARMED = "key_armed"
        const val KEY_FORCE_R1T = "force_r1t"
    }
}
