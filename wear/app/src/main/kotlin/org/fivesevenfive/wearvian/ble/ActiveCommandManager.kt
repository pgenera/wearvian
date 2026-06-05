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
            findChar(gatt, RivianBle.CHAR_VEHICLE_STATUS)?.let { enableNotify(gatt, it) }
            enableNotify(gatt, phoneIdChar)
            enableNotify(gatt, nonceChar)
            enableNotify(gatt, cmdChar)

            val shared = keyManager.sharedSecret(enrollment.vehiclePublicKey)

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

            // 3) encrypted command frame
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

    private suspend fun writeChar(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        charWritten = CompletableDeferred()
        gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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
    }
}
