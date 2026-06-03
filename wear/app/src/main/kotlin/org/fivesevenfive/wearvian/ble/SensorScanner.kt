package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.delay
import org.fivesevenfive.wearvian.util.logw

/**
 * Discovers the vehicle's BLE sensors for drive localization. They advertise the
 * `RIVSENSORSERVICE` service ([RivianBle.SERVICE_ACTIVE_ENTRY]) — the PRIMARY module
 * ("Rivian Phone Key") plus the interior/exterior location sensors ("Rivian Sensor N").
 * Drive (passive entry) needs the car to triangulate the watch across these by RSSI;
 * this is the discovery step toward connecting to them (see docs/passive-entry-protocol.md).
 */
@SuppressLint("MissingPermission")
class SensorScanner(context: Context) {

    data class Hit(val name: String?, val address: String, val rssi: Int)

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Scan for sensor-service devices for [durationMs]; returns the strongest RSSI per device. */
    suspend fun discover(durationMs: Long = 6_000L): List<Hit> {
        val scanner = adapter.bluetoothLeScanner ?: run {
            logw("SensorScanner: Bluetooth off / no scanner")
            return emptyList()
        }
        val hits = LinkedHashMap<String, Hit>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val prev = hits[result.device.address]
                if (prev == null || result.rssi > prev.rssi) {
                    hits[result.device.address] = Hit(result.device.name, result.device.address, result.rssi)
                }
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(RivianBle.SERVICE_ACTIVE_ENTRY))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        return hits.values.sortedByDescending { it.rssi }
    }
}
