package org.fivesevenfive.wearvian.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.util.logi

/**
 * Foreground service that keeps the watch present to the vehicle so its BLE
 * proximity sensors keep us authorised for passive unlock + drive.
 *
 * Strategy: hold an auto-reconnecting GATT connection to the bonded "Rivian
 * Phone Key" peripheral. The OS-managed bond (with its IRK) is what the vehicle
 * recognises; maintaining the connection keeps us continuously detectable.
 *
 * NOTE (empirical): exactly what the vehicle needs to localise us after bonding
 * — a live connection vs. periodic connectable advertising — must be confirmed
 * on-vehicle. If a held connection proves insufficient, add a
 * BluetoothLeAdvertiser here as well. Foreground-only operation is intentional.
 */
@SuppressLint("MissingPermission")
class PresenceService : Service() {

    private var gatt: BluetoothGatt? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logi("PresenceService: onStartCommand")
        startAsForeground()
        connectToBondedVehicle()
        return START_STICKY
    }

    private fun connectToBondedVehicle() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.bondedDevices.firstOrNull { it.name == RivianBle.DEVICE_NAME }
        if (device == null) {
            logi("PresenceService: no bonded '${RivianBle.DEVICE_NAME}' found; stopping")
            stopSelf()
            return
        }
        logi("PresenceService: connecting to bonded ${device.name} ${device.address} (autoConnect)")
        // autoConnect=true lets the stack transparently re-establish the link.
        gatt = device.connectGatt(this, true, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                logi("PresenceService: gatt state status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // With autoConnect the stack retries; nudge it just in case.
                    g.connect()
                }
            }
        })
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.presence_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.presence_notification_title))
            .setContentText(getString(R.string.presence_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    override fun onDestroy() {
        logi("PresenceService: onDestroy")
        gatt?.close()
        gatt = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wearvian_presence"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }
    }
}
