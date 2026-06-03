package org.fivesevenfive.wearvian.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.ble.VehicleSession
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi
import java.util.UUID

/**
 * Foreground service that maintains the drive-presence sessions. It runs one
 * [VehicleSession] to the bonded phone-key PRIMARY module AND one to each location
 * sensor it discovers (sensors advertise the vehicle's VAS id as their service UUID),
 * concurrently, so the car can triangulate the watch across them (inside → drive,
 * door → unlock). Holds a wake lock so the heartbeats keep streaming with the screen
 * off. All steps mirror to [DebugLog] → logcat. See docs/passive-entry-protocol.md.
 */
@SuppressLint("MissingPermission")
class PresenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyManager = KeyManager()
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logi("PresenceService: onStartCommand")
        startAsForeground()
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
        val sharedSecret = runCatching { keyManager.sharedSecret(enrollment.vehiclePublicKey) }
            .getOrElse { DebugLog.add("presence: ECDH failed — ${it.message}"); return }
        val started = HashSet<String>()

        // PRIMARY phone-key (the bonded device).
        adapter.bondedDevices.firstOrNull { it.name == RivianBle.DEVICE_NAME }?.let { primary ->
            started.add(primary.address)
            scope.launch { VehicleSession(this@PresenceService, primary, enrollment, sharedSecret, "PK").runForever(scope) }
        } ?: DebugLog.add("presence: no bonded ${RivianBle.DEVICE_NAME}")

        // Location sensors: scan by the vehicle's VAS id and open a session per new device.
        val svc = vehicleServiceUuid(enrollment.vasVehicleId)
        if (svc == null) {
            DebugLog.add("presence: bad vasVehicleId '${enrollment.vasVehicleId}'; no sensor sessions")
            return
        }
        while (scope.isActive) {
            runCatching {
                val hits = SensorScanner(this).discover(svc)
                hits.forEach { hit ->
                    if (started.add(hit.address)) {
                        val label = sensorLabel(hit.name)
                        DebugLog.ble("·", label, "${hit.name ?: "?"} ${hit.address} rssi=${hit.rssi} → session")
                        val dev = adapter.getRemoteDevice(hit.address)
                        scope.launch { VehicleSession(this@PresenceService, dev, enrollment, sharedSecret, label).runForever(scope) }
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
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wearvian_presence"
        private const val NOTIFICATION_ID = 1
        private const val RESCAN_MS = 15_000L
        private const val STAGGER_MS = 4_000L

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
