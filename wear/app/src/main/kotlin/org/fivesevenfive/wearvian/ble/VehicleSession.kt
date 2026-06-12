package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import org.fivesevenfive.wearvian.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.service.PresenceStatus
import org.fivesevenfive.wearvian.service.VehicleStatus
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
 * Confirmed against an official-app btsnoop of a fresh, never-paired device (2026-06-05):
 * EVERY device — PRIMARY and sensors alike — uses the **identical, in-the-clear** flow.
 * There is NO link-layer pairing/encryption and NO bonding (0 Encryption Change / LTK /
 * Pairing events in the capture); our old createBond attempts caused the HCI 0x05 failures.
 * The app subscribes ONLY 0x12 + 0x15 (handshake) and 0x20 (ranging) — it NEVER subscribes
 * 0x1c; we were subscribing 0x1c first, which broke the sensor sessions.
 *
 * Flow per (re)connection (exact order from the capture):
 *   connect → discover → notify 0x12,0x15 → write PhoneProfile(10 80)→0x20 →
 *   write phoneId→0x12 (car echoes 16-byte vehicle-id) → write pNonce→0x15
 *   (car returns 48-byte vNonce) → notify 0x20 → write SensorInformation(01)→0x20 →
 *   stream 37-byte RSSI heartbeats to 0x1b. The heartbeat's 5th byte is the
 *   phone-measured RSSI (`readRemoteRssi`, default −128) — the localization signal the
 *   car ranges on. [sharedSecret] is the per-vehicle ECDH secret (identical per device).
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
    /** Session nonces, kept so the GATT callback can decrypt inbound 0x20 status/ack frames. */
    @Volatile private var pNonce: ByteArray? = null
    @Volatile private var vNonce: ByteArray? = null
    /** Last decrypted STATUS plaintext — log only on change so closure transitions stand out. */
    private var lastStatusHex: String? = null
    /** Last raw 0x1c VEHICLE_STATUS frame — dedupe so closure transitions stand out. */
    private var lastStatus1cHex: String? = null
    /**
     * Active-command sequence number ("csn") — SEPARATE from the heartbeat counter, and the
     * value bound into each command's HMAC preimage. Confirmed in the decompile: the command
     * builder uses `tVar.r` (`em/f0.f`: `int i = tVar.r; tVar.r = i + 1`) while heartbeats use
     * a different field (`j0Var.k`). `csn` is init'd by connection type (`l60.x`): LEGACY → 0,
     * PRE_CCC/CCC → 1, reset on session clear. Our Gen-1 link is LEGACY (no CCC encryption), so
     * csn starts at 0, +1 per command, reset per (re)connect (each does a fresh nonce handshake).
     * The earlier build fed the heartbeat counter here (74, 1, 10…); the vehicle failed the csn
     * check and dropped the link (status 0x13) on every command.
     */
    private var commandCounter = 0
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** Run the connect→session loop until [scope] is cancelled, reconnecting with backoff. */
    suspend fun runForever(scope: CoroutineScope) {
        // PRIMARY can go dormant when the car's parked — a direct connect keeps missing its
        // rare advertisements and times out (status: "Timed out waiting for 10000 ms"). After a
        // connect failure, fall back to an autoConnect=true background reconnect for PK: the
        // controller completes it from the accept list whenever PK next advertises, with no
        // connect window and no direct-connect slot to contend. Reset on a successful connect so
        // an awake PK (and the sensors, which advertise actively) still get the fast direct path.
        var connectFailures = 0
        while (scope.isActive) {
            val established = runCatching { runOnce(scope, autoConnect = primary && connectFailures > 0) }
                .onFailure { DebugLog.add("$label: session ended — ${it.message}") }
                .getOrDefault(false)
            connectFailures = if (established) 0 else connectFailures + 1
            if (scope.isActive) delay(RECONNECT_BACKOFF_MS)
        }
    }

    /** @return true iff the GATT connection was established (auth/heartbeat may still fail after). */
    private suspend fun runOnce(scope: CoroutineScope, autoConnect: Boolean): Boolean {
        reset()
        var established = false
        PresenceStatus.set(label, PresenceStatus.Link.CONNECTING)
        DebugLog.ble("·", label, "connecting ${device.address}${if (autoConnect) " (autoConnect)" else ""}")
        val g = device.connectGatt(context, autoConnect, gattCallback)
        try {
            // autoConnect has no bounded connect window — it completes whenever the peer next
            // advertises, so await without a timeout (still cancelled if the scope tears down).
            if (autoConnect) connected.await() else withTimeout(CONNECT_MS) { connected.await() }
            established = true
            PresenceStatus.set(label, PresenceStatus.Link.CONNECTED)
            DebugLog.ble("·", label, "connected")
            g.discoverServices()
            withTimeout(OP_MS) { servicesReady.await() }
            sessionAlive = true
            // Request a fast connection interval — the default power-save interval
            // (~1 s) throttled our heartbeats to ~0.9 Hz. HIGH ≈ 7.5–15 ms interval.
            val connPri = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            DebugLog.ble("·", label, "discovered (${g.services.size} svc) connPriReq=$connPri")

            val phoneIdChar = requireChar(g, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(g, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            val readChar = requireChar(g, RivianBle.CHAR_RIVIAN_READ)
            val msgChar = findChar(g, RivianBle.CHAR_ACTIVE_COMMAND)
            // Subscribe ONLY the handshake chars now (0x12, 0x15) — exactly what the
            // official app does. NOT 0x1c (the app never subscribes it; doing so broke
            // our sensor sessions). 0x20 is subscribed after auth, below.
            val n12 = enableNotify(g, phoneIdChar)
            val n15 = enableNotify(g, nonceChar)
            DebugLog.ble("·", label, "notify 0x12=$n12 0x15=$n15; → PhoneProfile")

            // Write PhoneProfile (type 0x10 + capability 0x80) to 0x20 before phoneId,
            // matching the capture order.
            msgChar?.let {
                runCatching { writeChar(g, it, MSG_PHONE_PROFILE) }
                    .onSuccess { DebugLog.ble("→", "$label/0x20", "PhoneProfile(1080)", 2) }
                    .onFailure { e -> DebugLog.ble("·", label, "0x20 PhoneProfile write failed: ${e.message}") }
            }

            // phoneId write; the car echoes a 16-byte vehicle-id back on 0x12.
            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(g, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            DebugLog.ble("→", "$label/0x12", "phoneId written; awaiting echo")
            val vid = runCatching { withTimeout(PHONEID_ECHO_MS) { notifications.getValue(phoneIdChar.uuid).await() } }
                .getOrNull()
            when {
                vid == null -> DebugLog.ble("·", label, "no phoneId echo; continuing")
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
            // Publish the nonces so the GATT callback can decrypt inbound 0x20 status/ack frames.
            this.pNonce = pNonce
            this.vNonce = vNonce
            PresenceStatus.set(label, PresenceStatus.Link.UP)
            DebugLog.add("$label: session up (vNonce ${vNonce.toHexString().take(8)}…)")

            // Now subscribe 0x20 (the ranging channel) and kick it off with
            // SensorInformation(0x01) — order matches the capture (0x20 CCCD after auth).
            msgChar?.let {
                enableNotify(g, it)
                runCatching { writeChar(g, it, byteArrayOf(MSG_SENSOR_INFORMATION)) }
                    .onSuccess { DebugLog.ble("→", "$label/0x20", "SensorInformation(01)", 1) }
                    .onFailure { e -> DebugLog.ble("·", label, "0x20 SensorInformation write failed: ${e.message}") }
            }

            // EXPERIMENT (branch wearvian-vehicle-status-0x1c): subscribe CHAR_VEHICLE_STATUS
            // (0x1c) on the PRIMARY link, POST-auth. The decompile (em/f0.e, non-secured branch)
            // shows the official app subscribes this char for the full vehicle-status stream; we
            // dropped it long ago because subscribing it PRE-auth on the in-the-clear SENSORS
            // forced link encryption (HCI status=0x05) and broke them. PRIMARY-only + post-auth
            // is the safe variant. If rich status arrives here, the "missing subscription"
            // hypothesis is confirmed. Sensors still never subscribe it.
            if (primary) {
                findChar(g, RivianBle.CHAR_VEHICLE_STATUS)?.let {
                    val ok = enableNotify(g, it)
                    DebugLog.ble("·", label, "0x1c VEHICLE_STATUS subscribe=$ok [experiment]")
                } ?: DebugLog.ble("·", label, "0x1c char not found")
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
            var pausedForLock = false
            while (scope.isActive && sessionAlive) {
                // Layer 1 anti-theft: while the watch is locked (e.g. removed from the wrist
                // with a screen lock set), send NO authenticated heartbeats — the car loses
                // presence and won't passively unlock/drive for whoever holds the watch. The
                // GATT link stays up so presence resumes instantly on unlock. Protects keys
                // both old and new (the shared secret is cached in memory, so the keystore
                // key's unlocked-device requirement alone wouldn't stop an active session).
                if (keyguard?.isDeviceLocked == true) {
                    if (!pausedForLock) {
                        pausedForLock = true
                        DebugLog.add("$label: watch locked — pausing presence heartbeats")
                    }
                    delay(HEARTBEAT_PERIOD_MS)
                    continue
                }
                if (pausedForLock) {
                    pausedForLock = false
                    DebugLog.add("$label: watch unlocked — resuming heartbeats")
                }
                // PRIMARY drains queued active commands so they ride THIS live, awake
                // session with the running counter — the only way the vehicle accepts the
                // presence-gated closures (charge-port/windows/liftgate). A fresh one-shot's
                // counter=0 would look stale mid-session. See CommandBus / docs.
                if (primary && msgChar != null) {
                    while (true) {
                        val cmd = CommandBus.poll() ?: break
                        val frame = ActiveCommandFrames.activeCommandFrame(
                            sharedSecret, pNonce, vNonce, commandCounter, cmd.code,
                        )
                        runCatching { writeChar(g, msgChar, frame) }
                            .onSuccess {
                                DebugLog.ble("→", "$label/0x20",
                                    "CMD ${cmd.label} (0x%04x) cmdctr=$commandCounter".format(cmd.code), frame.size)
                            }
                            .onFailure { e -> DebugLog.add("$label: CMD ${cmd.label} write failed — ${e.message}") }
                        commandCounter++
                    }
                }
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
            PresenceStatus.set(label, PresenceStatus.Link.DOWN)
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        return established
    }

    private fun reset() {
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        descriptorWritten = CompletableDeferred()
        charWritten = CompletableDeferred()
        notifications.clear()
        latestRssi = RSSI_DEFAULT
        pNonce = null
        vNonce = null
        lastStatusHex = null
        commandCounter = 0
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

    /** Try the implicit-IV decrypt prober on an inbound frame; returns a log suffix or "". */
    private fun probe(frame: ByteArray): String {
        val pn = pNonce ?: return ""
        val vn = vNonce ?: return ""
        val hit = ActiveCommandFrames.probeInbound(sharedSecret, pn, vn, commandCounter, frame) ?: return ""
        return "  ‹DECRYPT $hit›"
    }

    /**
     * EXPERIMENT (branch wearvian-vehicle-status-0x1c): a 0x1c CHAR_VEHICLE_STATUS notification,
     * from the PRIMARY-only post-auth subscription we re-added. Log on change so closure
     * transitions stand out; old captures suggested 0x1c carries structured (likely plaintext)
     * status, but run the decrypt prober too in case it's encrypted.
     */
    private fun onVehicleStatus(value: ByteArray) {
        VehicleStatus.update(value) // publish parsed lock/closure state to the UI (in-memory)
        // The 0x1c frame is plaintext vehicle status (lock/closure/SoC/range) — sensitive usage
        // data. Don't write it to the debug log on production builds; keep it for dev diagnostics.
        if (BuildConfig.PRODUCTION) return
        val hex = value.toHexString()
        if (hex == lastStatus1cHex) return
        lastStatus1cHex = hex
        DebugLog.ble("←", "$label/0x1c", "VEHICLE_STATUS $hex${probe(value)}", value.size)
    }

    /**
     * Handle a 0x20 notification. The vehicle multiplexes three things on this channel
     * (docs/passive-entry-protocol.md): `18 01` STATUS reports (lock/closure/charge
     * state), `17 01` command acks (CommandReturnValue), and short ranging chatter. We
     * decrypt the encrypted frames with our own session key and log the PLAINTEXT, so the
     * watch's debug log alone reveals closure state — no phone snoop needed. STATUS is
     * deduped (logged only when the plaintext changes) so cycling a closure with another
     * key shows up as a single new line.
     */
    private fun onActiveChannelMessage(value: ByteArray) {
        val p = pNonce
        val v = vNonce
        when (value.firstOrNull()) {
            ActiveCommandFrames.TYPE_VEHICLE_STATUS -> {
                // Decrypted vehicle status (lock/closure/charge) — sensitive usage data, so don't
                // log it on production builds. (Closure state still reaches the UI via 0x1c.)
                if (BuildConfig.PRODUCTION) return
                val pt = if (p != null && v != null) ActiveCommandFrames.decryptInbound(sharedSecret, p, v, value) else null
                when {
                    pt == null -> {
                        if (value.toHexString() != lastStatusHex) {
                            lastStatusHex = value.toHexString()
                            DebugLog.ble("←", "$label/0x20", "STATUS raw ${value.toHexString()}${probe(value)}", value.size)
                        }
                    }
                    pt.toHexString() != lastStatusHex -> {
                        lastStatusHex = pt.toHexString()
                        DebugLog.ble("←", "$label/0x20", "STATUS pt=${pt.toHexString()}", pt.size)
                    }
                    // else: identical to the last status — suppress the repeat.
                }
            }
            ActiveCommandFrames.TYPE_ACTIVE_CMD_RESPONSE -> {
                val pt = if (p != null && v != null) ActiveCommandFrames.decryptInbound(sharedSecret, p, v, value) else null
                DebugLog.ble("←", "$label/0x20",
                    pt?.let { "CMD-ACK pt=${it.toHexString()}" } ?: "CMD-ACK raw ${value.toHexString()}${probe(value)}",
                    value.size)
            }
            else -> {
                // Ranging / control chatter: full hex for the first few, then throttle.
                if (cmdInbound < CMD_LOG_FIRST || cmdInbound % CMD_LOG_EVERY == 0) {
                    DebugLog.ble("←", "$label/0x20", "msg ${value.toHexString()}", value.size)
                }
                cmdInbound++
            }
        }
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
                onVehicleStatus(value)
            } else if (characteristic.uuid == RivianBle.CHAR_ACTIVE_COMMAND) {
                onActiveChannelMessage(value)
            } else if (inbound++ % INBOUND_LOG_EVERY == 0) {
                DebugLog.ble("←", "$label/${tag(characteristic.uuid)}", "notify ${value.toHexString().take(12)}…", value.size)
            }
        }
    }

    companion object {
        private const val CONNECT_MS = 10_000L
        private const val OP_MS = 5_000L
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
        /** s60/i0.SensorInformation.getId() = 1 — written to 0x20 to start ranging. */
        private const val MSG_SENSOR_INFORMATION = 0x01.toByte()
        /** PhoneProfile message: type 0x10 + capability byte 0x80 (from the capture). */
        private val MSG_PHONE_PROFILE = byteArrayOf(0x10, 0x80.toByte())
    }
}
