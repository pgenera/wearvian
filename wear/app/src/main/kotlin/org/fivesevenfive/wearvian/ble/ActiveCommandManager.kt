package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.loge
import org.fivesevenfive.wearvian.util.toHexString
import java.security.SecureRandom
import java.util.UUID

/**
 * Sends an authenticated active-entry command to the bonded vehicle over BLE,
 * per the reverse-engineered protocol (docs/passive-entry-protocol.md):
 *   connect -> discover -> notify -> phoneId echo -> pNonce/vNonce auth ->
 *   write AES-128-GCM command frame to [RivianBle.CHAR_ACTIVE_COMMAND].
 * Every step is mirrored to [DebugLog] for the on-watch debug console.
 */
@SuppressLint("MissingPermission")
class ActiveCommandManager(
    private val context: Context,
    private val keyManager: KeyManager,
) {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    private var charWritten = CompletableDeferred<Boolean>()
    private val notifications = HashMap<UUID, CompletableDeferred<ByteArray>>()
    /** Live phone→vehicle RSSI fed into the wake heartbeats; −128 until the first read. */
    @Volatile private var latestRssi = -128

    suspend fun sendCommand(enrollment: Enrollment, commandCode: Int, label: String): Result<Unit> = runCatching {
        DebugLog.add("cmd $label (0x%04x) → start".format(commandCode))
        val device = adapter.bondedDevices.firstOrNull { it.name == RivianBle.DEVICE_NAME }
            ?: error("vehicle '${RivianBle.DEVICE_NAME}' not bonded")
        DebugLog.ble("·", "gatt", "connecting ${device.address}")
        val gatt = device.connectGatt(context, false, gattCallback)
        try {
            withTimeout(CONNECT_MS) { connected.await() }
            gatt.discoverServices()
            withTimeout(OP_MS) { servicesReady.await() }
            DebugLog.ble("·", "gatt", "connected + discovered")

            val phoneIdChar = requireChar(gatt, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(gatt, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            val cmdChar = requireChar(gatt, RivianBle.CHAR_ACTIVE_COMMAND)
            val readChar = requireChar(gatt, RivianBle.CHAR_RIVIAN_READ)
            findChar(gatt, RivianBle.CHAR_VEHICLE_STATUS)?.let { enableNotify(gatt, it) }
            enableNotify(gatt, phoneIdChar)
            enableNotify(gatt, nonceChar)
            enableNotify(gatt, cmdChar)

            val shared = keyManager.sharedSecret(enrollment.vehiclePublicKey)

            // 0) PhoneProfile opens the ranging session — exactly what the official app's
            // command session does (PhoneProfile 10 80 → 0x20 before phoneId).
            runCatching { writeChar(gatt, cmdChar, MSG_PHONE_PROFILE) }
            DebugLog.ble("→", "0x20", "PhoneProfile(1080)", 2)

            // 1) phone id -> vehicle echoes its id
            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(gatt, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            DebugLog.ble("→", "0x12", "phoneId", 16)
            val vid = withTimeout(OP_MS) { notifications.getValue(phoneIdChar.uuid).await() }
            require(PairingFrames.vehicleIdMatches(vid, enrollment.vasVehicleId)) { "vehicle id mismatch" }
            DebugLog.ble("←", "0x12", "vehicleId ok", vid.size)

            // 2) auth: send pNonce, receive vNonce
            val pNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            notifications[nonceChar.uuid] = CompletableDeferred()
            writeChar(gatt, nonceChar, ActiveCommandFrames.authNonce(shared, pNonce))
            DebugLog.ble("→", "0x15", "authNonce pNonce+HMAC", 48)
            val vResp = withTimeout(OP_MS) { notifications.getValue(nonceChar.uuid).await() }
            val vNonce = vResp.copyOf(16)
            DebugLog.ble("←", "0x15", "vNonce ${vNonce.toHexString().take(8)}…", vResp.size)

            // 3) WAKE the vehicle over BLE (no cloud). There is no BLE "wake" command —
            // WakeVehicle has an empty BLE byte[] (h60/r0) and is cloud-only in the app.
            // Instead, the app's command session wakes the vehicle the same way passive
            // entry does: kick off ranging (SensorInformation 0x01 → 0x20) then stream
            // authenticated heartbeats to 0x1b, and send the command *within* that live
            // session. A bare one-shot command can't wake a sleeping command processor.
            enableNotify(gatt, readChar)
            runCatching { writeChar(gatt, cmdChar, byteArrayOf(MSG_SENSOR_INFORMATION)) }
            DebugLog.ble("→", "0x20", "SensorInformation(01)", 1)
            gatt.readRemoteRssi()
            repeat(WAKE_BEATS) { i ->
                val hb = ActiveCommandFrames.heartbeatFrame(shared, pNonce, vNonce, i, latestRssi.toByte())
                runCatching { writeChar(gatt, readChar, hb, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
                delay(WAKE_BEAT_MS)
            }
            DebugLog.add("cmd $label: BLE wake session sent ($WAKE_BEATS beats) → command")

            // 4) encrypted command frame, within the now-awake session
            val frame = ActiveCommandFrames.activeCommandFrame(shared, pNonce, vNonce, counter = 0, commandCode = commandCode)
            notifications[cmdChar.uuid] = CompletableDeferred()
            writeChar(gatt, cmdChar, frame)
            DebugLog.ble("→", "0x20", "CMD $label (enc)", frame.size)
            val resp = runCatching { withTimeout(OP_MS) { notifications.getValue(cmdChar.uuid).await() } }.getOrNull()
            DebugLog.add(resp?.let { "← 0x20 resp ${it.toHexString().take(12)}… ${it.size}B" } ?: "cmd $label written (no resp)")
            DebugLog.add("cmd $label ✓ done")
        } finally {
            gatt.disconnect()
            gatt.close()
        }
    }.onFailure {
        DebugLog.add("cmd $label ✗ ${it.message}")
        loge("sendCommand failed", it)
    }

    private fun requireChar(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic =
        findChar(gatt, uuid) ?: error("characteristic $uuid not found")

    private fun findChar(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in gatt.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private suspend fun enableNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(RivianBle.CCCD) ?: return
        descriptorWritten = CompletableDeferred()
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        runCatching { withTimeout(OP_MS) { descriptorWritten.await() } }
    }

    private suspend fun writeChar(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ) {
        charWritten = CompletableDeferred()
        gatt.writeCharacteristic(char, value, writeType)
        withTimeout(OP_MS) { charWritten.await() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) connected.complete(true)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED && !connected.isCompleted)
                connected.completeExceptionally(IllegalStateException("disconnected"))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) latestRssi = rssi
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            charWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications[characteristic.uuid]?.complete(value)
        }
    }

    private companion object {
        const val CONNECT_MS = 10_000L
        const val OP_MS = 5_000L
        /** Wake-burst: heartbeats streamed to 0x1b before the command, to wake a sleeping vehicle. */
        const val WAKE_BEATS = 24
        const val WAKE_BEAT_MS = 110L
        /** PhoneProfile message: type 0x10 + capability byte 0x80 (opens the ranging session). */
        val MSG_PHONE_PROFILE = byteArrayOf(0x10, 0x80.toByte())
        /** SensorInformation id = 1 — written to 0x20 to start ranging. */
        const val MSG_SENSOR_INFORMATION = 0x01.toByte()
    }
}
