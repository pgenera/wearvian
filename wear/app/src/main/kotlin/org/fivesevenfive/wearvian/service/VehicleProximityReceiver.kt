package org.fivesevenfive.wearvian.service

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.loge

/**
 * Fired by the OS (via [ProximityWake]'s PendingIntent scan) when the vehicle's beacon
 * comes into range while we're idling passively. Cancels the offloaded scan and brings
 * the presence service back up. See docs/proximity-wake.md.
 *
 * NOTE: on Android 12+, starting a foreground service from a background broadcast can
 * throw ForegroundServiceStartNotAllowedException. If that surfaces on-device, the
 * fallback is CompanionDeviceManager.onDeviceAppeared, which is allowlisted to start an
 * FGS (see the doc). We log the failure here so it's visible in the debug console.
 */
class VehicleProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        @Suppress("DEPRECATION")
        val results = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        val who = results?.firstOrNull()?.device?.address
        DebugLog.add("proximity: vehicle nearby (err=$errorCode dev=${who ?: "?"}) → start presence")
        // Do NOT disarm here: PresenceService.onStartCommand disarms once it's actually
        // running. If the FGS start is blocked (Android 12+ background limit), leaving the
        // scan armed lets the next approach retry instead of the app going permanently dark.
        runCatching { PresenceService.start(context) }
            .onFailure {
                loge("proximity: FGS start blocked", it)
                DebugLog.add("proximity: FGS start blocked — ${it.message} (wake left armed to retry)")
            }
    }
}
