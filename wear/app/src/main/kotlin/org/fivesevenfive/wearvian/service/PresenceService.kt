package org.fivesevenfive.wearvian.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.toHexString
import java.security.SecureRandom
import java.util.UUID

/**
 * Foreground service that maintains the live presence session needed for passive
 * unlock + drive. Per docs/passive-entry-protocol.md it holds ONE stable connection,
 * does the phoneId/pNonce-vNonce handshake once, then streams the authenticated
 * PASSIVE_ENTRY heartbeat (37 B) to [RivianBle.CHAR_RIVIAN_READ] at ~12 Hz. Each step
 * is mirrored to [DebugLog] (swipe-left console). On disconnect it re-establishes with
 * a short backoff (no reconnect storm).
 */
@SuppressLint("MissingPermission")
class PresenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyManager = KeyManager()
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private var gatt: BluetoothGatt? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    private var charWritten = CompletableDeferred<Boolean>()
    private val notifications = HashMap<UUID, CompletableDeferred<ByteArray>>()
    @Volatile private var sessionAlive = false
    private var inbound = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logi("PresenceService: onStartCommand")
        startAsForeground()
        // Hold the CPU on so the ~12 Hz heartbeat loop and BLE writes keep running
        // when the watch screen sleeps (otherwise doze freezes the coroutine).
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wearvian:presence")
                .apply { acquire() }
            DebugLog.add("presence: wake lock acquired")
        }
        val enrollment = EnrollmentStore(this).load()
        if (enrollment == null) {
            DebugLog.add("presence: not enrolled; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // Guard against duplicate starts (e.g. a re-tap after the UI desynced) —
        // a second presence loop would fight the first and kill the connection.
        if (loopJob?.isActive == true) {
            DebugLog.add("presence: already running; ignoring duplicate start")
            return START_STICKY
        }
        isRunning = true
        loopJob = scope.launch { presenceLoop(enrollment) }
        return START_STICKY
    }

    private suspend fun presenceLoop(enrollment: Enrollment) {
        DebugLog.add("presence: loop start for ${enrollment.vin}")
        // Discover the vehicle's sensors IN PARALLEL (the sensors advertise the vehicle's
        // VAS id as their service UUID — see SensorScanner). Non-blocking so it can't delay
        // the working phone-key heartbeat; logs which sensors are reachable + RSSI.
        vehicleServiceUuid(enrollment.vasVehicleId)?.let { svc ->
            scope.launch {
                delay(2_000) // let the phone-key handshake settle first
                runCatching {
                    DebugLog.add("presence: scanning sensors (svc=${svc.toString().take(8)}…)")
                    val hits = SensorScanner(this@PresenceService).discover(svc)
                    DebugLog.add("presence: found ${hits.size} sensor(s)")
                    hits.forEach { DebugLog.ble("·", "sensor", "${it.name ?: "?"} ${it.address} rssi=${it.rssi}") }
                }
            }
        } ?: DebugLog.add("presence: bad vasVehicleId '${enrollment.vasVehicleId}', skipping sensor scan")
        while (scope.isActive) {
            runCatching { runSession(enrollment) }
                .onFailure { DebugLog.add("presence: session ended — ${it.message}") }
            if (scope.isActive) delay(RECONNECT_BACKOFF_MS)
        }
    }

    private suspend fun runSession(enrollment: Enrollment) {
        val device = adapter.bondedDevices.firstOrNull { it.name == RivianBle.DEVICE_NAME }
            ?: error("vehicle not bonded")
        resetSession()
        DebugLog.ble("·", "gatt", "connecting ${device.address}")
        val g = device.connectGatt(this, false, gattCallback)
        gatt = g
        try {
            withTimeout(CONNECT_MS) { connected.await() }
            g.discoverServices()
            withTimeout(OP_MS) { servicesReady.await() }
            sessionAlive = true
            DebugLog.ble("·", "gatt", "connected + discovered")

            val phoneIdChar = requireChar(g, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(g, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            val readChar = requireChar(g, RivianBle.CHAR_RIVIAN_READ)
            findChar(g, RivianBle.CHAR_ACTIVE_COMMAND)?.let { enableNotify(g, it) }
            findChar(g, RivianBle.CHAR_VEHICLE_STATUS)?.let { enableNotify(g, it) }
            enableNotify(g, phoneIdChar)
            enableNotify(g, nonceChar)

            val shared = keyManager.sharedSecret(enrollment.vehiclePublicKey)

            // handshake: phoneId echo
            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(g, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            DebugLog.ble("→", "0x12", "phoneId", 16)
            val vid = withTimeout(OP_MS) { notifications.getValue(phoneIdChar.uuid).await() }
            require(PairingFrames.vehicleIdMatches(vid, enrollment.vasVehicleId)) { "vehicle id mismatch" }
            DebugLog.ble("←", "0x12", "vehicleId ok", vid.size)

            // handshake: pNonce -> vNonce
            val pNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            notifications[nonceChar.uuid] = CompletableDeferred()
            writeChar(g, nonceChar, ActiveCommandFrames.authNonce(shared, pNonce))
            DebugLog.ble("→", "0x15", "authNonce pNonce+HMAC", 48)
            val vResp = withTimeout(OP_MS) { notifications.getValue(nonceChar.uuid).await() }
            val vNonce = vResp.copyOf(16)
            DebugLog.ble("←", "0x15", "vNonce", vResp.size)

            // stream the drive presence heartbeat
            DebugLog.add("presence: session up — streaming heartbeat to 0x1b")
            var counter = 0
            while (scope.isActive && sessionAlive) {
                val hb = ActiveCommandFrames.heartbeatFrame(shared, pNonce, vNonce, counter, HEARTBEAT_FLAG)
                writeChar(g, readChar, hb)
                if (counter % HB_LOG_EVERY == 0) DebugLog.ble("→", "0x1b", "heartbeat ctr=$counter", hb.size)
                counter++
                delay(HEARTBEAT_PERIOD_MS)
            }
        } finally {
            sessionAlive = false
            runCatching { g.disconnect() }
            runCatching { g.close() }
            gatt = null
        }
    }

    private fun resetSession() {
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
            DebugLog.ble("·", "gatt", "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessionAlive = false
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("disconnected"))
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
            // Vehicle status (0x1c) is the meaningful "did it take effect" signal — log it
            // fully with bytes. The high-rate ranging stream (0x20/0x1b) is rate-limited.
            if (characteristic.uuid == RivianBle.CHAR_VEHICLE_STATUS) {
                DebugLog.ble("←", "0x1c", "status ${value.toHexString()}", value.size)
            } else if (inbound++ % INBOUND_LOG_EVERY == 0) {
                DebugLog.ble("←", shortId(characteristic.uuid), "notify ${value.toHexString().take(16)}…", value.size)
            }
        }
    }

    /** Parse the vasVehicleId (dashed or 32-hex) into the service UUID the sensors advertise. */
    private fun vehicleServiceUuid(vasVehicleId: String): UUID? = runCatching {
        val s = vasVehicleId.trim()
        if (s.contains("-")) {
            UUID.fromString(s)
        } else {
            val h = s.lowercase().removePrefix("0x")
            require(h.length == 32) { "not 32 hex chars" }
            UUID.fromString("${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
        }
    }.getOrNull()

    private fun shortId(uuid: UUID): String = when (uuid) {
        RivianBle.CHAR_ACTIVE_COMMAND -> "0x20"
        RivianBle.CHAR_VEHICLE_STATUS -> "0x1c"
        RivianBle.CHAR_RIVIAN_READ -> "0x1b"
        else -> uuid.toString().take(8)
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.presence_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.presence_notification_title))
            .setContentText(getString(R.string.presence_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        logi("PresenceService: onDestroy")
        DebugLog.add("presence: stopping")
        isRunning = false
        sessionAlive = false
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wearvian_presence"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_MS = 10_000L
        private const val OP_MS = 5_000L
        private const val RECONNECT_BACKOFF_MS = 1_500L
        private const val HEARTBEAT_PERIOD_MS = 75L          // ~13 Hz
        private const val HB_LOG_EVERY = 13                  // log ~every 1 s
        private const val INBOUND_LOG_EVERY = 30
        /** Starting value for the 1-byte phone status/motion flag (TBD on-vehicle). */
        private val HEARTBEAT_FLAG = 0x80.toByte()

        /** True while the service is running — the UI reads this so the toggle stays
         *  in sync across screen/navigation changes (it's a foreground service that
         *  outlives any single composition). */
        @Volatile
        var isRunning = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }
    }
}
