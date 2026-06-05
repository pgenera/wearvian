package org.fivesevenfive.wearvian.store

import android.content.Context

/** User-facing app settings (plain prefs; nothing secret). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_settings", Context.MODE_PRIVATE)

    /**
     * Auto power-save: after a stretch with no vehicle link, drop to passive (release the
     * wake lock, stop the foreground service) and re-arm via the offloaded proximity scan.
     * Default on. See docs/proximity-wake.md.
     */
    var proximityWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXIMITY_WAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_PROXIMITY_WAKE, value).apply()

    private companion object {
        const val KEY_PROXIMITY_WAKE = "proximity_wake_enabled"
    }
}
