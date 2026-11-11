package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import org.fivesevenfive.wearvian.store.VehicleAddressStore

/** Locates the enrolled vehicle's PRIMARY phone-key module. */
@SuppressLint("MissingPermission")
object PhoneKey {

    /**
     * The phone-key device: by the address stored at pairing (preferring its bonded entry), else —
     * only when no address is stored (paired before it was recorded) — the first bonded device
     * named [RivianBle.DEVICE_NAME]. Null while Bluetooth is off, or when not paired at all.
     */
    fun find(context: Context, adapter: BluetoothAdapter?): BluetoothDevice? {
        if (adapter == null || !adapter.isEnabled) return null
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val stored = storedAddress(context)
        if (stored != null) {
            bonded.firstOrNull { it.address.equals(stored, ignoreCase = true) }?.let { return it }
            // Bond removed in Settings: the Rivian session runs in the clear, so connect by address.
            return runCatching { adapter.getRemoteDevice(stored) }.getOrNull()
        }
        return bonded.firstOrNull { it.name == RivianBle.DEVICE_NAME }
    }

    /** The address recorded at pairing, if valid. */
    fun storedAddress(context: Context): String? =
        VehicleAddressStore(context).phoneKeyAddress?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
}
