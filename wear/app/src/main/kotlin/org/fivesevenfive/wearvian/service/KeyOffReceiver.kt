package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.ble.CompanionManager
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Backs the "Off" action on the presence notification: disarms the mobile key and stops
 * the service. Stopping the service (allowed from any context, unlike *starting* an FGS)
 * clears the notification via [PresenceService.onDestroy]. Also stops CDM presence
 * observation so passive mode can't re-wake a deliberately-off key.
 */
class KeyOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.add("presence: key OFF from notification")
        SettingsStore(context).keyArmed = false
        CompanionManager(context).stopObserving()
        PresenceService.stop(context)
    }
}
