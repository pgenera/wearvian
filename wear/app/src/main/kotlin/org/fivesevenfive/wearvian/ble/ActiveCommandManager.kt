package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.crypto.SharedSecretCache
import org.fivesevenfive.wearvian.crypto.WatchLockedException
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.loge
import org.fivesevenfive.wearvian.util.toHexString
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends an authenticated active-entry command to the bonded vehicle over BLE,
 * per the reverse-engineered protocol (docs/passive-entry-protocol.md):
 *   connect -> discover -> notify -> phoneId echo -> pNonce/vNonce auth ->
 *   write AES-128-GCM command frame to [RivianBle.CHAR_ACTIVE_COMMAND].
 * Every step is mirrored to [DebugLog] for the on-watch debug console.
 *
 * One-shots are serialized process-wide (and the PRIMARY [VehicleSession] waits for them via
 * [awaitOneShotIdle]) so two GATT clients never run the nonce handshake on PK at once. A failed
 * attempt is retried once unless the failure is one a retry can't fix (see [CommandFailure]).
 *
 * Latency (cold tap measured at ~5.9 s in June logs): the ECDH comes from [SharedSecretCache] and
 * overlaps the connect instead of following it, the tile can [prewarm] the link before the tap, and
 * only the characteristics the one-shot reads are subscribed.
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
    // Written by the command coroutine, read/completed by the GATT callback thread → concurrent.
    private val notifications = ConcurrentHashMap<UUID, CompletableDeferred<ByteArray>>()

    /** Set once this client's link drops after connecting (a pre-opened link is then useless). */
    @Volatile private var linkLost = false

    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /**
     * Connect, authenticate and send [commandCode] once (retrying once on a retryable failure).
     * Fully time-bounded; a failure's [CommandException.failure] says why (other exceptions mean
     * [CommandFailure.SEND_FAILED]). Uses the tile's pre-opened link ([prewarm]) when one is up.
     */
    suspend fun sendCommand(enrollment: Enrollment, commandCode: Int, label: String): Result<Unit> {
        // Registered before queueing on the lock: a pre-warm this tap is waiting on must finish
        // (and hand over its link) even if the tile leaves view meanwhile.
        prewarmGate.commandWaiting()
        try {
            return oneShotLock.withLock {
                val warmLink = takeWarmLocked()
                var result = if (warmLink != null) warmLink.manager.attempt(enrollment, commandCode, label, warmLink.gatt)
                else attempt(enrollment, commandCode, label, null)
                val first = result.exceptionOrNull()
                if (first != null && failureOf(first).retryable && currentCoroutineContext().isActive) {
                    DebugLog.add("cmd $label retrying once")
                    // A pre-opened link that turned out dead isn't a reason to back off: connect now.
                    if (warmLink == null) delay(RETRY_DELAY_MS)
                    result = attempt(enrollment, commandCode, label, null)
                }
                result
            }
        } finally {
            prewarmGate.commandDone()
        }
    }

    /**
     * One try. [preopened] is a link [openLink] already connected and subscribed (tile pre-warm);
     * otherwise this connects itself. Either way the link is closed when the try ends.
     */
    private suspend fun attempt(
        enrollment: Enrollment, commandCode: Int, label: String, preopened: BluetoothGatt?,
    ): Result<Unit> = runCatching {
        DebugLog.add("cmd $label (0x%04x) → start".format(commandCode))
        // Layer 1 anti-theft: don't send commands while the watch is locked (off wrist).
        if (keyguard?.isDeviceLocked == true) {
            SharedSecretCache.clear() // a fresh locked reading: drop the secret too
            preopened?.let { closeLink(it) }
            throw CommandException(CommandFailure.WATCH_LOCKED, "watch locked — command blocked")
        }
        // The StrongBox ECDH (0.5–2 s) used to run only after the connect; start it now (or find it
        // cached) so it overlaps the connect instead of adding to it.
        SharedSecretCache.prefetch(keyManager, enrollment.vehiclePublicKey)
        val gatt = preopened?.also {
            DebugLog.ble("·", "gatt", "using pre-opened link")
            // A held link idles at the default interval (see prewarm); speed it up now it's in use.
            it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } ?: openLink(CONNECT_MS, highPriority = true, requireSubscriptions = false)
        try {
            authenticateAndSend(gatt, enrollment, commandCode, label)
        } finally {
            closeLink(gatt)
        }
    }.onFailure {
        DebugLog.add("cmd $label ✗ ${it.message}")
        loge("sendCommand failed", it)
    }

    /**
     * Connect, discover and subscribe the handshake + command characteristics; on failure the
     * client is closed and the error rethrown. Everything before the authentication, so the tile can
     * do it ahead of a tap. [highPriority] asks for the fastest interval (a tap's burst of round
     * trips; a held pre-warm link stays at the default so an unused one costs less).
     * [requireSubscriptions] fails the link if a CCCD write isn't acked — for a pre-warm, so a
     * half-subscribed link isn't kept for a tap to time out on. A tap keeps the old tolerant
     * behaviour.
     */
    private suspend fun openLink(connectMs: Long, highPriority: Boolean, requireSubscriptions: Boolean): BluetoothGatt {
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        descriptorWritten = CompletableDeferred()
        charWritten = CompletableDeferred()
        notifications.clear()
        linkLost = false
        val device = PhoneKey.find(context, adapter)
            ?: throw CommandException(CommandFailure.NOT_BONDED, "phone key not paired (or Bluetooth off)")
        DebugLog.ble("·", "gatt", "connecting ${device.address}")
        val gatt = device.connectGatt(context, false, gattCallback)
        try {
            withTimeoutOrNull(connectMs) { connected.await() }
                ?: throw CommandException(CommandFailure.NOT_REACHABLE, "connect timed out")
            // A one-shot is a short burst of sequential round trips: run it at the fastest interval.
            // (The car may still impose its own; the live session keeps BALANCED, see VehicleSession.)
            val pri = if (highPriority) gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) else null
            if (!gatt.discoverServices()) error("discoverServices rejected")
            if (!withTimeout(OP_MS) { servicesReady.await() }) error("service discovery failed")
            DebugLog.ble("·", "gatt", "connected + discovered connPriReq=$pri")

            // Only what the one-shot reads: 0x12 (vehicle-id echo), 0x15 (vNonce), 0x20 (command
            // response). Not 0x1c vehicle status — nothing here reads it, and it was a serial
            // CCCD round trip on every tap.
            val subscribed = enableNotify(gatt, requireChar(gatt, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)) and
                enableNotify(gatt, requireChar(gatt, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)) and
                enableNotify(gatt, requireChar(gatt, RivianBle.CHAR_ACTIVE_COMMAND))
            if (requireSubscriptions && !subscribed) error("notification subscribe failed")
            return gatt
        } catch (t: Throwable) {
            closeLink(gatt)
            throw t
        }
    }

    private suspend fun authenticateAndSend(gatt: BluetoothGatt, enrollment: Enrollment, commandCode: Int, label: String) {
        // Started before the connect (or cached), so normally ready by now. Awaited before the first
        // handshake write so the car sees the same write timing as before.
        val shared = withTimeout(ECDH_MS) { SharedSecretCache.get(keyManager, enrollment.vehiclePublicKey) }
        try {
            authenticateAndSendWith(gatt, enrollment, shared, commandCode, label)
        } finally {
            shared.fill(0) // our copy; the cache keeps its own
        }
    }

    private suspend fun authenticateAndSendWith(
        gatt: BluetoothGatt, enrollment: Enrollment, shared: ByteArray, commandCode: Int, label: String,
    ) {
        val phoneIdChar = requireChar(gatt, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
        val nonceChar = requireChar(gatt, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
        val cmdChar = requireChar(gatt, RivianBle.CHAR_ACTIVE_COMMAND)

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
    }

    private fun closeLink(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun requireChar(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic =
        findChar(gatt, uuid) ?: error("characteristic $uuid not found")

    private fun findChar(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in gatt.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private suspend fun enableNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(RivianBle.CCCD) ?: return false
        descriptorWritten = CompletableDeferred()
        val rc = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            DebugLog.ble("·", "gatt", "notify CCCD ${char.uuid} submit rejected rc=$rc")
            return false
        }
        val ok = runCatching { withTimeout(OP_MS) { descriptorWritten.await() } }.getOrNull()
        if (ok != true) DebugLog.ble("·", "gatt", "notify CCCD ${char.uuid} acked=false")
        return ok == true
    }

    private suspend fun writeChar(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        charWritten = CompletableDeferred()
        val rc = gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc != BluetoothStatusCodes.SUCCESS) error("write ${char.uuid} rejected rc=$rc")
        if (!withTimeout(OP_MS) { charWritten.await() }) error("write ${char.uuid} failed")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) connected.complete(true)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("disconnected"))
                else {
                    // Dropped after connecting (e.g. a pre-opened link the car let go): fail what's
                    // pending now instead of letting each wait run out its timeout.
                    linkLost = true
                    val gone = IllegalStateException("link lost")
                    servicesReady.completeExceptionally(gone)
                    descriptorWritten.completeExceptionally(gone)
                    charWritten.completeExceptionally(gone)
                    notifications.values.forEach { it.completeExceptionally(gone) }
                }
            }
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

    companion object {
        /** Matches [VehicleSession]'s direct connect: a dormant module can take well over 10 s. */
        private const val CONNECT_MS = 35_000L
        private const val OP_MS = 5_000L
        private const val RETRY_DELAY_MS = 500L
        /** Upper bound on waiting for the StrongBox ECDH (normally 0.5–2 s, or already cached). */
        private const val ECDH_MS = 10_000L

        /**
         * Pre-warm: how long a link opened when the tile comes into view is held for a tap. Long
         * enough to read the tile and tap; short enough that glancing at it costs little.
         */
        private const val PREWARM_HOLD_MS = 15_000L
        /** Pre-warm connect window. Shorter than a tap's: a car not reachable now is probably out of
         *  range, and a tap made meanwhile waits for this before connecting itself. */
        private const val PREWARM_CONNECT_MS = 10_000L

        /** Held for the whole of each one-shot (both attempts) and of each pre-warm connect. */
        private val oneShotLock = Mutex()

        /** Tile visibility + waiting taps, updated synchronously from the tile callbacks. */
        private val prewarmGate = PrewarmGate()

        private class WarmLink(
            val manager: ActiveCommandManager,
            val gatt: BluetoothGatt,
            val visit: Int,
            var heldSinceMs: Long,
        ) {
            var expiry: Job? = null
        }

        /** The pre-opened link waiting for a tap; guarded by [oneShotLock]. */
        private var warm: WarmLink? = null
        private val jobLock = Any()
        private var prewarmJob: Job? = null // guarded by jobLock
        private var prewarmJobVisit = 0     // guarded by jobLock
        private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun now() = SystemClock.elapsedRealtime()

        /** Tile came into view. Call synchronously from the callback; pass the result to [prewarm]. */
        fun tileEntered(): Int = prewarmGate.entered()

        /**
         * Tile left view. Synchronously makes the visit's pre-warm unwanted and cancels it (unless a
         * tap is waiting on it), then drops an unused held link.
         */
        fun tileLeft() {
            if (prewarmGate.left()) synchronized(jobLock) { prewarmJob?.cancel() }
            warmScope.launch {
                oneShotLock.withLock {
                    val link = warm ?: return@withLock
                    if (!prewarmGate.wanted(link.visit, PresenceService.isRunning)) closeWarmLocked("tile left")
                }
            }
        }

        /**
         * Tile [visit] is in view with the key off: start the ECDH and open (connect, discover,
         * subscribe) a link to PK now, so a tap within [PREWARM_HOLD_MS] only has to authenticate and
         * write the command. Whether it's still wanted (tile still in view, key still off) is
         * re-checked under the lock before connecting and before the link is kept.
         */
        fun prewarm(context: Context, enrollment: Enrollment, keyManager: KeyManager, visit: Int) {
            SharedSecretCache.prefetch(keyManager, enrollment.vehiclePublicKey)
            val app = context.applicationContext
            synchronized(jobLock) {
                if (prewarmJob?.isActive == true && prewarmJobVisit == visit) return
                prewarmJob?.cancel() // an earlier visit's
                prewarmJobVisit = visit
                prewarmJob = warmScope.launch { runPrewarm(app, keyManager, visit) }
            }
        }

        private suspend fun runPrewarm(app: Context, keyManager: KeyManager, visit: Int) = oneShotLock.withLock {
            if (!prewarmGate.wanted(visit, PresenceService.isRunning)) return@withLock
            val current = warm
            if (current != null) {
                if (!current.manager.linkLost && PrewarmGate.fresh(current.heldSinceMs, now(), PREWARM_HOLD_MS)) {
                    current.heldSinceMs = now() // still up: hold it for another window
                    armExpiryLocked(current)
                    return@withLock
                }
                closeWarmLocked("stale")
            }
            DebugLog.add("cmd: pre-warming link for tile")
            val manager = ActiveCommandManager(app, keyManager)
            val gatt = try {
                manager.openLink(PREWARM_CONNECT_MS, highPriority = false, requireSubscriptions = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.add("cmd: pre-warm failed — ${e.message}")
                return@withLock
            }
            if (!prewarmGate.wanted(visit, PresenceService.isRunning)) {
                manager.closeLink(gatt)
                DebugLog.add("cmd: pre-warmed link not needed any more — closed")
                return@withLock
            }
            warm = WarmLink(manager, gatt, visit, now()).also { armExpiryLocked(it) }
            DebugLog.add("cmd: link pre-warmed")
        }

        private fun armExpiryLocked(link: WarmLink) {
            link.expiry?.cancel()
            // Best effort only: delay() stalls while the CPU sleeps, so takeWarmLocked also checks
            // the hold window by the monotonic clock before using a link.
            link.expiry = warmScope.launch {
                delay(PREWARM_HOLD_MS)
                oneShotLock.withLock { if (warm === link) closeWarmLocked("unused") }
            }
        }

        /** Hand the pre-opened link to a command (null if none, dropped, or past its window). Lock held. */
        private fun takeWarmLocked(): WarmLink? {
            val link = warm ?: return null
            warm = null
            link.expiry?.cancel()
            val why = when {
                link.manager.linkLost -> "had dropped"
                !PrewarmGate.fresh(link.heldSinceMs, now(), PREWARM_HOLD_MS) -> "was past its window"
                else -> return link
            }
            link.manager.closeLink(link.gatt)
            DebugLog.add("cmd: pre-warmed link $why — connecting fresh")
            return null
        }

        private fun closeWarmLocked(why: String) {
            val link = warm ?: return
            warm = null
            link.expiry?.cancel()
            link.manager.closeLink(link.gatt)
            DebugLog.add("cmd: pre-warmed link closed ($why)")
        }

        /**
         * Suspend until no one-shot command holds a GATT client on PK. A pre-warm in progress or an
         * unused pre-opened link is dropped: the presence service is taking over the link. Called
         * before the PRIMARY session connects and before passive arms its PK autoConnect.
         */
        suspend fun awaitOneShotIdle() {
            synchronized(jobLock) { prewarmJob?.cancel() }
            if (oneShotLock.isLocked) DebugLog.add("cmd: waiting for one-shot to finish")
            oneShotLock.withLock { closeWarmLocked("presence service starting") }
        }

        /** Classify a one-shot failure for retry policy and user feedback. */
        fun failureOf(t: Throwable): CommandFailure = when (t) {
            is CommandException -> t.failure
            is WatchLockedException -> CommandFailure.WATCH_LOCKED // the secret cache saw the lock
            else -> CommandFailure.SEND_FAILED
        }
    }
}

/** Why a command did not reach the vehicle — drives retry policy and the short failure text. */
enum class CommandFailure(val userText: String, val retryable: Boolean) {
    WATCH_LOCKED("watch locked", false),
    NOT_BONDED("vehicle not paired", false),
    /** One-shot connect timed out (the full window already elapsed — retrying would double it). */
    NOT_REACHABLE("vehicle not reachable", false),
    /** Live-session path: no session picked the command up in time, or it was dropped on teardown. */
    NOT_CONNECTED("vehicle not connected", false),
    SEND_FAILED("couldn't send", true),
}

class CommandException(val failure: CommandFailure, message: String) : Exception(message)
