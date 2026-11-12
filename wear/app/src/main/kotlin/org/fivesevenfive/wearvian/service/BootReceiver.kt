package org.fivesevenfive.wearvian.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Re-arms the key after a watch reboot when it was armed ([SettingsStore.keyArmed] persists), so the
 * key isn't silently off while the tile still draws it armed. BOOT_COMPLETED is an allowed
 * background start for a `connectedDevice` foreground service. The service comes up locked (the
 * watch is locked after a reboot) and keeps BLE down until the first unlock; [ServiceStartGate]
 * still refuses an unarmed key or missing Bluetooth permission, and an un-enrolled service stops
 * itself. Not LOCKED_BOOT_COMPLETED: the settings live in credential-encrypted storage.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val armed = SettingsStore(context).keyArmed
        if (!shouldStart(intent.action, armed)) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) DebugLog.add("presence: boot — key not armed, staying off")
            return
        }
        DebugLog.add("presence: boot — re-arming key")
        PresenceService.start(context)
    }

    companion object {
        /** Pure gate (unit-tested): only a boot, and only for a key the user left armed. */
        fun shouldStart(action: String?, keyArmed: Boolean): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED && keyArmed
    }
}
