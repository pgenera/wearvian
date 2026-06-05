package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import org.fivesevenfive.wearvian.service.VehicleProximityReceiver
import org.fivesevenfive.wearvian.util.DebugLog
import java.util.UUID

/**
 * Hardware-offloaded "is the car nearby?" watch. Registers a BLE scan with the system
 * via a [PendingIntent] so the OS holds it even after our process is killed, and
 * broadcasts to [VehicleProximityReceiver] when the vehicle's beacon (its VAS service
 * UUID, or a known sensor/PRIMARY MAC) first comes into range. This lets the presence
 * service idle at near-zero battery and auto-re-arm on approach. See
 * docs/proximity-wake.md.
 */
@SuppressLint("MissingPermission")
object ProximityWake {

    const val ACTION = "org.fivesevenfive.wearvian.VEHICLE_NEARBY"
    private const val REQUEST_CODE = 0x5715 // "RVN-ish"; identifies our PendingIntent

    /**
     * Arm the offloaded scan for the vehicle. Filters on [serviceUuid] (the sensors'
     * advertised VAS id) and any known [addresses]. Returns true iff the system accepted
     * the scan. Tries an efficient first-match (hardware-offloaded) scan, falling back to
     * all-matches if the chip doesn't support offloaded batching.
     */
    fun arm(context: Context, serviceUuid: UUID?, addresses: Set<String>): Boolean {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner ?: run {
            DebugLog.add("proximity: no scanner (BT off?) — can't arm")
            return false
        }
        val filters = buildList {
            serviceUuid?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
            addresses.forEach { mac ->
                runCatching { add(ScanFilter.Builder().setDeviceAddress(mac).build()) }
            }
        }
        if (filters.isEmpty()) {
            DebugLog.add("proximity: no filters (no service UUID or MACs) — can't arm")
            return false
        }

        fun start(callbackType: Int): Int {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(callbackType)
                .apply {
                    if (callbackType == ScanSettings.CALLBACK_TYPE_FIRST_MATCH) {
                        setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                        setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    }
                }
                .build()
            return runCatching { scanner.startScan(filters, settings, pendingIntent(context)) }
                .getOrElse { DebugLog.add("proximity: startScan threw — ${it.message}"); -1 }
        }

        var rc = start(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
        if (rc != 0) {
            DebugLog.add("proximity: FIRST_MATCH rc=$rc; retrying ALL_MATCHES")
            rc = start(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
        DebugLog.add("proximity: arm rc=$rc (${filters.size} filters)")
        return rc == 0
    }

    /** Cancel the offloaded scan (e.g. once presence is back up). */
    fun disarm(context: Context) {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(pendingIntent(context)) }
        DebugLog.add("proximity: disarmed")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // MUTABLE so the system can fill in the scan-result extras on delivery.
        val intent = Intent(context, VehicleProximityReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
