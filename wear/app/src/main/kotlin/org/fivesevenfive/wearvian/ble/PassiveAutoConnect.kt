package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Low-power wake trigger for passive idle: a GATT `autoConnect(=true)` pending on the bonded PRIMARY
 * (PK) phone-key device. The Bluetooth controller completes it from the accept list whenever PK next
 * advertises — no wake lock, no CPU polling, no bounded connect window — then fires [onConnected].
 *
 * This is the reliable companion to the offloaded FIRST_MATCH scan ([ProximityWake]). That scan
 * detects *any* node advertising but then has to race a fresh connect; autoConnect *is* the connect,
 * so a returning PK reconnects directly with no detect-then-connect gap to miss. We use the bare
 * connection only as the trigger — on connect we hand off to the full presence session (which
 * reconnects with auth + heartbeats), so this callback does no service discovery of its own.
 */
@SuppressLint("MissingPermission")
class PassiveAutoConnect(private val onConnected: () -> Unit) {

    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                DebugLog.add("passive-autoconnect: PK reconnected → wake")
                onConnected()
            }
        }
    }

    /** Arm the autoConnect on the bonded PK. No-op returning true if already armed; false if no PK. */
    fun arm(context: Context): Boolean {
        if (gatt != null) return true
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val pk = adapter?.bondedDevices?.firstOrNull { it.name == RivianBle.DEVICE_NAME } ?: run {
            DebugLog.add("passive-autoconnect: no bonded ${RivianBle.DEVICE_NAME} — not armed")
            return false
        }
        gatt = pk.connectGatt(context, /* autoConnect = */ true, callback)
        DebugLog.add("passive-autoconnect: armed on PK")
        return gatt != null
    }

    /** Tear down the pending/live connection so the full session can take over (or on lock/stop). */
    fun disarm() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }
}
