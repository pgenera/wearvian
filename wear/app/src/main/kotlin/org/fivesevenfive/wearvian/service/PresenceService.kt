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
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.fivesevenfive.wearvian.ble.CommandBus
import org.fivesevenfive.wearvian.ble.PassiveAutoConnect
import org.fivesevenfive.wearvian.ble.ProximityWake
import org.fivesevenfive.wearvian.tile.TileRefresher
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.ble.VehicleSession
import org.fivesevenfive.wearvian.complication.ComplicationRefresher
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.store.DebugOverrides
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.store.TelemetryStore
import org.fivesevenfive.wearvian.store.logPersistentState
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

    // Single-thread confinement. Every lifecycle transition (enterPassive / exitPassive /
    // startActivePresence / lock / driving-doze) mutates shared @Volatile flags with non-atomic
    // check-then-act, and they're triggered from many coroutines (the idle/stable/lock/driving
    // monitors, the proximity + autoConnect callbacks, onStartCommand). Limiting the scope to a
    // single dispatcher thread makes each transition's synchronous body atomic w.r.t. the others,
    // removing the interleave races (e.g. a FIRST_MATCH exitPassive racing enterPassive's deferred
    // `passiveLink.arm`, which could strand an autoConnect armed while active). BLE work here is
    // callback-driven and suspends on every await/delay, so one thread doesn't bottleneck it. All
    // transition entry points therefore run on `scope` (onStartCommand launches onto it below).
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val keyManager = KeyManager()
    private val settings by lazy { SettingsStore(this) }
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    /** Passive wake: an autoConnect pending on the bonded PK, alongside the FIRST_MATCH scan. */
    private val passiveLink = PassiveAutoConnect { scope.launch { exitPassive() } }
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null   // owns the BLE work (sessions + scan); torn down when locked
    private var notifJob: Job? = null
    private var idleJob: Job? = null
    private var stableJob: Job? = null // drops to passive when connected-but-idle (see monitorStableIdle)
    private var lockJob: Job? = null
    private var tileJob: Job? = null
    private var departJob: Job? = null // debounces a MATCH_LOST before we treat the car as gone
    private var drivingJob: Job? = null // driving-doze: drop the wake lock while the car is in gear
    private var watchJob: Job? = null   // watch-mode burst presence (only when registered as a watch)
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

    /** Snapshot of [passive] taken at lock, so unlock restores the same mode instead of forcing active. */
    @Volatile private var wasPassive = false

    /**
     * True when this key was registered with Rivian as a WATCH (keyDeviceSubtype="WATCH") — the car
     * does NO passive lock/unlock for it, so proximity presence on approach/departure is wasted. The
     * gating signal for the watch-mode presence lifecycle (presence only around the drive window).
     * Set on start from the stored enrollment; defaults false (phone, full proximity) for installs
     * enrolled before the device-type plumbing. Behavior gated on this lands incrementally.
     */
    @Volatile private var watchMode = false

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
                        settings.passiveSeenDeparture = true // keep the persisted sub-state current
                        _parkedNearby.value = false // no longer parked beside it — now waiting for return
                        updateNotification(PASSIVE_TEXT) // relabel from "Parked nearby" → "Waiting for vehicle"
                        // Car has actually left → now arm the autoConnect so its RETURN reconnects PK
                        // directly. We don't arm it while parked nearby, or it would immediately
                        // reconnect and bounce us straight out of passive.
                        passiveLink.arm(this@PresenceService)
                        DebugLog.add("presence: car gone ${DEPART_DEBOUNCE_MS / 1000}s → a return now wakes (scan + PK autoConnect)")
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
        logPersistentState(this) // dump all persisted state + the transient switch at startup
        watchMode = enrollment.asWatch || DebugOverrides.forceWatch
        DebugLog.add("presence: key acts as ${if (watchMode) "WATCH — manual lock/unlock, no passive entry" else "PHONE — full proximity"} (enrolled asWatch=${enrollment.asWatch}, forceWatch=${DebugOverrides.forceWatch})")
        // lockJob is the "service is already running" marker (loopJob can be inactive while locked).
        if (lockJob?.isActive == true) {
            // Transitions run on `scope` (the single-thread lane) so they can't interleave with the
            // monitors — onStartCommand itself is on the main thread, so launch onto scope.
            when {
                goPassiveNow -> { DebugLog.add("presence: manual passive (already running)"); scope.launch { enterPassive() } }
                passive -> { DebugLog.add("presence: start while passive → wake"); scope.launch { exitPassive() } }
                else -> DebugLog.add("presence: already running; ignoring duplicate start")
            }
            return START_STICKY
        }
        _running.value = true
        // Anti-theft: don't bring BLE up while the watch is locked (off wrist); monitorLock
        // restores it on unlock. Otherwise go straight to passive (manual button) or active.
        // Transitions launch onto `scope` (single-thread lane) so they serialize with the monitors.
        // On an app upgrade (ACTION_UPGRADE_RESTORE — a near-instant process swap) resume the persisted
        // passive idle so a parked-nearby key doesn't snap back to active; any other start ignores it.
        val restoreMode = intent?.action == ACTION_UPGRADE_RESTORE && settings.passiveIdle
        locked = keyguard?.isDeviceLocked == true
        when {
            locked -> { if (restoreMode) wasPassive = true; updateNotification(LOCKED_TEXT) }
            goPassiveNow -> { DebugLog.add("presence: manual passive requested"); scope.launch { enterPassive() } }
            restoreMode -> {
                DebugLog.add("presence: upgrade — resuming passive idle (seenDeparture=${settings.passiveSeenDeparture})")
                scope.launch { enterPassive(restoreSeenDeparture = settings.passiveSeenDeparture) }
            }
            else -> scope.launch { startActivePresence() }
        }
        // Mirror the aggregate connection state into the ongoing notification, like the official
        // app's "vehicle connected / disconnected" persistent notification — but while locked or
        // passive, hold that status text instead of link state.
        // While locked / passive / heartbeats-paused (driving-doze OR watch-mode standby), the
        // notification holds a fixed status text (LOCKED_TEXT / PASSIVE_TEXT / DRIVING_TEXT /
        // WATCH_DOZE_TEXT) — don't let the live link-count summary clobber it.
        notifJob = scope.launch { PresenceStatus.summary.collect { if (!locked && !passive && !VehicleSession.heartbeatsPaused) updateNotification(it) } }
        // Drop to passive (stay-alive + in-process proximity watch) after a stretch with no link.
        idleJob = scope.launch { monitorIdle() }
        // Also drop to passive while still connected but parked-idle (state steady for a while).
        stableJob = scope.launch { monitorStableIdle() }
        // Tear down / restore BLE as the watch locks / unlocks.
        lockJob = scope.launch { monitorLock() }
        // Keep the (background) tile's vehicle-state shading in sync, heavily debounced.
        tileJob = scope.launch { refreshTileOnStatusChange() }
        // Persist battery SoC + range and refresh the watch-face complications when they change.
        scope.launch { persistTelemetryOnChange() }
        // Drop the wake lock + heartbeats while the car is in gear (phone mode; watch mode handles
        // gear itself via monitorWatchPresence).
        drivingJob = scope.launch { monitorDriving() }
        // Watch keys: doze by default, burst presence only around the drive window.
        watchJob = scope.launch { monitorWatchPresence() }
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
     * Bring up the BLE sessions. PHONE keys hold the wake lock for continuous presence (passive
     * unlock/lock + drive). WATCH keys come up in DOZE — connection + 0x1c kept, but heartbeats paused
     * and no wake lock — since the car does no passive lock/unlock for them; [monitorWatchPresence]
     * bursts presence on only around the drive window.
     */
    private fun startActivePresence() {
        settings.passiveIdle = false // going active — a later upgrade restart should NOT resume passive
        if (watchMode) {
            VehicleSession.heartbeatsPaused = true // doze by default; bursts un-pause it
            startBle()
        } else {
            acquireWakeLock()
            startBle()
        }
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
                    wasPassive = passive // remember the mode so unlock restores it, not a forced active probe
                    if (passive) { ProximityWake.stopScan(this, proximityCallback); passiveLink.disarm(); departJob?.cancel(); passive = false; _passive.value = false; _parkedNearby.value = false }
                    driving = false; VehicleSession.heartbeatsPaused = false // locked overrides driving-doze
                    stopBle()
                    releaseWakeLock()
                    updateNotification(LOCKED_TEXT)
                } else if (wasPassive) {
                    // Were idling passively (car out of range) → come back up passive: re-arm the proximity
                    // scan, no wake lock. If the car arrived while off-wrist, FIRST_MATCH promotes us to
                    // active within seconds. Avoids re-paying a full ~45s active probe on every unlock.
                    DebugLog.add("presence: watch unlocked — restoring passive idle")
                    enterPassive() // sets its own passive notification text (parked-nearby vs waiting)
                } else {
                    DebugLog.add("presence: watch unlocked — restoring active presence")
                    startActivePresence()
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
            // Disconnected — go passive as soon as the car's nodes are confirmed out of range (a quick
            // offloaded scan), instead of always holding active for IDLE_TIMEOUT_MS. A reconnect, or the
            // scan still seeing the nodes advertising, means it's a blip → stay active.
            val disconnectedAt = SystemClock.elapsedRealtime()
            if (!departed(disconnectedAt)) continue // reconnected, or car still in range → keep active
            if (!goPassive()) continue
            PresenceStatus.connected.first { it } // passive: block until the proximity watch wakes us
        }
    }

    /**
     * After the links drop, decide whether the car has actually LEFT (→ passive now) vs a transient
     * blip (→ keep active). Arms an offloaded scan for the car's nodes (they advertise once we're no
     * longer connected to them — the same way passive mode finds them on return). If neither a
     * reconnect nor a node sighting happens within [IDLE_PROBE_MS], the car is out of range → go
     * passive, without holding active for the full [IDLE_TIMEOUT_MS]. If the nodes ARE seen (car
     * nearby) it's a blip, so we wait for the reconnect — but never past the IDLE_TIMEOUT_MS backstop
     * measured from [disconnectedAt]. @return true iff the car is gone (caller should go passive).
     */
    private suspend fun departed(disconnectedAt: Long): Boolean {
        val vasId = EnrollmentStore(this).load()?.vasVehicleId
            ?: return withTimeoutOrNull(IDLE_TIMEOUT_MS) { PresenceStatus.connected.first { it } } == null
        val seen = java.util.concurrent.atomic.AtomicBoolean(false)
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (callbackType == android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH &&
                    seen.compareAndSet(false, true)) {
                    DebugLog.add("idle: car node ${result.device?.address} in range — blip, staying active")
                }
            }
        }
        if (!ProximityWake.scanForVehicle(this, vasId, cb)) {
            // No scan available (BT off / no filters) → fall back to the old fixed wait.
            return withTimeoutOrNull(IDLE_TIMEOUT_MS) { PresenceStatus.connected.first { it } } == null
        }
        try {
            // Give the offloaded scan a short window to spot the car (or for a reconnect to happen).
            withTimeoutOrNull(IDLE_PROBE_MS) {
                while (!seen.get() && !PresenceStatus.connected.value && !passive && !locked) delay(500)
            }
            return when {
                passive || locked -> false
                PresenceStatus.connected.value -> false // reconnected on its own — blip
                !seen.get() -> { DebugLog.add("idle: car nodes out of range ${IDLE_PROBE_MS / 1000}s → passive"); true }
                else -> {
                    // Car's still advertising nearby — a blip. Wait for the reconnect, capped by the
                    // IDLE_TIMEOUT_MS backstop from when the links first dropped.
                    val remaining = IDLE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - disconnectedAt)
                    remaining <= 0 || withTimeoutOrNull(remaining) { PresenceStatus.connected.first { it } } == null
                }
            }
        } finally {
            ProximityWake.stopScan(this, cb)
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
        // Watch mode stays in doze while connected (no heartbeats/wake lock anyway) and MUST keep the
        // link to watch 0x1c for the door-open trigger — so don't tear it down here; departure →
        // passive is still handled by monitorIdle (no link UP) when the car actually drops/leaves.
        if (watchMode) return
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
        if (watchMode) return // watch mode manages gear/heartbeats in monitorWatchPresence
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
     * Watch-mode presence lifecycle (asWatch enrollments only). A watch key gets no passive lock/unlock
     * from the car, so we don't stream heartbeats on approach / dwell / departure — the sessions stay
     * connected (0x1c flowing) but DOZE: heartbeats paused, no wake lock. We BURST full presence only
     * around the drive window so drive-enable works, then fall back to doze.
     *
     * Burst triggers (any): a door opens, the vehicle wakes (asleep→awake), or a manual command (the
     * heartbeat loop won't drain a queued command while paused, so a tap has to wake the burst). A burst
     * holds for [WATCH_BURST_MS] of inactivity, then drops to doze; it also drops the instant the car
     * reports it went back to sleep, and never runs while actually driving (gear ≠ Park — already
     * validated heartbeat-free via driving-doze). UNVALIDATED on-vehicle: whether the link survives
     * heartbeat-less while parked-present long enough to catch the door-open is the thing to test.
     */
    private suspend fun monitorWatchPresence() {
        if (!watchMode) return
        var prevDoorOpen = false
        var prevAsleep = false
        // elapsedRealtime() of the last door-open / vehicle-woke trigger; combined with
        // CommandBus.lastSubmitMs (manual command). Local — only used in this loop.
        var lastWatchTriggerMs = 0L
        while (true) {
            delay(WATCH_TICK_MS)
            if (locked || passive) { prevDoorOpen = false; prevAsleep = false; continue }
            val st = VehicleStatus.state.value
            if (st.valid) {
                if (st.anyDoorOpen && !prevDoorOpen) { lastWatchTriggerMs = SystemClock.elapsedRealtime(); DebugLog.add("watch: trigger = door opened") }
                if (!st.asleep && prevAsleep) { lastWatchTriggerMs = SystemClock.elapsedRealtime(); DebugLog.add("watch: trigger = vehicle woke") }
                prevDoorOpen = st.anyDoorOpen
                prevAsleep = st.asleep
            }
            val now = SystemClock.elapsedRealtime()
            val carAsleep = st.valid && st.asleep
            val inGear = st.valid && st.gear != VehicleStatus.Gear.PARK && st.gear != VehicleStatus.Gear.UNKNOWN
            // A MANUAL command must burst so the heartbeat loop can drain it — even if the car is asleep
            // or in gear (else a tapped lock/unlock is queued and never sent: the heartbeat loop won't
            // drain while paused). Passive triggers (door / wake) only burst while parked & awake — the
            // drive-enable window — and stop when the car sleeps or you shift into gear.
            val recentManual = CommandBus.lastSubmitMs > 0L && now - CommandBus.lastSubmitMs < WATCH_BURST_MS
            val recentPassive = lastWatchTriggerMs > 0L && now - lastWatchTriggerMs < WATCH_BURST_MS
            val wantBurst = recentManual || (recentPassive && !carAsleep && !inGear)
            // Drive off the real heartbeat flag (not a local) so this self-corrects after a lock/passive
            // reset flips it. In watch mode only this monitor un-pauses, so paused==false ⟺ bursting.
            if (wantBurst && VehicleSession.heartbeatsPaused) {
                acquireWakeLock()
                VehicleSession.heartbeatsPaused = false
                DebugLog.add("watch: → burst — presence ON (drive-intent)")
                updateNotification(PresenceStatus.summary.value)
            } else if (!wantBurst && !VehicleSession.heartbeatsPaused) {
                VehicleSession.heartbeatsPaused = true
                releaseWakeLock()
                val why = if (inGear) "driving" else if (carAsleep) "vehicle asleep" else "idle timeout"
                DebugLog.add("watch: → doze — presence OFF ($why)")
                updateNotification(WATCH_DOZE_TEXT)
            }
        }
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

    /**
     * Persist the latest battery SoC + estimated range to [TelemetryStore] and re-query the
     * watch-face complications when either value changes. The complications read the persisted
     * value (this process is usually dead when the system binds them), so the store must hold the
     * last-known reading. Keyed on (soc, range) only — door/lock churn doesn't write — and debounced
     * so the fast-flickering 0x1c stream doesn't thrash SharedPreferences.
     */
    @OptIn(FlowPreview::class)
    private suspend fun persistTelemetryOnChange() {
        VehicleStatus.state
            .map { it.socPercent to it.rangeKm }
            .distinctUntilChanged()
            .debounce(TILE_REFRESH_DEBOUNCE_MS)
            .collect { (soc, range) ->
                if (soc == null && range == null) return@collect // nothing decoded yet
                TelemetryStore(this).save(soc, range)
                ComplicationRefresher.refresh(this)
                DebugLog.add("telemetry: soc=${soc}% range=${range}km → complications")
            }
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
    private fun enterPassive(restoreSeenDeparture: Boolean? = null): Boolean {
        if (passive) return true
        val enrollment = EnrollmentStore(this).load() ?: run { stopSelf(); return true }
        passive = true
        _passive.value = true
        departJob?.cancel()
        // If no link is up the car is already gone → watch for its return immediately. If one is up
        // (parked-idle, or the manual button while at the car) wait for a MATCH_LOST first, so the
        // ever-present car doesn't snap us straight back to active via FIRST_MATCH. On an upgrade
        // restore, use the PERSISTED sub-state instead — a fresh process has no link, which recompute
        // would read as "departed" and arm an autoConnect that bounces us active next to the car.
        seenDeparture = restoreSeenDeparture ?: !PresenceStatus.connected.value
        _parkedNearby.value = !seenDeparture // in range → parked nearby; gone → waiting for return
        // Persist so a near-instant restart (app upgrade) can resume passive with the same sub-state.
        settings.passiveIdle = true
        settings.passiveSeenDeparture = seenDeparture
        // Tear the active sessions down, THEN arm the autoConnect wake on PK — but ONLY if the car is
        // already gone (seenDeparture). While parked nearby it's still advertising, so an autoConnect
        // would reconnect immediately and bounce us out of passive; in that case we wait for the scan's
        // MATCH_LOST to confirm departure and arm autoConnect then. (After stopBle so the old PK GATT
        // is closed and we don't briefly hold two clients to the same device.)
        scope.launch { stopBle(); if (seenDeparture) passiveLink.arm(this@PresenceService) }
        VehicleStatus.clear() // no session confirming state in passive — keep it, mark stale (dimmed)
        driving = false; VehicleSession.heartbeatsPaused = false // leaving active — clear driving-doze
        releaseWakeLock()
        val watching = ProximityWake.scanForVehicle(this, enrollment.vasVehicleId, proximityCallback)
        DebugLog.add("presence: → passive (carPresent=${!seenDeparture}, service alive); proximity watch=$watching, autoConnect=${seenDeparture}")
        updateNotification(if (seenDeparture) PASSIVE_TEXT else PARKED_TEXT)
        TileRefresher.refresh(this) // active→passive: tile state changed
        return true
    }

    /** Proximity woke us (or unlock): leave passive — re-acquire the wake lock and rebuild BLE. */
    private fun exitPassive() {
        if (!passive) return
        passive = false
        _passive.value = false
        _parkedNearby.value = false
        departJob?.cancel()
        ProximityWake.stopScan(this, proximityCallback)
        passiveLink.disarm() // hand PK off to the full session
        if (locked) {
            DebugLog.add("presence: proximity woke but watch locked — staying down")
            updateNotification(LOCKED_TEXT)
            return
        }
        startActivePresence()
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
            // IMPORTANCE_MIN: with the OngoingActivity attached (see buildNotification), the status
            // shows as a key icon on the watch face rather than a persistent notification card.
            NotificationChannel(CHANNEL_ID, getString(R.string.presence_channel_name), NotificationManager.IMPORTANCE_MIN),
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
        val offAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_power, "Disable", offIntent,
        ).build()
        // The MODE in the title (active / passive / paused) matches the app's key-status EXACTLY;
        // sub-states (driving-doze, watch standby, link count) are detail in the content text, so the
        // title and the app never contradict each other.
        val mode = when {
            locked -> "paused"
            passive -> "passive"
            else -> "active"
        }
        // NotificationCompat (not the platform Notification.Builder) because OngoingActivity.Builder
        // decorates a NotificationCompat.Builder.
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.presence_notification_title)} · $mode")
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // updates frequently — never buzz/re-alert
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(offAction)
        // Promote the ongoing status onto the watch face as a key icon (Wear OS Ongoing Activity).
        // This decorates the FGS notification in place, so the glanceable surface is the icon — not a
        // persistent card. Tapping it opens the app (same target as the notification's content intent).
        // CATEGORY_SERVICE ranks the key BELOW active user tasks (call / navigation / workout / media):
        // the watch face shows the highest-ranked ongoing activity, so with a low category the key
        // yields that slot when something more important is running and only surfaces when nothing is.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_tile_key)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setTouchIntent(contentIntent)
            .setStatus(Status.Builder().addTemplate("$mode · $state").build())
            .build()
            .apply(applicationContext)
        return builder.build()
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
        _parkedNearby.value = false
        // A real stop (user deactivated the key, or system kill) — stop the proximity watch.
        ProximityWake.stopScan(this, proximityCallback)
        passiveLink.disarm()
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
        /** No link UP for this long → drop to passive (stay-alive + offloaded proximity watch). This
         *  is now only the BACKSTOP; [departed] usually drops to passive far sooner via [IDLE_PROBE_MS]. */
        private const val IDLE_TIMEOUT_MS = 5 * 60_000L
        /** After the links drop, how long the offloaded scan looks for the car's nodes before
         *  concluding they're out of range and going passive. ~6x faster than the IDLE_TIMEOUT_MS
         *  backstop; long enough for the low-power scan to spot a nearby car (avoids false departures). */
        private const val IDLE_PROBE_MS = 45_000L
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
        private const val PARKED_TEXT = "Parked nearby"
        private const val DRIVING_TEXT = "In gear · presence paused"
        private const val WATCH_DOZE_TEXT = "Standby · open a door to drive"
        // Watch-mode burst presence: monitor re-eval cadence, and how long a burst holds after the
        // last trigger (door / wake / manual). 3 min covers a slow get-in with margin; tunable.
        private const val WATCH_TICK_MS = 1_000L
        private const val WATCH_BURST_MS = 3 * 60_000L

        /** Live "is the presence service running" — observed by the UI for the active/passive flip. */
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        val isRunning: Boolean get() = _running.value

        /** Live "is the service idling passively" (running, BLE down, scanning for approach). */
        private val _passive = MutableStateFlow(false)
        val passive: StateFlow<Boolean> = _passive

        /** Sub-state of [passive]: idling but the car is still in range ("parked nearby"), as opposed
         *  to departed / waiting-for-return. Drives the "Parked nearby" label and the unlock-reactivates
         *  policy — an explicit unlock while parked nearby means "I'm using the car" → wake to active. */
        private val _parkedNearby = MutableStateFlow(false)
        val parkedNearby: StateFlow<Boolean> = _parkedNearby

        const val ACTION_GO_PASSIVE = "org.fivesevenfive.wearvian.GO_PASSIVE"

        /** Marks a start as an app-upgrade restart (from [UpgradeReceiver]) — the one case where the
         *  persisted passive-idle state is trusted and resumed (see onStartCommand / SettingsStore). */
        const val ACTION_UPGRADE_RESTORE = "org.fivesevenfive.wearvian.UPGRADE_RESTORE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }

        /** Re-arm after an app upgrade, resuming the prior mode (active, or persisted passive idle). */
        fun startAfterUpgrade(context: Context) {
            context.startForegroundService(
                Intent(context, PresenceService::class.java).setAction(ACTION_UPGRADE_RESTORE),
            )
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
