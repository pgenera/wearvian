package org.fivesevenfive.wearvian.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.ProximityWake
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.ble.VehicleSession
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.store.VehicleAddressStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi
import java.util.UUID

/**
 * Foreground service that maintains the drive-presence sessions. It runs one
 * [VehicleSession] to the bonded phone-key PRIMARY module AND one to each location
 * sensor it discovers (sensors advertise the vehicle's VAS id as their service UUID),
 * concurrently. Every session authenticates and streams an RSSI-carrying heartbeat,
 * so the car can triangulate the watch across them (inside → drive, door → unlock).
 * Holds a wake lock so the heartbeats keep streaming with the screen off. All steps
 * mirror to [DebugLog] → logcat. See docs/passive-entry-protocol.md.
 */
@SuppressLint("MissingPermission")
class PresenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyManager = KeyManager()
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null   // owns the BLE work (sessions + scan); torn down when locked
    private var notifJob: Job? = null
    private var idleJob: Job? = null
    private var lockJob: Job? = null
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** Set when we stop *on purpose* to idle passively — keeps the proximity wake armed. */
    @Volatile private var goingPassive = false

    /** True while the watch is locked (off wrist): all BLE is torn down (anti-theft). */
    @Volatile private var locked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logi("PresenceService: onStartCommand")
        // We're running now (possibly woken by proximity) — clear any armed offloaded scan.
        goingPassive = false
        ProximityWake.disarm(this)
        startAsForeground()
        val enrollment = EnrollmentStore(this).load()
        if (enrollment == null) {
            DebugLog.add("presence: not enrolled; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // lockJob is the "service is already running" marker (loopJob can be inactive while locked).
        if (lockJob?.isActive == true) {
            DebugLog.add("presence: already running; ignoring duplicate start")
            return START_STICKY
        }
        isRunning = true
        // Anti-theft: don't bring BLE up while the watch is locked (off wrist); monitorLock
        // restores it on unlock.
        locked = keyguard?.isDeviceLocked == true
        if (locked) {
            updateNotification(LOCKED_TEXT)
        } else {
            acquireWakeLock()
            startBle()
        }
        // Mirror the aggregate connection state into the ongoing notification, like the
        // official app's "vehicle connected / disconnected" persistent notification — but
        // while locked, hold the "locked" text instead of link state.
        if (notifJob?.isActive != true) {
            notifJob = scope.launch { PresenceStatus.summary.collect { if (!locked) updateNotification(it) } }
        }
        // Drop to passive (offloaded proximity wake) after a stretch with no link up.
        if (idleJob?.isActive != true) idleJob = scope.launch { monitorIdle() }
        // Tear down / restore BLE as the watch locks / unlocks.
        if (lockJob?.isActive != true) lockJob = scope.launch { monitorLock() }
        return START_STICKY
    }

    /** Bring up the BLE work (sessions + sensor scan) as a single cancellable job. */
    private fun startBle() {
        if (loopJob?.isActive == true) return
        val enrollment = EnrollmentStore(this).load() ?: return
        loopJob = scope.launch { presenceLoop(enrollment) }
    }

    /** Tear down all BLE — cancels the loop and, via structured concurrency, every session. */
    private suspend fun stopBle() {
        loopJob?.cancelAndJoin()
        loopJob = null
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wearvian:presence")
                .apply { acquire() }
            DebugLog.add("presence: wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /**
     * Anti-theft: while the watch is locked (removed from the wrist with a screen lock),
     * tear down ALL BLE — no connections, no chatter, no wake lock — keeping the foreground
     * service alive so we can rebuild instantly on unlock without an FGS-restart (which the
     * Android-12 background limit can block). Poll-based: there's no reliable "device
     * locked" broadcast.
     */
    private suspend fun monitorLock() {
        while (true) {
            val nowLocked = keyguard?.isDeviceLocked == true
            if (nowLocked != locked) {
                locked = nowLocked
                if (nowLocked) {
                    DebugLog.add("presence: watch locked — tearing down BLE (anti-theft)")
                    stopBle()
                    releaseWakeLock()
                    updateNotification(LOCKED_TEXT)
                } else {
                    DebugLog.add("presence: watch unlocked — restoring presence")
                    acquireWakeLock()
                    startBle()
                    updateNotification(PresenceStatus.summary.value)
                }
            }
            delay(LOCK_POLL_MS)
        }
    }

    /**
     * After [IDLE_TIMEOUT_MS] with no link UP (car out of range / asleep), arm the
     * hardware-offloaded proximity wake and stop the foreground service so we idle at
     * near-zero battery. [VehicleProximityReceiver] brings us back on approach.
     * [kotlinx.coroutines.flow.collectLatest] cancels the countdown the moment a link
     * comes up.
     */
    private suspend fun monitorIdle() {
        while (true) {
            // Wait until no link is UP. Reading the flow (not a single collectLatest pass)
            // also recovers from a stale `connected==true` carried over a process restart.
            PresenceStatus.connected.first { !it }
            // Disconnected — go passive only if we stay disconnected for the whole window;
            // a reconnect within it cancels the attempt.
            val reconnected = withTimeoutOrNull(IDLE_TIMEOUT_MS) { PresenceStatus.connected.first { it } }
            // If we stayed idle and successfully went passive, this scope ends. If goPassive
            // couldn't arm (BT off) or power-save is off, loop and retry rather than spinning
            // foreground forever with no further attempt.
            if (reconnected == null && goPassive()) return
        }
    }

    /** Drop to passive (arm the proximity wake + stop). @return true iff we went passive. */
    private fun goPassive(): Boolean {
        if (goingPassive) return true
        // While locked, BLE is already torn down by monitorLock — don't arm a proximity
        // wake; just keep the (cheap) foreground service alive until the watch is unlocked.
        if (locked) return false
        if (!SettingsStore(this).proximityWakeEnabled) {
            DebugLog.add("presence: idle, but auto power-save is off — staying foreground")
            return false
        }
        val enrollment = EnrollmentStore(this).load() ?: run { stopSelf(); return true }
        val svc = vehicleServiceUuid(enrollment.vasVehicleId)
        val macs = VehicleAddressStore(this).load()
        val armed = ProximityWake.arm(this, svc, macs)
        DebugLog.add("presence: idle ${IDLE_TIMEOUT_MS / 60_000}m → passive; wake armed=$armed (svc=${svc != null} macs=${macs.size})")
        if (!armed) {
            // Couldn't arm the wake — don't go dark; the loop retries later.
            DebugLog.add("presence: wake NOT armed — will retry")
            return false
        }
        goingPassive = true
        stopSelf()
        return true
    }

    // Runs as [loopJob]. Sessions are launched as CHILDREN (structured concurrency) so
    // cancelling loopJob (e.g. on lock) tears down every session too.
    private suspend fun presenceLoop(enrollment: Enrollment) = coroutineScope {
        val bleScope = this
        DebugLog.add("presence: loop start for ${enrollment.vin}")
        val sharedSecret = runCatching { keyManager.sharedSecret(enrollment.vehiclePublicKey) }
            .getOrElse { DebugLog.add("presence: ECDH failed — ${it.message}"); return@coroutineScope }
        val started = HashSet<String>()
        val addresses = VehicleAddressStore(this@PresenceService)

        // PRIMARY phone-key (the bonded device).
        adapter.bondedDevices.firstOrNull { it.name == RivianBle.DEVICE_NAME }?.let { primary ->
            started.add(primary.address)
            addresses.add(primary.address) // remember for the proximity-wake filters
            bleScope.launch { VehicleSession(this@PresenceService, primary, enrollment, sharedSecret, "PK", primary = true).runForever(bleScope) }
        } ?: DebugLog.add("presence: no bonded ${RivianBle.DEVICE_NAME}")

        // Location sensors: scan by the vehicle's VAS id and open a session per new device.
        val svc = vehicleServiceUuid(enrollment.vasVehicleId)
        if (svc == null) {
            DebugLog.add("presence: bad vasVehicleId '${enrollment.vasVehicleId}'; no sensor sessions")
            return@coroutineScope
        }
        while (bleScope.isActive) {
            runCatching {
                val hits = SensorScanner(this@PresenceService).discover(svc)
                // Connect strongest-RSSI (closest) sensors first — they matter most for
                // inside-cabin localization and are the most reliable to bring up.
                hits.sortedByDescending { it.rssi }.forEach { hit ->
                    if (started.add(hit.address)) {
                        addresses.add(hit.address) // remember for the proximity-wake filters
                        val label = sensorLabel(hit.name)
                        DebugLog.ble("·", label, "${hit.name ?: "?"} ${hit.address} rssi=${hit.rssi} → session")
                        val dev = adapter.getRemoteDevice(hit.address)
                        bleScope.launch { VehicleSession(this@PresenceService, dev, enrollment, sharedSecret, label, primary = false).runForever(bleScope) }
                        // Stagger connects — Android BLE can't reliably do several
                        // concurrent connect/discover attempts at once.
                        delay(STAGGER_MS)
                    }
                }
            }.onFailure { DebugLog.add("presence: sensor scan failed — ${it.message}") }
            delay(RESCAN_MS)
        }
    }

    private fun sensorLabel(name: String?): String = when {
        name == null -> "S?"
        name.startsWith("Rivian Sensor ") -> "S" + name.removePrefix("Rivian Sensor ").trim()
        else -> "PK2"
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

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.presence_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID, buildNotification(PresenceStatus.summary.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /** Build the ongoing notification showing the current vehicle connection state. */
    private fun buildNotification(state: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // "Off" action — turns the key off, stops the service, clears the notification.
        val offIntent = PendingIntent.getBroadcast(
            this, 2, Intent(this, KeyOffReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Just the "Off" icon action — Wear OS already adds its own "Open app" button from
        // the content intent, so a second open action would be redundant.
        val offAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_power), "Off", offIntent,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.presence_notification_title))
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // updates frequently — never buzz/re-alert
            .addAction(offAction)
            .build()
    }

    /** Re-post the ongoing notification with the latest connection state. */
    private fun updateNotification(state: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
        logi("notification: $state  [${PresenceStatus.snapshot()}]")
    }

    override fun onDestroy() {
        logi("PresenceService: onDestroy")
        DebugLog.add("presence: stopping (passive=$goingPassive)")
        isRunning = false
        // If we're idling passively on purpose, KEEP the offloaded proximity wake armed
        // so we get woken on approach. Any other stop (user deactivated the key) cancels it.
        if (!goingPassive) ProximityWake.disarm(this)
        // Cancel the coroutine scope FIRST so the notification observer is gone before
        // we reset state — otherwise reset()'s "Stopped" emission gets re-posted as a
        // standalone notification that outlives the service. Then remove the FG
        // notification explicitly so deactivating the key clears it.
        scope.cancel()
        PresenceStatus.reset()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wearvian_presence"
        private const val NOTIFICATION_ID = 1
        private const val RESCAN_MS = 15_000L
        private const val STAGGER_MS = 1_500L
        /** No link UP for this long → drop to passive (offloaded proximity wake). */
        private const val IDLE_TIMEOUT_MS = 5 * 60_000L
        /** How often to check the watch lock state (no reliable "device locked" broadcast). */
        private const val LOCK_POLL_MS = 2_000L
        private const val LOCKED_TEXT = "Watch locked · key paused"

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
