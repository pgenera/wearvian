package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Backs the "Off" action on the presence notification: disarms the mobile key and stops
 * the service. Stopping the service (allowed from any context, unlike *starting* an FGS)
 * clears the notification and stops the passive proximity watch via [PresenceService.onDestroy].
 */
class KeyOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.add("presence: key OFF from notification")
        SettingsStore(context).keyArmed = false
        PresenceService.stop(context)
    }
}
