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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.ProximityWake
import org.fivesevenfive.wearvian.tile.TileRefresher
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.ble.VehicleSession
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.VehicleAddressStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi

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
    private var stableJob: Job? = null // drops to passive when connected-but-idle (see monitorStableIdle)
    private var lockJob: Job? = null
    private var tileJob: Job? = null
    private var departJob: Job? = null // debounces a MATCH_LOST before we treat the car as gone
    private var drivingJob: Job? = null // driving-doze: drop the wake lock while the car is in gear
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /**
     * True while idling passively: the service + foreground notification STAY up, the wake lock
     * is released and the BLE sessions are torn down, and a single hardware-offloaded scan watches
     * for the vehicle. Because we never leave the foreground, [proximityCallback] can rebuild BLE
     * on approach without a background FGS-start (which Android-12 blocks) — and without CDM, which
     * Wear OS forbids 3p apps from using.
     */
    @Volatile private var passive = false

    /** True while the watch is locked (off wrist): all BLE is torn down (anti-theft). */
    @Volatile private var locked = false

    /**
     * True while in "driving-doze": the car is in gear, so we release the wake lock and pause
     * heartbeats but KEEP the connection + 0x1c subscription, to still catch the return to Park.
     * See [monitorDriving].
     */
    @Volatile private var driving = false

    /**
     * Latched true if the car dropped the link while heartbeats were paused (driving-doze) — disables
     * driving-doze for the rest of THIS drive (until gear returns to Park), so we don't churn
     * pause→drop→reconnect→pause. Cleared on the next Park.
     */
    @Volatile private var drivingDozeDisabled = false

    /**
     * True once [onDestroy] has begun. Gates [updateNotification] so a notification observer racing
     * on another thread can't re-post the ongoing notification *after* we've removed it — which would
     * leave a standalone notification (same id, no longer owned by the service) lingering on screen.
     * Cancelling the scope is async (no join), so ordering alone can't close this race; this flag does.
     */
    @Volatile private var stopping = false

    /**
     * In passive mode: false until we've confirmed (via a debounced MATCH_LOST) that the car has
     * actually left range. While false, a FIRST_MATCH is just the car we're still parked beside —
     * ignore it, or we'd snap straight back to active. Once true, the next FIRST_MATCH is a genuine
     * return → wake. Set on [enterPassive] from whether a link is currently up (gone → true).
     */
    @Volatile private var seenDeparture = false

    /**
     * In-process proximity watch (passive mode). FIRST_MATCH = the car is in range; MATCH_LOST = its
     * advertisement is no longer seen. We only wake on FIRST_MATCH once we've seen the car leave
     * ([seenDeparture]); a MATCH_LOST arms that, after [DEPART_DEBOUNCE_MS] so a brief dropout while
     * parked nearby doesn't count as leaving.
     */
    private val proximityCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            when (callbackType) {
                android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                    if (departJob?.isActive == true) return // already counting down a departure
                    DebugLog.add("presence: proximity lost ${result.device?.address} → arming return watch")
                    departJob = scope.launch {
                        delay(DEPART_DEBOUNCE_MS)
                        seenDeparture = true
                        DebugLog.add("presence: car gone ${DEPART_DEBOUNCE_MS / 1000}s → a return now wakes")
                    }
                }
                else -> { // CALLBACK_TYPE_FIRST_MATCH
                    departJob?.cancel() // car is (back) in range — cancel any pending "it left"
                    if (seenDeparture) {
                        DebugLog.add("presence: proximity return ${result.device?.address} rssi=${result.rssi} → wake")
                        scope.launch { exitPassive() }
                    } else {
                        DebugLog.add("presence: proximity hit ${result.device?.address} (parked nearby) → ignore")
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            DebugLog.add("presence: proximity scan failed err=$errorCode")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val goPassiveNow = intent?.action == ACTION_GO_PASSIVE
        logi("PresenceService: onStartCommand action=${intent?.action}")
        startAsForeground()
        val enrollment = EnrollmentStore(this).load()
        if (enrollment == null) {
            DebugLog.add("presence: not enrolled; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // lockJob is the "service is already running" marker (loopJob can be inactive while locked).
        if (lockJob?.isActive == true) {
            when {
                goPassiveNow -> { DebugLog.add("presence: manual passive (already running)"); enterPassive() }
                passive -> { DebugLog.add("presence: start while passive → wake"); exitPassive() }
                else -> DebugLog.add("presence: already running; ignoring duplicate start")
            }
            return START_STICKY
        }
        _running.value = true
        // Anti-theft: don't bring BLE up while the watch is locked (off wrist); monitorLock
        // restores it on unlock. Otherwise go straight to passive (manual button) or active.
        locked = keyguard?.isDeviceLocked == true
        when {
            locked -> updateNotification(LOCKED_TEXT)
            goPassiveNow -> { DebugLog.add("presence: manual passive requested"); enterPassive() }
            else -> { acquireWakeLock(); startBle() }
        }
        // Mirror the aggregate connection state into the ongoing notification, like the official
        // app's "vehicle connected / disconnected" persistent notification — but while locked or
        // passive, hold that status text instead of link state.
        // While locked / passive / driving-doze, the notification shows a fixed status (LOCKED_TEXT /
        // PASSIVE_TEXT / DRIVING_TEXT) — don't let the live link-count summary clobber it.
        notifJob = scope.launch { PresenceStatus.summary.collect { if (!locked && !passive && !driving) updateNotification(it) } }
        // Drop to passive (stay-alive + in-process proximity watch) after a stretch with no link.
        idleJob = scope.launch { monitorIdle() }
        // Also drop to passive while still connected but parked-idle (state steady for a while).
        stableJob = scope.launch { monitorStableIdle() }
        // Tear down / restore BLE as the watch locks / unlocks.
        lockJob = scope.launch { monitorLock() }
        // Keep the (background) tile's vehicle-state shading in sync, heavily debounced.
        tileJob = scope.launch { refreshTileOnStatusChange() }
        // Drop the wake lock + heartbeats while the car is in gear (debug builds only; see below).
        drivingJob = scope.launch { monitorDriving() }
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
                    if (passive) { ProximityWake.stopScan(this, proximityCallback); departJob?.cancel(); passive = false; _passive.value = false }
                    driving = false; VehicleSession.heartbeatsPaused = false // locked overrides driving-doze
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
     * After [IDLE_TIMEOUT_MS] with no link UP (car out of range / asleep), drop to passive: keep
     * the service + notification up but release the wake lock, tear down BLE, and let a single
     * offloaded scan watch for the vehicle. We then block until [exitPassive] revives the link on
     * approach, and resume watching for the next idle stretch.
     */
    private suspend fun monitorIdle() {
        while (true) {
            // Wait until no link is UP. Reading the flow (not a single collectLatest pass)
            // also recovers from a stale `connected==true` carried over a process restart.
            PresenceStatus.connected.first { !it }
            if (passive) {
                // Already idling (e.g. manual passive) — wait until the proximity watch revives us.
                PresenceStatus.connected.first { it }
                continue
            }
            // Disconnected — go passive only if we stay disconnected for the whole window;
            // a reconnect within it cancels the attempt.
            val reconnected = withTimeoutOrNull(IDLE_TIMEOUT_MS) { PresenceStatus.connected.first { it } }
            if (reconnected != null) continue // came back on its own
            // Power-save off, or couldn't arm the scan → stay foreground-active and retry next cycle.
            if (!goPassive()) continue
            PresenceStatus.connected.first { it } // passive: block until the proximity watch wakes us
        }
    }

    /**
     * Drop to passive while the car is STILL connected but nothing is happening: when the meaningful
     * vehicle state (lock / closures / charge / asleep) holds steady for the timeout, we release the
     * wake lock and tear down the sessions, watching via the offloaded scan instead — the same
     * battery win as [monitorIdle], but for the "parked right next to it" case the disconnect-based
     * idle never catches. Unlike [monitorIdle] this is ALWAYS-ON (no power-save gate): it costs no
     * capability (commands fall back to a one-shot connect; the car wakes us on the depart→return
     * cycle), it only stops the continuous heartbeat. Any meaningful change resets the timer, so the
     * door you open as you walk up keeps the key active.
     */
    private suspend fun monitorStableIdle() {
        while (true) {
            delay(STABLE_CHECK_MS)
            if (passive || locked) continue
            val s = VehicleStatus.state.value
            if (!s.valid || !s.live) continue // no confirmed state to judge stability from
            val snap = stableSnapshot(s)
            // Plugged in is a strong "home/long stay" signal → drop sooner; otherwise be conservative
            // so a normal errand stop doesn't idle the key out from under the user.
            val timeout = if (s.chargeState in PLUGGED_STATES) PARKED_PLUG_TIMEOUT_MS else PARKED_IDLE_TIMEOUT_MS
            // Hold for `timeout`; break the instant anything meaningful changes. withTimeoutOrNull
            // returns null iff the full window elapsed unchanged (→ go passive); non-null if we broke.
            val changed = withTimeoutOrNull(timeout) {
                while (true) {
                    delay(STABLE_CHECK_MS)
                    if (passive || locked) break
                    val s2 = VehicleStatus.state.value
                    if (!s2.valid || !s2.live || stableSnapshot(s2) != snap) break
                }
            }
            if (changed == null && !passive && !locked) {
                DebugLog.add("presence: state stable ${timeout / 60_000}m → passive (parked-idle)")
                enterPassive()
                PresenceStatus.connected.first { it } // block until reactivated (return / manual / unlock)
            }
        }
    }

    /** Fields that signal user activity; SoC / range / cabin-temp drift passively and are excluded. */
    private fun stableSnapshot(s: VehicleStatus.State) =
        listOf(s.locked, s.anyDoorOpen, s.anyWindowOpen, s.frunkOpen, s.liftgateOpen, s.chargeState, s.asleep)

    /**
     * Driving-doze: when the gear leaves Park the car is started and being driven, so we don't need
     * to hold presence. Release the wake lock and pause heartbeats, but KEEP the GATT link + 0x1c
     * subscription — the BT controller still delivers status frames while the CPU dozes, so we catch
     * the return to Park (which re-arms presence). The watch stays in BLE range the whole drive (it's
     * on the driver's wrist), so the link doesn't drop from range.
     *
     * VALIDATED on-vehicle 2026-06-16 (drive-sleep.log): 0x1c kept streaming through the heartbeat-less
     * window on a single continuous link (no reconnect). If the link ever DOES drop mid-gear
     * (interference / a module hiccup), [drivingDozeDisabled] latches the optimization off for the rest
     * of the drive and we resume normal presence.
     */
    private suspend fun monitorDriving() {
        combine(VehicleStatus.state, PresenceStatus.connected) { st, conn ->
            st.gear to (conn && st.valid && st.live)
        }.distinctUntilChanged().collect { (gear, liveConnected) ->
            if (locked || passive) return@collect // those modes own the wake lock / BLE
            val inGear = gear != VehicleStatus.Gear.PARK && gear != VehicleStatus.Gear.UNKNOWN
            when {
                !inGear -> { // Park (or no reading): normal presence; clear the per-drive latch
                    drivingDozeDisabled = false
                    if (driving) exitDriving("back in Park")
                }
                !liveConnected -> if (driving) { // in gear but the link is gone → hypothesis failed
                    exitDriving("link dropped without heartbeats")
                    drivingDozeDisabled = true // don't retry until Park, or we'd churn pause→drop→reconnect
                    DebugLog.add("presence: driving-doze FAILED — car dropped the heartbeat-less link; holding presence for the rest of this drive")
                }
                else -> if (!driving && !drivingDozeDisabled) enterDriving(gear)
            }
        }
    }

    /** Enter driving-doze: keep the connection/0x1c, drop the wake lock + heartbeats. */
    private fun enterDriving(gear: VehicleStatus.Gear) {
        if (driving) return
        driving = true
        VehicleSession.heartbeatsPaused = true
        releaseWakeLock()
        updateNotification(DRIVING_TEXT)
        DebugLog.add("presence: → driving-doze (gear=$gear) — wake lock released, heartbeats paused, link kept for 0x1c")
        TileRefresher.refresh(this)
    }

    /** Leave driving-doze: resume presence (wake lock + heartbeats). */
    private fun exitDriving(why: String) {
        if (!driving) return
        driving = false
        VehicleSession.heartbeatsPaused = false
        acquireWakeLock()
        updateNotification(PresenceStatus.summary.value)
        DebugLog.add("presence: ← driving-doze ($why) — wake lock reacquired, heartbeats resumed")
        TileRefresher.refresh(this)
    }

    /**
     * Refresh the tile when the vehicle state it draws actually changes — heavily debounced,
     * because the 0x1c stream flickers fast (mid-unlock especially) and a tile update is
     * expensive/rate-limited. Maps to only the fields the tile renders so changes it ignores
     * (doors/windows) don't trigger churn, dedupes, then waits for the state to settle. Refreshes
     * are no-ops while the app is foreground (see [TileRefresher]).
     */
    @OptIn(FlowPreview::class)
    private suspend fun refreshTileOnStatusChange() {
        VehicleStatus.state
            .map { listOf(it.valid, it.live, it.locked, it.frunkOpen, it.liftgateOpen) }
            .distinctUntilChanged()
            .debounce(TILE_REFRESH_DEBOUNCE_MS)
            .collect { TileRefresher.refresh(this) }
    }

    /** Auto idle→passive (always on, except while locked). @return true iff passive. */
    private fun goPassive(): Boolean {
        if (passive) return true
        // While locked, BLE is already torn down by monitorLock — don't go passive; just keep
        // the (cheap) foreground service alive until the watch is unlocked.
        if (locked) return false
        DebugLog.add("presence: idle ${IDLE_TIMEOUT_MS / 60_000}m → passive")
        return enterPassive()
    }

    /**
     * Enter passive idle WITHOUT stopping the service: tear down BLE, release the wake lock, and
     * arm the in-process proximity watch. The foreground notification stays up (so we can rebuild
     * on approach without a background FGS-start). Used by auto-idle AND the manual button.
     */
    private fun enterPassive(): Boolean {
        if (passive) return true
        val enrollment = EnrollmentStore(this).load() ?: run { stopSelf(); return true }
        passive = true
        _passive.value = true
        departJob?.cancel()
        // If no link is up the car is already gone → watch for its return immediately. If one is up
        // (parked-idle, or the manual button while at the car) wait for a MATCH_LOST first, so the
        // ever-present car doesn't snap us straight back to active via FIRST_MATCH.
        seenDeparture = !PresenceStatus.connected.value
        scope.launch { stopBle() }
        VehicleStatus.clear() // no session confirming state in passive — keep it, mark stale (dimmed)
        driving = false; VehicleSession.heartbeatsPaused = false // leaving active — clear driving-doze
        releaseWakeLock()
        val watching = ProximityWake.scanForVehicle(this, enrollment.vasVehicleId, proximityCallback)
        DebugLog.add("presence: → passive (carPresent=${!seenDeparture}, service alive); proximity watch=$watching")
        updateNotification(PASSIVE_TEXT)
        TileRefresher.refresh(this) // active→passive: tile state changed
        return true
    }

    /** Proximity woke us (or unlock): leave passive — re-acquire the wake lock and rebuild BLE. */
    private fun exitPassive() {
        if (!passive) return
        passive = false
        _passive.value = false
        departJob?.cancel()
        ProximityWake.stopScan(this, proximityCallback)
        if (locked) {
            DebugLog.add("presence: proximity woke but watch locked — staying down")
            updateNotification(LOCKED_TEXT)
            return
        }
        acquireWakeLock()
        startBle()
        updateNotification(PresenceStatus.summary.value)
        DebugLog.add("presence: proximity wake → active")
        TileRefresher.refresh(this) // passive→active: tile state changed
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
        val svc = RivianBle.vehicleServiceUuid(enrollment.vasVehicleId)
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
        // Disables the key: turns it off, stops the service, clears the notification.
        val offAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_power), "Disable", offIntent,
        ).build()
        // The MODE (active / passive / paused) lives in the title; the content text is pure detail
        // (link state, "Waiting for vehicle", etc.) — so the two never contradict each other.
        val mode = when {
            locked -> "paused"
            driving -> "driving"
            passive -> "passive"
            else -> "active"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.presence_notification_title)} · $mode")
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
        if (stopping) return // teardown in progress — never re-post after we've removed the notification
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
        logi("notification: $state  [${PresenceStatus.snapshot()}]")
    }

    override fun onDestroy() {
        logi("PresenceService: onDestroy")
        DebugLog.add("presence: stopping")
        // Latch teardown BEFORE anything else: from here on updateNotification is a no-op, so a
        // notification observer racing on another thread can't re-post after we remove the FG
        // notification below (which would leave it lingering — the "Off didn't clear it" bug).
        stopping = true
        _running.value = false
        _passive.value = false
        // A real stop (user deactivated the key, or system kill) — stop the proximity watch.
        ProximityWake.stopScan(this, proximityCallback)
        departJob?.cancel()
        // Tear down the coroutine scope (the notification observer lives here) and reset shared
        // state, then remove the FG notification explicitly so deactivating the key clears it.
        scope.cancel()
        driving = false; VehicleSession.heartbeatsPaused = false // never leak the pause to a future session
        PresenceStatus.reset()
        VehicleStatus.clear() // keep last-known state but mark it stale (no session confirming it)
        TileRefresher.refresh(this) // active/passive→off: key no longer armed; refresh the tile
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
        /** No link UP for this long → drop to passive (stay-alive + offloaded proximity watch). */
        private const val IDLE_TIMEOUT_MS = 5 * 60_000L
        /** How often [monitorStableIdle] samples vehicle state to judge "nothing's changed". */
        private const val STABLE_CHECK_MS = 30_000L
        /** Connected but state steady this long → drop to passive. Plugged in = strong stay signal. */
        private const val PARKED_PLUG_TIMEOUT_MS = 5 * 60_000L
        private const val PARKED_IDLE_TIMEOUT_MS = 15 * 60_000L
        /** Debounce a MATCH_LOST before counting the car as gone, so a brief dropout isn't a "left". */
        private const val DEPART_DEBOUNCE_MS = 30_000L
        /** Charge states that mean the cord is connected (idle drops faster when plugged in). */
        private val PLUGGED_STATES = setOf(
            VehicleStatus.ChargeState.CHARGING,
            VehicleStatus.ChargeState.PLUGGED_IDLE,
            VehicleStatus.ChargeState.STARTING,
        )
        /** How often to check the watch lock state (no reliable "device locked" broadcast). */
        private const val LOCK_POLL_MS = 2_000L
        /** Settle time before refreshing the tile on a 0x1c change — the stream flickers fast. */
        private const val TILE_REFRESH_DEBOUNCE_MS = 3_000L
        private const val LOCKED_TEXT = "Watch locked"
        private const val PASSIVE_TEXT = "Waiting for vehicle"
        private const val DRIVING_TEXT = "In gear · presence paused"

        /** Live "is the presence service running" — observed by the UI for the active/passive flip. */
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        val isRunning: Boolean get() = _running.value

        /** Live "is the service idling passively" (running, BLE down, scanning for approach). */
        private val _passive = MutableStateFlow(false)
        val passive: StateFlow<Boolean> = _passive

        const val ACTION_GO_PASSIVE = "org.fivesevenfive.wearvian.GO_PASSIVE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }

        /** Drop a running service to passive immediately (manual button). */
        fun goPassiveNow(context: Context) {
            context.startForegroundService(
                Intent(context, PresenceService::class.java).setAction(ACTION_GO_PASSIVE),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }
    }
}
