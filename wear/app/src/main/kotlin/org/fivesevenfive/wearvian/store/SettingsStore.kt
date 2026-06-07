package org.fivesevenfive.wearvian.store

import android.content.Context

/** User-facing app settings (plain prefs; nothing secret). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_settings", Context.MODE_PRIVATE)

    /**
     * Auto power-save: after a stretch with no vehicle link, drop to passive — the foreground
     * service + notification stay up but the wake lock is released and BLE is torn down, and an
     * in-process offloaded scan watches for the car's approach to rebuild the link. **Default
     * OFF.** See docs/proximity-wake.md.
     */
    var proximityWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXIMITY_WAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXIMITY_WAKE, value).apply()

    /**
     * Whether the user has the mobile key armed (operational intent, not a preference).
     * Distinct from "presence service currently running": when auto power-save drops the
     * service to passive, the key is still armed — the UI shows "Key passive", not "off".
     */
    var keyArmed: Boolean
        get() = prefs.getBoolean(KEY_KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_KEY_ARMED, value).apply()

    private companion object {
        const val KEY_PROXIMITY_WAKE = "proximity_wake_enabled"
        const val KEY_KEY_ARMED = "key_armed"
    }
}
