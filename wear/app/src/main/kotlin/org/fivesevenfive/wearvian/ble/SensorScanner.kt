package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.util.logw

/**
 * Discovers the vehicle's BLE sensors for drive localization. Per the decompiled app
 * (`l60/y0`), the sensors advertise the **vehicle's VAS id as their service UUID**, and
 * each scan result yields a sensor (address, interior/exterior location, RSSI). The car
 * triangulates the watch across these by RSSI to enable drive (inside) vs unlock (door).
 * See docs/passive-entry-protocol.md.
 */
@SuppressLint("MissingPermission")
class SensorScanner(context: Context) {

    data class Hit(val name: String?, val address: String, val rssi: Int)

    /** The system rejected the scan (onScanFailed) — NOT the same as "no sensors in range". */
    class ScanFailedException(val errorCode: Int) : Exception("sensor scan failed err=$errorCode")

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Scan for the vehicle's sensors — devices advertising [serviceUuid] (the vehicle's
     * VAS id). Returns the strongest RSSI per device over [durationMs].
     */
    suspend fun discover(serviceUuid: java.util.UUID, durationMs: Long = 6_000L): List<Hit> {
        val scanner = adapter.bluetoothLeScanner ?: run {
            logw("SensorScanner: Bluetooth off / no scanner")
            return emptyList()
        }
        val hits = LinkedHashMap<String, Hit>()
        val failed = CompletableDeferred<Int>()
        val cb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val prev = hits[result.device.address]
                if (prev == null || result.rssi > prev.rssi) {
                    hits[result.device.address] = Hit(result.device.name, result.device.address, result.rssi)
                }
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        val failCode = try {
            // startScan reports most failures asynchronously (rate limit, no filter slots).
            withTimeoutOrNull(durationMs) { failed.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        if (failCode != null) throw ScanFailedException(failCode)
        return hits.values.sortedByDescending { it.rssi }
    }
}
