package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.toHexString
import java.security.SecureRandom
import java.util.UUID

/**
 * One presence connection to a single vehicle BLE device — the phone-key PRIMARY
 * module OR one of the location sensors. Each holds its own GATT + state so several
 * can run concurrently; the car triangulates the watch across them by RSSI to enable
 * drive (inside) vs unlock (door). See docs/passive-entry-protocol.md.
 *
 * Flow per (re)connection: connect → discover → notify → phoneId echo → pNonce/vNonce
 * auth → stream the 37-byte heartbeat to 0x1b. [sharedSecret] is the per-vehicle ECDH
 * secret (computed once by the caller; identical for every device of this vehicle).
 */
@SuppressLint("MissingPermission")
class VehicleSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val enrollment: Enrollment,
    private val sharedSecret: ByteArray,
    private val label: String,
    /**
     * The PRIMARY phone-key module does the full auth handshake + 37-byte heartbeat.
     * The location sensors (decompile: `l60/i.I()` skips the heartbeat for non-PRIMARY)
     * just need a held connection so the car can measure RSSI for localization.
     */
    private val primary: Boolean,
) {
    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    private var charWritten = CompletableDeferred<Boolean>()
    private val notifications = HashMap<UUID, CompletableDeferred<ByteArray>>()
    @Volatile private var sessionAlive = false
    private var inbound = 0

    /** Run the connect→session loop until [scope] is cancelled, reconnecting with backoff. */
    suspend fun runForever(scope: CoroutineScope) {
        while (scope.isActive) {
            runCatching { runOnce(scope) }
                .onFailure { DebugLog.add("$label: session ended — ${it.message}") }
            if (scope.isActive) delay(RECONNECT_BACKOFF_MS)
        }
    }

    private suspend fun runOnce(scope: CoroutineScope) {
        reset()
        DebugLog.ble("·", label, "connecting ${device.address}")
        val g = device.connectGatt(context, false, gattCallback)
        try {
            withTimeout(CONNECT_MS) { connected.await() }
            DebugLog.ble("·", label, "connected")
            g.discoverServices()
            withTimeout(OP_MS) { servicesReady.await() }
            sessionAlive = true
            DebugLog.ble("·", label, "discovered (${g.services.size} svc)")
            findChar(g, RivianBle.CHAR_VEHICLE_STATUS)?.let { enableNotify(g, it) }

            // Sensors don't do the phone-key handshake/heartbeat (PRIMARY-only); they
            // just hold a connection so the car can RSSI-localize the watch.
            if (!primary) {
                DebugLog.ble("·", label, "holding (sensor presence for RSSI)")
                while (scope.isActive && sessionAlive) delay(HOLD_MS)
                return
            }

            val phoneIdChar = requireChar(g, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(g, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            val readChar = requireChar(g, RivianBle.CHAR_RIVIAN_READ)
            enableNotify(g, phoneIdChar)
            enableNotify(g, nonceChar)
            DebugLog.ble("·", label, "notify enabled; → phoneId")

            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(g, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            val vid = withTimeout(OP_MS) { notifications.getValue(phoneIdChar.uuid).await() }
            require(PairingFrames.vehicleIdMatches(vid, enrollment.vasVehicleId)) { "vehicle id mismatch" }

            val pNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            notifications[nonceChar.uuid] = CompletableDeferred()
            writeChar(g, nonceChar, ActiveCommandFrames.authNonce(sharedSecret, pNonce))
            val vResp = withTimeout(OP_MS) { notifications.getValue(nonceChar.uuid).await() }
            val vNonce = vResp.copyOf(16)
            DebugLog.add("$label: session up (vNonce ${vNonce.toHexString().take(8)}…)")

            var counter = 0
            while (scope.isActive && sessionAlive) {
                val hb = ActiveCommandFrames.heartbeatFrame(sharedSecret, pNonce, vNonce, counter, HEARTBEAT_FLAG)
                writeChar(g, readChar, hb)
                if (counter % HB_LOG_EVERY == 0) DebugLog.ble("→", "$label/0x1b", "hb ctr=$counter", hb.size)
                counter++
                delay(HEARTBEAT_PERIOD_MS)
            }
        } finally {
            sessionAlive = false
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private fun reset() {
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        descriptorWritten = CompletableDeferred()
        charWritten = CompletableDeferred()
        notifications.clear()
    }

    private fun requireChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic =
        findChar(g, uuid) ?: error("characteristic $uuid not found")

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private suspend fun enableNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(RivianBle.CCCD) ?: return
        descriptorWritten = CompletableDeferred()
        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        runCatching { withTimeout(OP_MS) { descriptorWritten.await() } }
    }

    private suspend fun writeChar(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        charWritten = CompletableDeferred()
        g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        withTimeout(OP_MS) { charWritten.await() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessionAlive = false
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("disconnected status=$status"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            charWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val waiter = notifications[characteristic.uuid]
            if (waiter != null && !waiter.isCompleted) {
                waiter.complete(value)
                return
            }
            if (characteristic.uuid == RivianBle.CHAR_VEHICLE_STATUS) {
                DebugLog.ble("←", "$label/0x1c", "status ${value.toHexString()}", value.size)
            } else if (inbound++ % INBOUND_LOG_EVERY == 0) {
                DebugLog.ble("←", "$label", "notify ${value.toHexString().take(12)}…", value.size)
            }
        }
    }

    companion object {
        private const val CONNECT_MS = 10_000L
        private const val OP_MS = 5_000L
        private const val RECONNECT_BACKOFF_MS = 1_500L
        private const val HOLD_MS = 2_000L
        private const val HEARTBEAT_PERIOD_MS = 75L
        private const val HB_LOG_EVERY = 13
        private const val INBOUND_LOG_EVERY = 30
        private val HEARTBEAT_FLAG = 0x80.toByte()
    }
}
