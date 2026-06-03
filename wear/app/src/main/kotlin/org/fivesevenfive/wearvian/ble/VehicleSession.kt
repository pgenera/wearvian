package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * can run concurrently; the car triangulates the watch across them by the RSSI each
 * link reports, to enable drive (inside cabin) vs unlock (door). See
 * docs/passive-entry-protocol.md.
 *
 * Per the decompiled app (`l60/i`, `l60/j0`, `l60/i0`), EVERY connection — PRIMARY
 * and sensors alike — runs the full phoneId+nonce auth handshake, then streams a
 * 37-byte heartbeat to 0x1b every ~300 ms. The heartbeat's 5th byte is the
 * phone-measured RSSI to that device (`readRemoteRssi`, default −128), which is the
 * actual localization signal the car ranges on. A connection that only bonds/holds
 * (no auth, no RSSI heartbeat) is dropped by the peripheral — which is why our old
 * "connect-and-hold" sensors churned.
 *
 * Flow per (re)connection: connect → discover → notify (0x12/0x15/0x1c/0x20) →
 * phoneId echo → pNonce/vNonce auth → write SensorInformation(0x01) to 0x20 →
 * stream RSSI heartbeats to 0x1b. [sharedSecret] is the per-vehicle ECDH secret
 * (computed once by the caller; identical for every device of this vehicle).
 */
@SuppressLint("MissingPermission")
class VehicleSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val enrollment: Enrollment,
    private val sharedSecret: ByteArray,
    private val label: String,
    /**
     * The PRIMARY phone-key module. Kept for logging/future use (the decompile runs
     * an extra PRIMARY-only coroutine `l60/i.I()`); the auth + RSSI-heartbeat path
     * below is identical for PRIMARY and sensors.
     */
    private val primary: Boolean,
) {
    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    private var charWritten = CompletableDeferred<Boolean>()
    private val notifications = HashMap<UUID, CompletableDeferred<ByteArray>>()
    @Volatile private var sessionAlive = false
    /** Live phone→device RSSI, fed into the heartbeat. −128 (=0x80) until the first read. */
    @Volatile private var latestRssi: Int = RSSI_DEFAULT
    private var inbound = 0
    private var cmdInbound = 0

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
        // Sensors are separate, unbonded BLE devices; their characteristics require
        // an encrypted link, so the first CCCD write to an unbonded sensor is dropped
        // with HCI 0x05 (auth failure). The official app bonds each device
        // (decompile l60/i.r() → createBond; b0.e gates a sensor's AUTHENTICATED state
        // on bondState==BONDED). Bond first, then connect with encryption available.
        if (!primary && device.bondState != BluetoothDevice.BOND_BONDED) {
            val ok = ensureBonded()
            DebugLog.ble("·", label, "bond after createBond: state=${bondName(device.bondState)} ok=$ok")
            if (!ok) error("bonding failed (state=${bondName(device.bondState)})")
        }
        DebugLog.ble("·", label, "connecting ${device.address}")
        val g = device.connectGatt(context, false, gattCallback)
        try {
            withTimeout(CONNECT_MS) { connected.await() }
            DebugLog.ble("·", label, "connected")
            g.discoverServices()
            withTimeout(OP_MS) { servicesReady.await() }
            sessionAlive = true
            // Request a fast connection interval — the default power-save interval
            // (~1 s) throttled our heartbeats to ~0.9 Hz; the car wants multi-Hz
            // presence for drive. HIGH ≈ 7.5–15 ms interval.
            val connPri = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            DebugLog.ble("·", label, "discovered (${g.services.size} svc) connPriReq=$connPri")

            val phoneIdChar = requireChar(g, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(g, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            val readChar = requireChar(g, RivianBle.CHAR_RIVIAN_READ)
            val n1c = findChar(g, RivianBle.CHAR_VEHICLE_STATUS)?.let { enableNotify(g, it) }
            val n20 = findChar(g, RivianBle.CHAR_ACTIVE_COMMAND)?.let { enableNotify(g, it) }
            val n12 = enableNotify(g, phoneIdChar)
            val n15 = enableNotify(g, nonceChar)
            DebugLog.ble("·", label, "notify 0x1c=$n1c 0x20=$n20 0x12=$n12 0x15=$n15; → phoneId")

            // phoneId write; the PRIMARY echoes the vehicle-id back. Sensors may not
            // echo — tolerate a missing echo, but reject a real mismatch.
            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(g, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            DebugLog.ble("→", "$label/0x12", "phoneId written; awaiting echo")
            val vid = runCatching { withTimeout(PHONEID_ECHO_MS) { notifications.getValue(phoneIdChar.uuid).await() } }
                .getOrNull()
            when {
                vid == null -> DebugLog.ble("·", label, "no phoneId echo (sensor); continuing")
                !PairingFrames.vehicleIdMatches(vid, enrollment.vasVehicleId) -> error("vehicle id mismatch")
                else -> DebugLog.ble("·", label, "phoneId echo ok ${vid.toHexString().take(12)}…")
            }

            // pNonce/vNonce auth — required: the heartbeat HMAC needs vNonce.
            val pNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            notifications[nonceChar.uuid] = CompletableDeferred()
            writeChar(g, nonceChar, ActiveCommandFrames.authNonce(sharedSecret, pNonce))
            DebugLog.ble("→", "$label/0x15", "pNonce written; awaiting vNonce")
            val vResp = runCatching { withTimeout(OP_MS) { notifications.getValue(nonceChar.uuid).await() } }
                .getOrElse { DebugLog.ble("·", label, "vNonce TIMEOUT — auth failed"); throw it }
            val vNonce = vResp.copyOf(16)
            DebugLog.add("$label: session up (vNonce ${vNonce.toHexString().take(8)}…)")

            // Kick off the vehicle message channel: write SensorInformation (0x01) to
            // 0x20 so the car starts ranging this link (decompile: l60/i.m()).
            findChar(g, RivianBle.CHAR_ACTIVE_COMMAND)?.let { msgChar ->
                runCatching { writeChar(g, msgChar, byteArrayOf(MSG_SENSOR_INFORMATION)) }
                    .onSuccess { DebugLog.ble("→", "$label/0x20", "SensorInformation(01)", 1) }
                    .onFailure { DebugLog.ble("·", label, "0x20 SensorInformation write failed: ${it.message}") }
            }
            g.readRemoteRssi()

            // Heartbeat write type, exactly as the decompile chooses it: the app sets
            // `qVar.o = (0x1b.properties & PROPERTY_WRITE) != 0` (l60/p) and writes with
            // `(qVar.o || preCcc) ? WRITE_TYPE_DEFAULT(2) : WRITE_TYPE_NO_RESPONSE(1)`
            // (l60/i0). So: with-response only if 0x1b advertises it, else no-response.
            // A per-beat with-response round-trip is also what throttled us to ~1 Hz and
            // starved the sensor handshakes, so a no-response char is the good case.
            val supportsWriteWithResponse =
                (readChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val hbWriteType =
                if (supportsWriteWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            DebugLog.ble("·", label, "0x1b props=0x%02x → %s".format(
                readChar.properties, if (supportsWriteWithResponse) "WITH_RESPONSE" else "NO_RESPONSE"))

            // Ranging heartbeat: every ~300 ms, report the live RSSI to this device.
            var counter = 0
            while (scope.isActive && sessionAlive) {
                val rssiByte = latestRssi.toByte()
                val hb = ActiveCommandFrames.heartbeatFrame(sharedSecret, pNonce, vNonce, counter, rssiByte)
                writeChar(g, readChar, hb, hbWriteType)
                if (counter % HB_LOG_EVERY == 0) {
                    DebugLog.ble("→", "$label/0x1b", "hb ctr=$counter rssi=$latestRssi", hb.size)
                }
                counter++
                // Refresh RSSI periodically (not every beat) so the extra GATT
                // round-trip doesn't throttle the heartbeat rate; it lands for a
                // later cycle. RSSI doesn't change fast enough to need every beat.
                if (counter % RSSI_EVERY == 0) g.readRemoteRssi()
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
        latestRssi = RSSI_DEFAULT
    }

    /**
     * Initiate + await link-layer bonding with this device (for unbonded sensors).
     * Serialized across all sessions via [bondGate]: firing createBond() on several
     * sensors at once while the PK link is active fails the pairing connection
     * (HCI_ERR_CONN_FAILED_ESTABLISHMENT) before SMP can run; one clean bond at a
     * time gives the controller a usable radio.
     */
    private suspend fun ensureBonded(): Boolean = bondGate.withLock {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return@withLock true
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> if (!bonded.isCompleted) bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> if (!bonded.isCompleted) bonded.complete(false)
                }
            }
        }
        // ACTION_BOND_STATE_CHANGED is a system broadcast, so the receiver must be
        // EXPORTED — NOT_EXPORTED silently drops it (we never saw the result).
        context.registerReceiver(
            receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED,
        )
        try {
            val started = device.createBond()
            DebugLog.ble("·", label, "createBond()=$started state=${bondName(device.bondState)}")
            if (!started && device.bondState != BluetoothDevice.BOND_BONDED) false
            else runCatching { withTimeout(BOND_MS) { bonded.await() } }.getOrDefault(false)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun bondName(s: Int): String = when (s) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_NONE -> "NONE"
        else -> "?$s"
    }

    private fun requireChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic =
        findChar(g, uuid) ?: error("characteristic $uuid not found")

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    /** Enable notifications; returns true iff the CCCD write was acked in time. */
    private suspend fun enableNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(RivianBle.CCCD) ?: run {
            DebugLog.ble("·", label, "no CCCD on ${tag(char.uuid)}")
            return false
        }
        descriptorWritten = CompletableDeferred()
        // The submit return code is the only in-process congestion signal Android
        // gives (e.g. WRITE_REQUEST_BUSY); a rejected submit never calls back, so
        // surface it instead of letting it decay into a silent await timeout.
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            DebugLog.ble("·", label, "notify CCCD ${tag(char.uuid)} submit rejected rc=${rcName(rc)}")
            return false
        }
        val ok = runCatching { withTimeout(OP_MS) { descriptorWritten.await() } }.getOrNull()
        if (ok != true) DebugLog.ble("·", label, "notify CCCD ${tag(char.uuid)} acked=false (timeout)")
        return ok == true
    }

    /** Short tag for a known characteristic UUID, for readable logs. */
    private fun tag(uuid: UUID): String = when (uuid) {
        RivianBle.CHAR_PHONE_ID_VEHICLE_ID -> "0x12"
        RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE -> "0x15"
        RivianBle.CHAR_RIVIAN_READ -> "0x1b"
        RivianBle.CHAR_VEHICLE_STATUS -> "0x1c"
        RivianBle.CHAR_ACTIVE_COMMAND -> "0x20"
        else -> uuid.toString().take(8)
    }

    private suspend fun writeChar(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ) {
        charWritten = CompletableDeferred()
        // Distinguish "the stack rejected the write" (in-process congestion, e.g.
        // WRITE_REQUEST_BUSY — a real signal) from "write accepted but no response"
        // (airtime starvation or remote silence). A rejected submit never calls back.
        val rc = g.writeCharacteristic(char, value, writeType)
        if (rc != BluetoothStatusCodes.SUCCESS) error("write ${tag(char.uuid)} rejected rc=${rcName(rc)}")
        withTimeout(OP_MS) { charWritten.await() }
    }

    /** Decode the common BluetoothStatusCodes returned by GATT submit calls. */
    private fun rcName(rc: Int): String = when (rc) {
        BluetoothStatusCodes.SUCCESS -> "SUCCESS"
        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> "WRITE_REQUEST_BUSY(201)"
        BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> "WRITE_NOT_ALLOWED(200)"
        BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> "SERVICE_NOT_BOUND"
        else -> "rc=$rc"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasAlive = sessionAlive
                sessionAlive = false
                // status 0x13=remote-terminated, 0x08=link timeout, 0x16=local close,
                // 0x3e=failed-to-establish, 0x85=gatt error.
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("disconnected status=$status"))
                } else if (wasAlive) {
                    DebugLog.ble("·", label, "disconnected status=0x%02x".format(status))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) latestRssi = rssi
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
            } else if (characteristic.uuid == RivianBle.CHAR_ACTIVE_COMMAND) {
                // 0x20 carries the vehicle's ranging/drive-state messages — the channel
                // we least understand. Log the first few in full, then throttle, full hex.
                if (cmdInbound < CMD_LOG_FIRST || cmdInbound % CMD_LOG_EVERY == 0) {
                    DebugLog.ble("←", "$label/0x20", "msg ${value.toHexString()}", value.size)
                }
                cmdInbound++
            } else if (inbound++ % INBOUND_LOG_EVERY == 0) {
                DebugLog.ble("←", "$label/${tag(characteristic.uuid)}", "notify ${value.toHexString().take(12)}…", value.size)
            }
        }
    }

    companion object {
        private const val CONNECT_MS = 10_000L
        private const val OP_MS = 5_000L
        private const val BOND_MS = 15_000L

        /** Serializes bonding across all sessions — concurrent createBond() storms fail. */
        private val bondGate = Mutex()
        private const val PHONEID_ECHO_MS = 3_000L
        private const val RECONNECT_BACKOFF_MS = 1_500L
        // Decompile (l60/j0.e): legacy ranging cadence is ~300 ms; l60/i.n() enforces a 300 ms floor.
        private const val HEARTBEAT_PERIOD_MS = 300L
        private const val HB_LOG_EVERY = 10
        /** Re-read RSSI every Nth heartbeat (~every 1.2 s at 300 ms cadence). */
        private const val RSSI_EVERY = 4
        private const val INBOUND_LOG_EVERY = 30
        /** 0x20 ranging-message logging: full hex for the first few, then every Nth. */
        private const val CMD_LOG_FIRST = 6
        private const val CMD_LOG_EVERY = 8
        /** Default RSSI before the first readRemoteRssi (l60/j0.h = −128 = 0x80). */
        private const val RSSI_DEFAULT = -128
        /** s60/i0.SensorInformation.getId() = 1 — first write to 0x20 to start ranging. */
        private const val MSG_SENSOR_INFORMATION = 0x01.toByte()
    }
}
