package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import org.fivesevenfive.wearvian.store.VehicleAddressStore
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Hardware-offloaded "is the car nearby?" watch used by the stay-alive passive mode. While idle
 * the foreground service stays up (so we never need a background FGS-start, which Android blocks)
 * but releases the wake lock and tears down the BLE sessions; this registers a low-power offloaded
 * scan whose match is delivered to an **in-process** [ScanCallback]. The chip does the filtering
 * and wakes the app processor on a match, so the [org.fivesevenfive.wearvian.service.PresenceService]
 * can rebuild the link on approach. See docs/proximity-wake.md.
 *
 * (CompanionDeviceManager would be the OS-blessed path, but Wear OS forbids 3p apps from creating
 * CDM associations, so we use a plain offloaded scan — which 3p apps may do.)
 */
@SuppressLint("MissingPermission")
object ProximityWake {

    /**
     * Arm an in-process offloaded scan for the vehicle: filters on the VAS service UUID (how the
     * modules advertise) plus every MAC we've learned, low-power, first-match. Delivers to
     * [callback]. Returns true iff the system accepted the scan.
     */
    fun scanForVehicle(context: Context, vasVehicleId: String, callback: ScanCallback): Boolean {
        val scanner = scanner(context) ?: run {
            DebugLog.add("proximity: no scanner (BT off?) — can't watch")
            return false
        }
        val filters = buildList {
            RivianBle.vehicleServiceUuid(vasVehicleId)?.let {
                add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
            }
            VehicleAddressStore(context).load().forEach { mac ->
                runCatching { add(ScanFilter.Builder().setDeviceAddress(mac).build()) }
            }
        }
        if (filters.isEmpty()) {
            DebugLog.add("proximity: no filters (no service UUID or MACs) — can't watch")
            return false
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        val ok = runCatching { scanner.startScan(filters, settings, callback); true }
            .getOrElse { DebugLog.add("proximity: startScan threw — ${it.message}"); false }
        DebugLog.add("proximity: watch armed=$ok (${filters.size} filters, in-process)")
        return ok
    }

    /** Stop an in-process [scanForVehicle] watch. */
    fun stopScan(context: Context, callback: ScanCallback) {
        val scanner = scanner(context) ?: return
        runCatching { scanner.stopScan(callback) }
        DebugLog.add("proximity: watch stopped")
    }

    private fun scanner(context: Context): BluetoothLeScanner? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
}
