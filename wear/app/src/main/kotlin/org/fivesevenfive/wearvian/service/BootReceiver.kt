package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Re-arms the mobile key after a device reboot or an app upgrade, so the user doesn't have to open
 * the app to bring the key back — after either event it should be ACTIVE, not off.
 *
 * Starts [PresenceService] when the key was armed ([SettingsStore.keyArmed], which persists across
 * both events). Starting a foreground service from BOOT_COMPLETED / MY_PACKAGE_REPLACED is one of the
 * documented exemptions to Android's background FGS-start restriction. If the key wasn't armed (never
 * set up, or the user turned it off) we stay off; if not enrolled, the service self-stops.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!SettingsStore(context).keyArmed) {
                    DebugLog.add("presence: ${intent.action} — key not armed, staying off")
                    return
                }
                DebugLog.add("presence: ${intent.action} — re-arming key (active)")
                PresenceService.start(context)
            }
        }
    }
}
