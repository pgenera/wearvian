package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.tile.TileRefresher
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Re-arms the mobile key after an app upgrade (MY_PACKAGE_REPLACED), without the user having to open
 * the app. Starts [PresenceService] via [PresenceService.startAfterUpgrade] when the key is armed
 * ([SettingsStore.keyArmed], which persists across the upgrade) — which resumes the PRIOR mode:
 * active, or (because an upgrade is a near-instant restart) the persisted passive idle, so a
 * parked-nearby key doesn't snap back to active. Starting a foreground service from
 * MY_PACKAGE_REPLACED is one of the documented exemptions to the background FGS-start restriction.
 * If not enrolled, the service self-stops.
 *
 * A reboot is handled separately by [BootReceiver] (BOOT_COMPLETED, also gated on keyArmed), which
 * starts the key normally — active, or locked until the first unlock — not resuming passive idle.
 *
 * An upgrade during Wear OS bedtime mode doesn't bring the key up and disarms it instead — the same
 * result as the running service's bedtime key-off ([BedtimePolicy]).
 */
class UpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = SettingsStore(context)
        val armed = settings.keyArmed
        if (!shouldStart(intent.action, armed, BedtimeMode.read(context))) {
            if (armed) {
                DebugLog.add("presence: upgraded during bedtime mode — turning key OFF")
                settings.keyArmed = false
                TileRefresher.refresh(context)
            } else {
                DebugLog.add("presence: upgraded — key not armed, staying off")
            }
            return
        }
        DebugLog.add("presence: upgraded — re-arming key (resuming prior mode)")
        PresenceService.startAfterUpgrade(context)
    }

    companion object {
        /** Pure gate (unit-tested): only an upgrade, only for an armed key, not during bedtime. */
        fun shouldStart(action: String?, keyArmed: Boolean, bedtime: BedtimeState): Boolean =
            action == Intent.ACTION_MY_PACKAGE_REPLACED && keyArmed && BedtimePolicy.allowBackgroundStart(bedtime)
    }
}
