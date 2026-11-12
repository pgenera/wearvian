package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
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
 */
class UpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SettingsStore(context).keyArmed) {
            DebugLog.add("presence: upgraded — key not armed, staying off")
            return
        }
        DebugLog.add("presence: upgraded — re-arming key (resuming prior mode)")
        PresenceService.startAfterUpgrade(context)
    }
}
