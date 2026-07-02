package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Re-arms the mobile key after an app upgrade (MY_PACKAGE_REPLACED), so the key comes back ACTIVE
 * without the user having to open the app. Starts [PresenceService] when the key is armed
 * ([SettingsStore.keyArmed], which persists across the upgrade). Starting a foreground service from
 * MY_PACKAGE_REPLACED is one of the documented exemptions to the background FGS-start restriction.
 * If not enrolled, the service self-stops.
 *
 * We intentionally do NOT listen for BOOT_COMPLETED — after a reboot the key re-arms when the app is
 * next launched (SetupViewModel restores presence from keyArmed).
 */
class UpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SettingsStore(context).keyArmed) {
            DebugLog.add("presence: upgraded — key not armed, staying off")
            return
        }
        DebugLog.add("presence: upgraded — re-arming key (active)")
        PresenceService.start(context)
    }
}
