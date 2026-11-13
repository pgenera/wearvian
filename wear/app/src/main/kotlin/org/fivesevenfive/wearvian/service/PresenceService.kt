package org.fivesevenfive.wearvian.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.WearvianApp
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.ActiveCommandManager
import org.fivesevenfive.wearvian.ble.CommandBus
import org.fivesevenfive.wearvian.ble.PassiveAutoConnect
import org.fivesevenfive.wearvian.ble.PhoneKey
import org.fivesevenfive.wearvian.ble.ReconnectHurry
import org.fivesevenfive.wearvian.ble.RescanSchedule
import org.fivesevenfive.wearvian.ble.SensorSeed
import org.fivesevenfive.wearvian.ble.SessionLabels
import org.fivesevenfive.wearvian.ble.ProximityWake
import org.fivesevenfive.wearvian.tile.TileRefresher
import org.fivesevenfive.wearvian.ble.RivianBle
import org.fivesevenfive.wearvian.ble.SensorScanner
import org.fivesevenfive.wearvian.ble.VehicleSession
import org.fivesevenfive.wearvian.complication.ComplicationRefresher
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.crypto.SharedSecretCache
import org.fivesevenfive.wearvian.crypto.WatchLockedException
import org.fivesevenfive.wearvian.store.DebugOverrides
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.store.TelemetryStore
import org.fivesevenfive.wearvian.store.logPersistentState
import org.fivesevenfive.wearvian.store.VehicleAddressStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.BlePermissions
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw

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
    // The one gap this leaves — a transition suspended in stopBle's cancelAndJoin — is closed by
    // the [transition] mutex.
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
    private var bringUpJob: Job? = null // bounded wake lock while sessions come up without the main one
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /**
     * Serializes the mode transitions (enter/exit passive, lock/unlock, departure confirmation).
     * The single-thread [scope] makes each synchronous step atomic, but [stopBle] suspends in
     * cancelAndJoin — without this lock another transition could run in that gap (e.g. a FIRST_MATCH
     * exitPassive in the middle of enterPassive's teardown) and leave the modes inconsistent.
     */
    private val transition = Mutex()

    /** Hold-off for scan wakes that keep failing to authenticate (see [FruitlessWakePolicy]). */
    private val fruitlessWake = FruitlessWakePolicy()
    /** This active stretch began with a wake from passive; and whether any session authenticated in it. */
    private var wokeFromPassive = false
    private var activeAuthenticated = false

    /** Which of the car's nodes the passive proximity scan currently sees (departure = all lost). */
    private val proximity = ProximityTracker(DEPART_DEBOUNCE_MS, STRANDED_LOSS_MS)

    /** True while the Bluetooth adapter is off: BLE is torn down and rebuilt on STATE_ON. */
    @Volatile private var btOff = false

    /** True while active presence can't find the phone-key module (not paired). */
    @Volatile private var phoneKeyMissing = false

    /** Consecutive proximity-scan failures in this passive stretch (the retry: [scheduleScanRetry]). */
    private var proximityScanFailures = 0

    /**
     * Airplane mode / a Bluetooth toggle kills every scan, pending autoConnect and GATT link without
     * telling the callers. Tear down on OFF and rebuild the current mode on ON ([onBluetoothState]).
     */
    /**
     * Screen on/off and user-present: re-read the keyguard at once ([syncLockState]) instead of
     * waiting for the 2 s poll, which stalls while the CPU sleeps (no wake lock is held while
     * locked or passive). Under a short timed wake lock so the transition runs before the CPU
     * suspends again — an unlock then restores BLE immediately.
     */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (lockJob?.isActive != true) return // not started (or stopping): nothing to sync
            scope.launch { withTimedWakeLock("wearvian:lock-sync", LOCK_SYNC_WAKE_MS) { syncLockState() } }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            scope.launch { onBluetoothState(state) }
        }
    }

    /**
     * True while idling passively: the service + foreground notification STAY up, the wake lock
     * is released and the BLE sessions are torn down, and a single hardware-offloaded scan watches
     * for the vehicle. Because we never leave the foreground, [proximityCallback] can rebuild BLE
     * on approach without a background FGS-start (which Android-12 blocks) — and without CDM, which
     * Wear OS forbids 3p apps from using.
     */
    @Volatile private var passive = false

    /** True while the watch is locked (off wrist): all BLE is torn down (anti-theft). Backed by a
     *  flow so the monitors can suspend until the watch is unlocked ([awaitActiveMode]). */
    private val lockedState = MutableStateFlow(false)
    private var locked: Boolean
        get() = lockedState.value
        set(value) { lockedState.value = value }

    /** Snapshot of [passive] taken at lock, so unlock restores the same mode instead of forcing active. */
    @Volatile private var wasPassive = false

    /**
     * Passive sub-state ([seenDeparture]) taken at lock with [wasPassive], so the unlock restores
     * "parked nearby" rather than "departed" (which would read the first sighting of a car that
     * never left as a return and wake the key). Null: not known, recompute on entry. If the car did
     * leave while locked, the parked-nearby arm window ([armProximityScan]) confirms the departure.
     */
    @Volatile private var lockedSeenDeparture: Boolean? = null

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
     * In-process proximity watch (passive mode). FIRST_MATCH = a car node is in range; MATCH_LOST =
     * that node's advertisement is no longer seen. Results are per node, so [proximity] tracks the
     * set currently seen and only ALL of them going quiet counts toward a departure. We only wake on
     * FIRST_MATCH once the car has left ([seenDeparture], or the set has been empty for
     * [DEPART_DEBOUNCE_MS] by the wall-ish elapsedRealtime clock) so a brief dropout while parked
     * nearby doesn't count as leaving. Handling hops onto [scope] (single-thread lane).
     */
    private val proximityCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val address = result.device?.address ?: "?"
            val rssi = result.rssi
            val now = SystemClock.elapsedRealtime() // stamped here: counts time the CPU was asleep
            val lost = callbackType == android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST
            scope.launch { if (lost) onProximityLost(address, now) else onProximityFound(address, rssi, now) }
        }
        override fun onScanFailed(errorCode: Int) {
            scope.launch { onProximityScanFailed(errorCode) }
        }
    }

    /**
     * The passive proximity scan was rejected (rate limit, no filter slots…) — without handling this
     * the key would sit passive with no scan running. Keep a wake path armed (the autoConnect if the
     * car has left; otherwise start the departure countdown, so it gets armed — until the retry
     * runs the scan is no wake path at all), retry the scan with backoff, and surface a persistent
     * failure in the notification.
     *
     * The retry is an AlarmManager ELAPSED_REALTIME_WAKEUP alarm, not a coroutine delay: passive
     * holds no wake lock and a coroutine timer stalls while the CPU sleeps, and a timed wake lock
     * would be too costly for a wait of up to 5 minutes.
     */
    private fun onProximityScanFailed(errorCode: Int) {
        if (!passive || btOff) return // BT off: the STATE_ON handler re-arms everything
        if (errorCode == android.bluetooth.le.ScanCallback.SCAN_FAILED_ALREADY_STARTED) return // it IS running
        proximityScanFailures++
        DebugLog.add("presence: proximity scan failed err=$errorCode (x$proximityScanFailures) — retrying")
        if (seenDeparture) {
            passiveLink.arm(this) // the other wake path works without the scan
        } else if (departJob?.isActive != true) {
            // Parked nearby, and the scan can't tell us when the car leaves: count from now.
            proximity.assumeAllLost(SystemClock.elapsedRealtime())
            startDepartureCountdown()
        }
        if (proximityScanFailures == SCAN_FAIL_NOTIFY_AFTER) updateNotification(passiveText())
        if (scanRetryPending) return
        scheduleScanRetry(ScanRetryPolicy.delayMs(proximityScanFailures))
    }

    /** True while a scan-retry alarm is set; touched only on [scope]. */
    private var scanRetryPending = false

    private val scanRetryListener = AlarmManager.OnAlarmListener {
        // Delivered on the main thread under the alarm's own brief wake lock; hold a short timed
        // one so the retry runs on scope before the CPU suspends again.
        scope.launch {
            withTimedWakeLock("wearvian:scan-retry", SCAN_RETRY_WAKE_MS) {
                transition.withLock {
                    // No longer pending, so a synchronous failure of this attempt
                    // (armProximityScan → onProximityScanFailed) schedules the next one.
                    scanRetryPending = false
                    if (passive && !locked && !btOff) armProximityScan()
                }
            }
        }
    }

    private fun scheduleScanRetry(waitMs: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + waitMs,
                "wearvian:scan-retry", scanRetryListener, null,
            )
            scanRetryPending = true
        }.onFailure { DebugLog.add("presence: scan retry alarm not set — ${it.javaClass.simpleName}") }
    }

    private fun cancelScanRetry() {
        if (!scanRetryPending) return
        scanRetryPending = false
        runCatching { getSystemService(AlarmManager::class.java)?.cancel(scanRetryListener) }
    }

    /**
     * (Re)start the passive proximity scan. A synchronous start failure counts as a scan failure.
     *
     * While parked nearby (`!seenDeparture`) departure is otherwise learned only from a MATCH_LOST
     * of a node this scan reported — if it never reports the car before the wearer walks off, the
     * departure is never confirmed and the autoConnect never armed. So, as the Bluetooth-on rebuild
     * always did, treat every node as lost now and start the departure countdown: the fresh scan's
     * first sighting within [parkedWindowMs] cancels it ("still here"); silence confirms the
     * departure and arms the autoConnect. Trade-off: a car present but invisible to the scan wakes
     * the key to active once (battery, not reliability) — hence a longer first window by default.
     */
    private fun armProximityScan(parkedWindowMs: Long = PARKED_ARM_WINDOW_MS): Boolean {
        val enrollment = EnrollmentStore(this).load() ?: return false
        ProximityWake.stopScan(this, proximityCallback)
        val now = SystemClock.elapsedRealtime()
        proximity.reset(now) // the new scan re-reports every node in range
        if (!seenDeparture) {
            proximity.assumeAllLost(now, parkedWindowMs)
            startDepartureCountdown(parkedWindowMs)
        }
        val ok = ProximityWake.scanForVehicle(this, enrollment.vasVehicleId, proximityCallback)
        if (!ok) onProximityScanFailed(SCAN_START_FAILED)
        return ok
    }

    /** Passive notification text for the current sub-state. */
    private fun passiveText(): String = if (seenDeparture) PASSIVE_TEXT else PARKED_TEXT

    /** Bluetooth adapter turned off/on: tear down, then rebuild whichever mode we are in. */
    private suspend fun onBluetoothState(state: Int) = transition.withLock {
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                if (btOff) return@withLock
                btOff = true
                DebugLog.add("presence: Bluetooth off — tearing down BLE until it returns")
                cancelScanRetry()
                if (passive) {
                    ProximityWake.stopScan(this, proximityCallback)
                    passiveLink.disarm()
                    departJob?.cancel()
                    proximity.reset()
                } else if (!locked) {
                    driving = false; VehicleSession.heartbeatsPaused = watchMode // doze default for a watch key
                    stopBle()
                    VehicleStatus.clear()
                    releaseWakeLock()
                }
                updateNotification(BT_OFF_TEXT)
            }
            BluetoothAdapter.STATE_ON -> {
                if (!btOff) return@withLock
                btOff = false
                // A fresh keyguard read: if it disagrees, the lock/unlock transition itself rebuilds
                // the right mode (Bluetooth is back on), so there is nothing more to do here.
                if (syncLockStateLocked()) return@withLock
                DebugLog.add("presence: Bluetooth on — rebuilding (${if (locked) "locked" else if (passive) "passive" else "active"})")
                when {
                    locked -> updateNotification(LOCKED_TEXT) // unlock rebuilds
                    passive -> {
                        proximityScanFailures = 0
                        // Parked nearby: the car may have left while we couldn't see it — the arm
                        // counts from now (the plain debounce, as before) and the fresh scan's
                        // FIRST_MATCH cancels the countdown if it's still here.
                        armProximityScan(parkedWindowMs = DEPART_DEBOUNCE_MS)
                        if (seenDeparture) passiveLink.arm(this)
                        updateNotification(passiveText())
                    }
                    else -> { stopBle(); startActivePresence() }
                }
            }
        }
    }

    private fun onProximityLost(address: String, now: Long) {
        if (!passive) return
        if (!proximity.lost(address, now)) {
            DebugLog.add("presence: proximity lost $address (${proximity.size} node(s) still seen) → still in range")
            return
        }
        if (seenDeparture || departJob?.isActive == true) return
        DebugLog.add("presence: proximity lost $address (no nodes seen) → arming return watch")
        startDepartureCountdown()
    }

    /** After [windowMs] with no node seen, confirm the departure ([markDeparted]). */
    private fun startDepartureCountdown(windowMs: Long = DEPART_DEBOUNCE_MS) {
        if (departJob?.isActive == true) return
        departJob = scope.launch {
            // Coroutine delays don't advance while the CPU is suspended, and passive mode has no
            // wake lock — so hold a short self-expiring one for the debounce, or the autoConnect
            // below might not be armed until long after the car has gone.
            withTimedWakeLock("wearvian:depart", windowMs + TIMED_WAKE_SLACK_MS) { delay(windowMs) }
            transition.withLock { markDeparted() }
        }
    }

    private suspend fun onProximityFound(address: String, rssi: Int, now: Long) {
        if (!passive) return
        proximityScanFailures = 0 // the scan is evidently working
        departJob?.cancel() // a node is (back) in range — cancel any pending "it left"
        when (proximity.found(address, now, seenDeparture)) {
            ProximityTracker.Found.RETURN -> {
                if (!fruitlessWake.sightingWakes(now, CommandBus.lastCommandMs)) {
                    DebugLog.add("presence: proximity return $address — held off (${fruitlessWake.fruitless} wake(s) never authenticated); autoConnect + taps still work")
                    return
                }
                DebugLog.add("presence: proximity return $address rssi=$rssi → wake")
                exitPassive()
            }
            ProximityTracker.Found.PRESENT ->
                DebugLog.add("presence: proximity hit $address (${proximity.size} node(s) seen, parked nearby) → ignore")
        }
    }

    /** The car has been out of range for the debounce: a return now wakes (scan + PK autoConnect). */
    private suspend fun markDeparted() {
        syncLockStateLocked() // don't trust the polled field: a lock tears passive down instead
        if (!passive || seenDeparture || locked) return
        seenDeparture = true
        settings.passiveSeenDeparture = true // keep the persisted sub-state current
        _parkedNearby.value = false // no longer parked beside it — now waiting for return
        updateNotification(PASSIVE_TEXT) // relabel from "Parked nearby" → "Waiting for vehicle"
        // Car has actually left → now arm the autoConnect so its RETURN reconnects PK directly. We
        // don't arm it while parked nearby, or it would immediately reconnect and bounce us out.
        passiveLink.arm(this@PresenceService)
        DebugLog.add("presence: car gone ${DEPART_DEBOUNCE_MS / 1000}s → a return now wakes (scan + PK autoConnect)")
    }

    /**
     * Run [block] under a self-expiring partial wake lock — for the short, bounded windows (departure
     * debounce, idle probe) whose timers must keep counting while no main wake lock is held.
     */
    private suspend fun <T> withTimedWakeLock(tag: String, timeoutMs: Long, block: suspend () -> T): T {
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false); acquire(timeoutMs) }
        try {
            return block()
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
        }
    }

    /** Suspend until the service is neither passive nor locked (i.e. active presence is up). */
    private suspend fun awaitActiveMode() {
        combine(_passive, lockedState) { p, l -> !p && !l }.first { it }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        btOff = adapter?.isEnabled != true
        // A protected system broadcast: delivered to a NOT_EXPORTED receiver.
        runCatching { registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED) }
            .onFailure { DebugLog.add("presence: BT state receiver not registered — ${it.javaClass.simpleName}") }
        val lockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(lockReceiver, lockFilter, RECEIVER_NOT_EXPORTED) }
            .onFailure { DebugLog.add("presence: lock receiver not registered — ${it.javaClass.simpleName}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val goPassiveNow = intent?.action == ACTION_GO_PASSIVE
        logi("PresenceService: onStartCommand action=${intent?.action}")
        // intent == null is the system re-creating this START_STICKY service after killing the process.
        // Gate going foreground first: without Bluetooth permission (revoked, auto-reset) startForeground
        // throws on Android 14+, and a sticky restart of a key the user turned off shouldn't come back.
        // Not proceeding returns START_NOT_STICKY so the system doesn't retry; the key re-arms from
        // keyArmed the next time the app opens (SetupViewModel.refresh).
        val gate = ServiceStartGate.check(
            isSystemRestart = intent == null,
            keyArmed = settings.keyArmed,
            hasBlePermissions = BlePermissions.granted(this),
        )
        if (gate != ServiceStartGate.Result.PROCEED) {
            DebugLog.add("presence: not starting (${gate.name}); stopping")
            if (lockJob?.isActive != true) { stopSelf(); return START_NOT_STICKY }
            // Already running (permission revoked mid-run already kills the process, so this is a
            // rare race): leave the running instance alone.
            return START_STICKY
        }
        if (!startAsForeground()) {
            // Background FGS-start restriction (a sticky restart while the app is in the background on
            // Android 12+) or a permission race. Bail out instead of crashing into a restart loop, and
            // tell the user: nothing restarts the key until the app is opened.
            if (lockJob?.isActive != true) { notifyKeyStopped(); stopSelf(); return START_NOT_STICKY }
            return START_STICKY
        }
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
                goPassiveNow -> { DebugLog.add("presence: manual passive (already running)"); scope.launch { manualPassive() } }
                passive -> { DebugLog.add("presence: start while passive → wake"); scope.launch { exitPassive() } }
                else -> DebugLog.add("presence: already running; ignoring duplicate start")
            }
            return START_STICKY
        }
        _running.value = true
        // In-process wake for callers in this process (a tile unlock while parked nearby), so they
        // don't need a foreground-service start, which can be refused from the background.
        wakeHook = { scope.launch { exitPassive() } }
        // Anti-theft: don't bring BLE up while the watch is locked (off wrist); monitorLock
        // restores it on unlock. Otherwise go straight to passive (manual button) or active.
        // Transitions launch onto `scope` (single-thread lane) so they serialize with the monitors.
        // On an app upgrade (ACTION_UPGRADE_RESTORE — a near-instant process swap) resume the persisted
        // passive idle so a parked-nearby key doesn't snap back to active; any other start ignores it.
        // The mode decision itself is the pure, unit-tested [StartupPolicy]; this branch just enacts it.
        val trigger = when (intent?.action) {
            ACTION_UPGRADE_RESTORE -> StartTrigger.UPGRADE_RESTORE
            ACTION_GO_PASSIVE -> StartTrigger.GO_PASSIVE
            else -> StartTrigger.NORMAL
        }
        locked = keyguard?.isDeviceLocked == true
        when (val decision = StartupPolicy.resolve(trigger, locked, settings.passiveIdle, settings.passiveSeenDeparture)) {
            is StartupDecision.Locked -> {
                if (decision.wasPassive) {
                    wasPassive = true
                    // An upgrade restore resumes the persisted sub-state; a manual go-passive has none.
                    lockedSeenDeparture = if (trigger == StartTrigger.UPGRADE_RESTORE) settings.passiveSeenDeparture else null
                }
                updateNotification(LOCKED_TEXT)
            }
            StartupDecision.ManualPassive -> { DebugLog.add("presence: manual passive requested"); scope.launch { manualPassive() } }
            is StartupDecision.Passive -> {
                DebugLog.add("presence: upgrade — resuming passive idle (seenDeparture=${decision.restoreSeenDeparture})")
                scope.launch { enterPassive(restoreSeenDeparture = decision.restoreSeenDeparture) }
            }
            StartupDecision.Active -> scope.launch { startActivePresence() }
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
        // Any authenticated session clears the fruitless-wake hold-off.
        scope.launch {
            PresenceStatus.connected.collect { up ->
                if (up && !passive) { activeAuthenticated = true; fruitlessWake.authenticated() }
            }
        }
        // Standby / driving-doze hold no wake lock: cover a dropped link's reconnect with a bounded one.
        scope.launch { monitorPrimaryDrop() }
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
        // Detach first: cancelAndJoin suspends, and a loop started meanwhile must not be nulled out.
        val job = loopJob
        loopJob = null
        job?.cancelAndJoin()
        // No session will drain them now (passive / locked / Bluetooth off / rebuild).
        CommandBus.primaryUp(false)
        CommandBus.clear()
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
        if (btOff) {
            // No radio: holding the wake lock would buy nothing. STATE_ON calls us again.
            DebugLog.add("presence: active, but Bluetooth is off — waiting for it")
            updateNotification(BT_OFF_TEXT)
            return
        }
        if (watchMode) {
            VehicleSession.heartbeatsPaused = true // doze by default; bursts un-pause it
            // No main wake lock in standby: cover the ECDH + connect + handshake with a bounded one.
            if (BringUpWakePolicy.onSessionsStart(wakeLock != null)) holdBringUpWakeLock("watch-key bring-up")
            startBle()
        } else {
            acquireWakeLock()
            startBle()
        }
        updateNotification(activeNotificationText())
    }

    /**
     * Hold a timed wake lock until the PRIMARY link is UP, at most [BringUpWakePolicy.MAX_MS]
     * (see [BringUpWakePolicy]). Self-expiring: it can never be held indefinitely.
     */
    private fun holdBringUpWakeLock(why: String) {
        if (bringUpJob?.isActive == true) return
        DebugLog.add("presence: bring-up wake lock ($why) — until PK is up, ≤${BringUpWakePolicy.MAX_MS / 1000}s")
        bringUpJob = scope.launch {
            withTimedWakeLock("wearvian:bring-up", BringUpWakePolicy.MAX_MS + TIMED_WAKE_SLACK_MS) {
                withTimeoutOrNull(BringUpWakePolicy.MAX_MS) { PresenceStatus.primaryLink.first { BringUpWakePolicy.done(it) } }
            }
        }
    }

    /** Watch standby / driving-doze: a dropped PRIMARY link takes the bring-up wake lock. */
    private suspend fun monitorPrimaryDrop() {
        var prev: PresenceStatus.Link? = null
        PresenceStatus.primaryLink.collect { now ->
            val active = !passive && !locked && !btOff
            if (BringUpWakePolicy.onPrimaryChange(prev, now, wakeLock != null, active, VehicleSession.heartbeatsPaused)) {
                holdBringUpWakeLock("PK dropped while heartbeats paused")
            }
            prev = now
        }
    }

    /** The active-mode notification text: a sub-state's fixed text (watch standby / driving-doze),
     *  else the live link summary. */
    private fun activeNotificationText(): String = when {
        watchMode && VehicleSession.heartbeatsPaused -> WATCH_DOZE_TEXT
        driving -> DRIVING_TEXT
        else -> PresenceStatus.summary.value
    }

    /**
     * Anti-theft: while the watch is locked (removed from the wrist with a screen lock),
     * tear down ALL BLE — no connections, no chatter, no wake lock — keeping the foreground
     * service alive so we can rebuild instantly on unlock without an FGS-restart (which the
     * Android-12 background limit can block). There's no reliable "device locked" broadcast, so
     * the state is synced ([syncLockState]) on the screen / user-present broadcasts ([lockReceiver])
     * and at the top of every transition that depends on it; this 2 s poll is only the backstop.
     */
    private suspend fun monitorLock() {
        while (true) {
            if (keyguardLocked() != locked) syncLockState()
            delay(LOCK_POLL_MS)
        }
    }

    private fun keyguardLocked(): Boolean = keyguard?.isDeviceLocked == true

    private suspend fun syncLockState(): Boolean = transition.withLock { syncLockStateLocked() }

    /**
     * Read the keyguard FRESH and, if it disagrees with [locked], run the lock / unlock transition
     * ([LockSyncPolicy]). Caller holds [transition]. @return true if a transition ran.
     */
    private suspend fun syncLockStateLocked(): Boolean {
        val action = LockSyncPolicy.decide(keyguardLocked(), locked, wasPassive)
        if (action == LockSyncPolicy.Action.NONE) return false
        locked = action == LockSyncPolicy.Action.LOCK
        when (action) {
            LockSyncPolicy.Action.NONE -> Unit
            // NonCancellable: this can run inside the very job it cancels (a departure countdown
            // reading the keyguard in markDeparted), and the anti-theft teardown must finish.
            LockSyncPolicy.Action.LOCK -> withContext(NonCancellable) {
                DebugLog.add("presence: watch locked — tearing down BLE (anti-theft)")
                wasPassive = passive // remember the mode so unlock restores it, not a forced active probe
                lockedSeenDeparture = if (passive) seenDeparture else null
                if (passive) { ProximityWake.stopScan(this@PresenceService, proximityCallback); passiveLink.disarm(); departJob?.cancel(); cancelScanRetry(); proximity.reset(); passive = false; _passive.value = false; _parkedNearby.value = false }
                driving = false; VehicleSession.heartbeatsPaused = false // locked overrides driving-doze
                bringUpJob?.cancel() // nothing to bring up while locked
                stopBle()
                // The ECDH secret must not outlive the lock: the Keystore key itself
                // (unlockedDeviceRequired) can't be used locked, and neither should a copy.
                SharedSecretCache.clear()
                releaseWakeLock()
                updateNotification(LOCKED_TEXT)
            }
            LockSyncPolicy.Action.UNLOCK_TO_PASSIVE -> {
                // Unlocked: recompute the secret (cleared on lock) now, so the StrongBox ECDH
                // overlaps the reconnect instead of delaying the first handshake.
                WearvianApp.prefetchSecret(this)
                // Were idling passively (car out of range) → come back up passive: re-arm the proximity
                // scan, no wake lock. If the car arrived while off-wrist, FIRST_MATCH promotes us to
                // active within seconds. Avoids re-paying a full ~45s active probe on every unlock.
                DebugLog.add("presence: watch unlocked — restoring passive idle")
                enterPassiveLocked(restoreSeenDeparture = lockedSeenDeparture) // sets its own passive notification text
            }
            LockSyncPolicy.Action.UNLOCK_TO_ACTIVE -> {
                WearvianApp.prefetchSecret(this)
                DebugLog.add("presence: watch unlocked — restoring active presence")
                startActivePresence() // sets the active notification text
            }
        }
        return true
    }

    /**
     * After [IDLE_TIMEOUT_MS] with no link UP (car out of range / asleep), drop to passive: keep
     * the service + notification up but release the wake lock, tear down BLE, and let a single
     * offloaded scan watch for the vehicle. While passive or locked we wait for active mode to
     * return — NOT for a link to come up: a wake that never connects (a brief FIRST_MATCH, a start
     * while away) must still be able to fall back to passive, or the wake lock is held forever.
     */
    private suspend fun monitorIdle() {
        while (true) {
            awaitActiveMode()
            // Wait until no link is UP. Reading the flow (not a single collectLatest pass)
            // also recovers from a stale `connected==true` carried over a process restart.
            PresenceStatus.connected.first { !it }
            if (passive || locked) continue // the links dropped because we left active mode
            // Disconnected — go passive as soon as the car's nodes are confirmed out of range (a quick
            // offloaded scan), instead of always holding active for IDLE_TIMEOUT_MS. A reconnect, or the
            // scan still seeing the nodes advertising, means it's a blip → stay active.
            val disconnectedAt = SystemClock.elapsedRealtime()
            if (!departed(disconnectedAt)) continue // reconnected, or car still in range → keep active
            goPassive()
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
        val scanFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                // A rejected probe sees nothing — that must read as "unknown", not "car gone".
                if (scanFailed.compareAndSet(false, true)) DebugLog.add("idle: probe scan failed err=$errorCode — using the backstop")
            }
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
            // Under a timed wake lock: a dozing watch-mode key holds none, and a coroutine timer
            // stalls while the CPU sleeps, which would stretch this probe indefinitely.
            withTimedWakeLock("wearvian:idle-probe", IDLE_PROBE_MS + TIMED_WAKE_SLACK_MS) {
                withTimeoutOrNull(IDLE_PROBE_MS) {
                    while (!seen.get() && !scanFailed.get() && !PresenceStatus.connected.value && !passive && !locked) delay(500)
                }
            }
            return when {
                passive || locked -> false
                PresenceStatus.connected.value -> false // reconnected on its own — blip
                !seen.get() && !scanFailed.get() -> { DebugLog.add("idle: car nodes out of range ${IDLE_PROBE_MS / 1000}s → passive"); true }
                else -> {
                    // Car's still advertising nearby (a blip) or the probe couldn't scan. Wait for the
                    // reconnect, capped by the IDLE_TIMEOUT_MS backstop from when the links first dropped.
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
     * door you open as you walk up keeps the key active. Being in gear (or in driving-doze) is
     * activity too ([ParkedIdlePolicy.eligible]): idling out mid-drive would tear down the 0x1c
     * stream that catches the return to Park and leave the key "Parked nearby" after the trip.
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
            // No confirmed state to judge from, or driving → not idle; restart the window.
            if (!ParkedIdlePolicy.eligible(s, driving)) continue
            val snap = ParkedIdlePolicy.snapshot(s)
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
                    if (!ParkedIdlePolicy.eligible(s2, driving) || ParkedIdlePolicy.snapshot(s2) != snap) break
                }
            }
            if (changed == null && !passive && !locked && ParkedIdlePolicy.eligible(VehicleStatus.state.value, driving)) {
                DebugLog.add("presence: state stable ${timeout / 60_000}m → passive (parked-idle)")
                enterPassive()
                awaitActiveMode() // block until reactivated (return / manual / unlock)
            }
        }
    }

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
     * Burst triggers (any, see [WatchBurstPolicy]): a door opens, the vehicle wakes (asleep→awake), the
     * status comes up live with the car awake, parked and open/unlocked (a session that came up late),
     * or a manual command — live-queue or one-shot (the heartbeat loop won't drain a queued command
     * while paused, and a one-shot unlock is the usual way back to the car). A burst
     * holds for [WATCH_BURST_MS] of inactivity, then drops to doze; it also drops the instant the car
     * reports it went back to sleep, and never runs while actually driving (gear ≠ Park — already
     * validated heartbeat-free via driving-doze). UNVALIDATED on-vehicle: whether the link survives
     * heartbeat-less while parked-present long enough to catch the door-open is the thing to test.
     *
     * Event-driven: each new 0x1c state is evaluated as it arrives (a status frame wakes the CPU even
     * in doze, when no wake lock is held and a timer tick would stall), with the tick only as the
     * re-evaluation cadence for burst expiry and manual commands. Only LIVE state counts — the stale
     * last-known state kept across passive/lock must neither trigger nor suppress a burst.
     */
    private suspend fun monitorWatchPresence() {
        if (!watchMode) return
        // Trigger edges + burst window (unit-tested); the manual signal is CommandBus.lastSubmitMs.
        val policy = WatchBurstPolicy(WATCH_BURST_MS)
        var lastState = VehicleStatus.state.value
        while (true) {
            withTimeoutOrNull(WATCH_TICK_MS) { VehicleStatus.state.first { it != lastState } }
            val st = VehicleStatus.state.value
            lastState = st
            if (locked || passive || btOff) { policy.reset(); continue }
            val fresh = st.valid && st.live
            val carAsleep = fresh && st.asleep
            val inGear = fresh && ParkedIdlePolicy.inGear(st.gear)
            // A MANUAL command must burst so the heartbeat loop can drain it — even if the car is asleep
            // or in gear (else a tapped lock/unlock is queued and never sent: the heartbeat loop won't
            // drain while paused). Passive triggers (door / wake / status-live) only burst while parked
            // & awake — the drive-enable window — and stop when the car sleeps or you shift into gear.
            val decision = policy.evaluate(
                fresh = fresh, doorOpen = st.anyDoorOpen, asleep = st.asleep, unlocked = !st.locked,
                inGear = inGear, lastManualMs = CommandBus.lastSubmitMs, nowMs = SystemClock.elapsedRealtime(),
            )
            decision.trigger?.let { DebugLog.add("watch: trigger = $it") }
            val wantBurst = decision.wantBurst
            // Drive off the real heartbeat flag (not a local) so this self-corrects after a lock/passive
            // reset flips it. In watch mode only this monitor un-pauses, so paused==false ⟺ bursting.
            if (wantBurst && VehicleSession.heartbeatsPaused) {
                acquireWakeLock()
                VehicleSession.heartbeatsPaused = false
                ReconnectHurry.hurry() // a session sitting out a backoff should join the burst now
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
    private suspend fun goPassive(): Boolean {
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
    private suspend fun enterPassive(restoreSeenDeparture: Boolean? = null): Boolean =
        transition.withLock { enterPassiveLocked(restoreSeenDeparture) }

    /**
     * The manual "go passive" button. Reads the keyguard fresh ([syncLockStateLocked]) first: on a
     * locked watch the key stays off until the unlock, so the request is ignored
     * ([LockSyncPolicy.acceptManualPassive]).
     */
    private suspend fun manualPassive(): Boolean = transition.withLock {
        syncLockStateLocked()
        if (!LockSyncPolicy.acceptManualPassive(keyguardLocked())) {
            DebugLog.add("presence: manual passive ignored — watch locked, key stays off")
            return@withLock false
        }
        enterPassiveLocked()
    }

    /** [enterPassive] body; caller holds [transition]. */
    private suspend fun enterPassiveLocked(restoreSeenDeparture: Boolean? = null): Boolean {
        if (passive) return true
        val enrollment = EnrollmentStore(this).load() ?: run { stopSelf(); return true }
        if (wokeFromPassive) {
            // A wake from passive is ending: count it if no session ever authenticated.
            fruitlessWake.wakeEnded(activeAuthenticated, SystemClock.elapsedRealtime())
            if (!activeAuthenticated) DebugLog.add("presence: wake never authenticated (x${fruitlessWake.fruitless}) — scan wakes held off ${FruitlessWakePolicy.holdOffMs(fruitlessWake.fruitless) / 60_000}m")
            wokeFromPassive = false
        }
        passive = true
        _passive.value = true
        departJob?.cancel()
        proximity.reset() // the new scan re-reports every node in range
        // If no link is up the car is already gone → watch for its return immediately. If one is up
        // (parked-idle, or the manual button while at the car) wait for a MATCH_LOST first, so the
        // ever-present car doesn't snap us straight back to active via FIRST_MATCH. On an upgrade
        // restore, use the PERSISTED sub-state instead — a fresh process has no link, which recompute
        // would read as "departed" and arm an autoConnect that bounces us active next to the car.
        // (Read before stopBle, which drops the links.)
        seenDeparture = restoreSeenDeparture ?: !PresenceStatus.connected.value
        _parkedNearby.value = !seenDeparture // in range → parked nearby; gone → waiting for return
        // Persist so a near-instant restart (app upgrade) can resume passive with the same sub-state.
        settings.passiveIdle = true
        settings.passiveSeenDeparture = seenDeparture
        driving = false; VehicleSession.heartbeatsPaused = false // leaving active — clear driving-doze
        // Tear the active sessions down FIRST (awaited), then arm the scan and — only if the car is
        // already gone — the autoConnect wake on PK. While parked nearby it's still advertising, so an
        // autoConnect would reconnect immediately and bounce us out of passive; in that case we wait
        // for the scan to lose every node and arm autoConnect then ([markDeparted]). Awaiting the
        // teardown means the old PK GATT is closed and we never briefly hold two clients to it.
        stopBle()
        // A tile pre-warm may hold a link to PK (the service can start straight into passive, with no
        // PRIMARY session to release it): drop it before arming the autoConnect, which would
        // otherwise complete at once over that link and wake us straight back to active.
        ActiveCommandManager.awaitOneShotIdle()
        VehicleStatus.clear() // no session confirming state in passive — keep it, mark stale (dimmed)
        releaseWakeLock()
        bringUpJob?.cancel() // no sessions in passive
        proximityScanFailures = 0
        cancelScanRetry()
        val watching = !btOff && armProximityScan() // BT off: STATE_ON arms the scan + autoConnect
        if (seenDeparture && !btOff) passiveLink.arm(this)
        DebugLog.add("presence: → passive (carPresent=${!seenDeparture}, service alive); proximity watch=$watching, autoConnect=${seenDeparture}")
        updateNotification(passiveText())
        TileRefresher.refresh(this) // active→passive: tile state changed
        return true
    }

    /** Proximity woke us (or unlock): leave passive — re-acquire the wake lock and rebuild BLE. */
    private suspend fun exitPassive() = transition.withLock { exitPassiveLocked() }

    /** [exitPassive] body; caller holds [transition]. */
    private suspend fun exitPassiveLocked() {
        // Read the keyguard fresh first: a watch taken off while passive still has locked == false
        // until the poll runs. If it is locked, the lock transition tears passive down (recording
        // wasPassive) and we stop here instead of taking the wake lock for a session that can't
        // authenticate.
        syncLockStateLocked()
        if (!passive) return
        passive = false
        _passive.value = false
        _parkedNearby.value = false
        departJob?.cancel()
        cancelScanRetry()
        proximity.reset()
        ProximityWake.stopScan(this, proximityCallback)
        passiveLink.disarm() // hand PK off to the full session
        if (locked) {
            DebugLog.add("presence: proximity woke but watch locked — staying down")
            updateNotification(LOCKED_TEXT)
            return
        }
        wokeFromPassive = true
        activeAuthenticated = PresenceStatus.connected.value
        startActivePresence() // sets the active notification text
        DebugLog.add("presence: proximity wake → active")
        TileRefresher.refresh(this) // passive→active: tile state changed
    }

    // Runs as [loopJob]. Sessions are launched as CHILDREN (structured concurrency) so
    // cancelling loopJob (e.g. on lock) tears down every session too.
    private suspend fun presenceLoop(enrollment: Enrollment) {
        DebugLog.add("presence: loop start for ${enrollment.vin}")
        val sharedSecret = awaitSharedSecret(enrollment)
        // coroutineScope returns only once every session (its children) has finished, so the copy
        // shared by all of them can be zeroed here (loop cancelled on lock / stop).
        try {
            sessionsLoop(enrollment, sharedSecret)
        } finally {
            sharedSecret.fill(0)
        }
    }

    private suspend fun sessionsLoop(enrollment: Enrollment, sharedSecret: ByteArray) = coroutineScope {
        val bleScope = this
        val started = HashSet<String>()
        val labels = HashSet<String>() // PresenceStatus keys: must be unique per session
        val addresses = VehicleAddressStore(this@PresenceService)
        val svc = RivianBle.vehicleServiceUuid(enrollment.vasVehicleId)
        if (svc == null) DebugLog.add("presence: bad vasVehicleId '${enrollment.vasVehicleId}'; no sensor sessions")
        var pkStarted = false
        var pkMissingLogged = false
        var rescanMs = RescanSchedule.MIN_MS
        var lastScanAt = 0L
        var wasPaused = false
        var seeded = false // sessions for the saved sensor addresses started (once per loop)

        while (bleScope.isActive) {
            // PRIMARY phone-key: looked up on every pass, not once — bondedDevices is empty while
            // Bluetooth is off or restarting, and a missed lookup would leave no PRIMARY (no 0x1c,
            // no command drain) for the whole loop.
            if (!pkStarted) {
                val pk = PhoneKey.find(this@PresenceService, adapter)
                if (pk != null) {
                    pkStarted = true
                    started.add(pk.address)
                    labels.add(PresenceStatus.PRIMARY_LABEL)
                    addresses.add(pk.address) // remember for the proximity-wake filters
                    setPhoneKeyMissing(false)
                    bleScope.launch { VehicleSession(this@PresenceService, pk, enrollment, sharedSecret, PresenceStatus.PRIMARY_LABEL, primary = true).runForever(bleScope) }
                } else if (adapter?.isEnabled == true) {
                    if (!pkMissingLogged) { pkMissingLogged = true; DebugLog.add("presence: phone key not paired — retrying") }
                    setPhoneKeyMissing(true)
                }
            }

            // Location sensors: scan by the vehicle's VAS id and open a session per new device. Not
            // while heartbeats are paused (driving-doze / watch standby — sensors only carry
            // heartbeats), and backing off once a scan turns up nothing new.
            val paused = VehicleSession.heartbeatsPaused
            val now = SystemClock.elapsedRealtime()
            val resumed = wasPaused && !paused
            wasPaused = paused
            // Sensors already known from earlier sessions: start them now rather than after a 6 s
            // scan re-finds them (the scan below still looks for new ones). Only once the PRIMARY is
            // running, so its address is in `started` and can never be opened as a sensor.
            if (svc != null && !paused && pkStarted && !seeded) {
                seeded = true
                val seeds = SensorSeed.toStart(addresses.load(), PhoneKey.storedAddress(this@PresenceService), started)
                seeds.forEach { started.add(it) }
                if (seeds.isNotEmpty()) {
                    DebugLog.add("presence: starting ${seeds.size} saved sensor session(s)")
                    // Alongside the discovery scan, staggered as before (one connect at a time).
                    bleScope.launch {
                        seeds.forEachIndexed { i, address ->
                            if (i > 0) delay(STAGGER_MS)
                            val label = SessionLabels.unique(null, address, labels)
                            DebugLog.ble("·", label, "$address (saved) → session")
                            val dev = adapter.getRemoteDevice(address)
                            bleScope.launch { VehicleSession(this@PresenceService, dev, enrollment, sharedSecret, label, primary = false).runForever(bleScope) }
                        }
                    }
                }
            }
            if (svc != null && !paused && (resumed || lastScanAt == 0L || now - lastScanAt >= rescanMs)) {
                lastScanAt = now
                val foundNew = runCatching {
                    var launched = 0
                    val hits = SensorScanner(this@PresenceService).discover(svc)
                    val pkAddress = PhoneKey.storedAddress(this@PresenceService)
                    // Connect strongest-RSSI (closest) sensors first — they matter most for
                    // inside-cabin localization and are the most reliable to bring up.
                    hits.sortedByDescending { it.rssi }.forEach { hit ->
                        // The phone key runs as the PRIMARY session above, never as a sensor.
                        if (hit.address.equals(pkAddress, ignoreCase = true) || hit.name == RivianBle.DEVICE_NAME) return@forEach
                        if (started.add(hit.address)) {
                            launched++
                            addresses.add(hit.address) // remember for the proximity-wake filters
                            val label = SessionLabels.unique(hit.name, hit.address, labels)
                            DebugLog.ble("·", label, "${hit.name ?: "?"} ${hit.address} rssi=${hit.rssi} → session")
                            val dev = adapter.getRemoteDevice(hit.address)
                            bleScope.launch { VehicleSession(this@PresenceService, dev, enrollment, sharedSecret, label, primary = false).runForever(bleScope) }
                            // Stagger connects — Android BLE can't reliably do several
                            // concurrent connect/discover attempts at once.
                            delay(STAGGER_MS)
                        }
                    }
                    launched > 0
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    DebugLog.add("presence: sensor scan failed — ${it.message}")
                    null
                }
                rescanMs = RescanSchedule.next(rescanMs, foundNew)
            }
            // A watch-key burst starting (or a tapped command) un-pauses heartbeats: run the next
            // pass at once instead of after the tick, so sensor sessions come up for the burst.
            ReconnectHurry.delayUnlessHurried(LOOP_TICK_MS)
        }
    }

    /** Phone-key lookup result, surfaced in the notification ("not paired") while active. */
    private fun setPhoneKeyMissing(missing: Boolean) {
        if (phoneKeyMissing == missing) return
        phoneKeyMissing = missing
        if (!passive && !locked) updateNotification(activeNotificationText())
    }

    /**
     * ECDH with the vehicle key, retried with backoff instead of giving up: a transient Keystore
     * failure (e.g. racing the unlock that just started us — the key is unlockedDeviceRequired) must
     * not end the loop with nothing retrying. While this waits no link comes up, so [monitorIdle]
     * still falls back to passive if it never succeeds. Cancelled with the loop.
     *
     * A locked watch (the cache reads the keyguard fresh, whatever our polled [locked] says) is not
     * a failure: wait for the unlock on a short fixed poll instead of the backoff, so a stray locked
     * reading can't delay the reconnect by a minute. The wait takes no wake lock: in passive / watch
     * doze none is held, and where one is (phone key, active) [monitorLock] sees the lock on its next
     * 2 s poll and tears down BLE (cancelling this) and releases it.
     */
    private suspend fun awaitSharedSecret(enrollment: Enrollment): ByteArray {
        var backoff = ECDH_RETRY_MIN_MS
        var lockedLogged = false
        while (true) {
            val result = runCatching { SharedSecretCache.get(keyManager, enrollment.vehiclePublicKey) }
            result.onSuccess { return it }
            if (result.exceptionOrNull() is WatchLockedException) {
                if (!lockedLogged) { lockedLogged = true; DebugLog.add("presence: secret withheld — watch locked; waiting for unlock") }
                delay(LOCKED_SECRET_POLL_MS)
                continue
            }
            DebugLog.add("presence: ECDH failed (${result.exceptionOrNull()?.javaClass?.simpleName}) — retry in ${backoff / 1000}s")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(ECDH_RETRY_MAX_MS)
        }
    }

    /** @return false if the system refused the foreground start (logged; the caller stops). */
    private fun startAsForeground(): Boolean = try {
        startAsForegroundOrThrow()
        runCatching { getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID) } // back on → retract "stopped"
        true
    } catch (e: IllegalStateException) { // incl. ForegroundServiceStartNotAllowedException (API 31+)
        logw("PresenceService: startForeground refused (${e.javaClass.simpleName})")
        DebugLog.add("presence: foreground start refused (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) { // Android 14+: connectedDevice FGS without a Bluetooth permission
        logw("PresenceService: startForeground denied (SecurityException)")
        DebugLog.add("presence: foreground start denied — Bluetooth permission missing")
        false
    }

    /**
     * Post an ordinary (non-foreground) notification that the key stopped because Android refused
     * the foreground restart. Tapping it opens the app, which restarts the key from keyArmed
     * (SetupViewModel.refresh); a later successful foreground start retracts it.
     */
    private fun notifyKeyStopped() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                ALERT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setContentTitle(getString(R.string.key_stopped_title))
                    .setContentText(getString(R.string.key_stopped_text))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build(),
            )
            DebugLog.add("presence: posted key-stopped notification")
        }.onFailure { logw("PresenceService: key-stopped notification failed (${it.javaClass.simpleName})") }
    }

    private fun startAsForegroundOrThrow() {
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
        val text = notificationOverride() ?: state
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        logi("notification: $text  [${PresenceStatus.snapshot()}]")
    }

    /** A fault that outranks whatever status text the caller passed (null = none). */
    private fun notificationOverride(): String? = when {
        locked -> null
        btOff -> BT_OFF_TEXT
        passive && proximityScanFailures >= SCAN_FAIL_NOTIFY_AFTER -> SCAN_FAILED_TEXT
        !passive && phoneKeyMissing -> PK_MISSING_TEXT
        else -> null
    }

    override fun onDestroy() {
        logi("PresenceService: onDestroy")
        DebugLog.add("presence: stopping")
        // Latch teardown BEFORE anything else: from here on updateNotification is a no-op, so a
        // notification observer racing on another thread can't re-post after we remove the FG
        // notification below (which would leave it lingering — the "Off didn't clear it" bug).
        stopping = true
        _running.value = false
        wakeHook = null
        _passive.value = false
        _parkedNearby.value = false
        runCatching { unregisterReceiver(btStateReceiver) }
        runCatching { unregisterReceiver(lockReceiver) }
        // A real stop (user deactivated the key, or system kill) — stop the proximity watch.
        ProximityWake.stopScan(this, proximityCallback)
        passiveLink.disarm()
        departJob?.cancel()
        cancelScanRetry() // an alarm outlives the scope
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
        private const val ALERT_CHANNEL_ID = "wearvian_alerts"
        private const val ALERT_NOTIFICATION_ID = 2
        /** Presence-loop pass cadence (phone-key lookup retry; sensor rescans run on [RescanSchedule]). */
        private const val LOOP_TICK_MS = 5_000L
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
        /** First departure window when a scan is armed while parked nearby ([armProximityScan]):
         *  longer than the debounce, since a sleeping car can take a while to be reported. */
        private const val PARKED_ARM_WINDOW_MS = 90_000L
        /** A node lost this long with no sighting since: the next sighting is a return even if other
         *  nodes were never reported lost (backstop for a dropped MATCH_LOST; see [ProximityTracker]). */
        private const val STRANDED_LOSS_MS = 3 * 60_000L
        /** Extra time a timed wake lock outlives the window it covers, so the window can finish. */
        private const val TIMED_WAKE_SLACK_MS = 5_000L
        /** Backoff bounds for retrying a failed ECDH in [presenceLoop]. */
        private const val ECDH_RETRY_MIN_MS = 2_000L
        /** Poll while the secret is withheld because the watch is locked (see awaitSharedSecret). */
        private const val LOCKED_SECRET_POLL_MS = 2_000L
        private const val ECDH_RETRY_MAX_MS = 60_000L
        /** Charge states that mean the cord is connected (idle drops faster when plugged in). */
        private val PLUGGED_STATES = setOf(
            VehicleStatus.ChargeState.CHARGING,
            VehicleStatus.ChargeState.PLUGGED_IDLE,
            VehicleStatus.ChargeState.STARTING,
        )
        /** How often to check the watch lock state (no reliable "device locked" broadcast). */
        private const val LOCK_POLL_MS = 2_000L
        /** Timed wake lock around a broadcast-triggered lock sync (it only has to run the transition). */
        private const val LOCK_SYNC_WAKE_MS = 5_000L
        /** Settle time before refreshing the tile on a 0x1c change — the stream flickers fast. */
        private const val TILE_REFRESH_DEBOUNCE_MS = 3_000L
        private const val LOCKED_TEXT = "Watch locked"
        private const val PASSIVE_TEXT = "Waiting for vehicle"
        private const val PARKED_TEXT = "Parked nearby"
        private const val DRIVING_TEXT = "In gear · presence paused"
        private const val WATCH_DOZE_TEXT = "Standby · open a door to drive"
        private const val BT_OFF_TEXT = "Bluetooth off"
        private const val PK_MISSING_TEXT = "Phone key not paired"
        private const val SCAN_FAILED_TEXT = "Proximity scan unavailable · retrying"
        /** Proximity-scan retry backoff, and the failure count at which the notification says so. */
        /** Timed wake lock around an alarm-triggered scan retry (it only has to restart the scan). */
        private const val SCAN_RETRY_WAKE_MS = 5_000L
        private const val SCAN_FAIL_NOTIFY_AFTER = 3
        /** Pseudo error code: startScan was rejected synchronously (no scanner / threw / no filters). */
        private const val SCAN_START_FAILED = -1
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

        fun start(context: Context): Boolean =
            startPresence(context, Intent(context, PresenceService::class.java))

        /** Set while a service instance runs in this process: wakes it out of passive in-process. */
        @Volatile private var wakeHook: (() -> Unit)? = null

        /**
         * Wake the key to active (no-op if already active). Uses the running service's in-process
         * hook when there is one; [start] only when it isn't running, since a foreground-service start
         * can be refused while the app is in the background.
         */
        fun wakeToActive(context: Context): Boolean {
            wakeHook?.let { it(); return true }
            return start(context)
        }

        /** Re-arm after an app upgrade, resuming the prior mode (active, or persisted passive idle). */
        fun startAfterUpgrade(context: Context): Boolean =
            startPresence(context, Intent(context, PresenceService::class.java).setAction(ACTION_UPGRADE_RESTORE))

        /** Drop a running service to passive immediately (manual button). */
        fun goPassiveNow(context: Context): Boolean =
            startPresence(context, Intent(context, PresenceService::class.java).setAction(ACTION_GO_PASSIVE))

        /**
         * startForegroundService, but only with the Bluetooth permissions held. Once a service is
         * started this way it MUST call startForeground, and on Android 14+ that throws without a
         * Bluetooth permission; stopping instead is also a crash ("did not then call
         * startForeground"). So the only safe place to refuse is here, before starting. Also absorbs
         * the background FGS-start restriction (e.g. a tile tap while our app is in the background).
         * @return true if the start was handed to the system.
         */
        private fun startPresence(context: Context, intent: Intent): Boolean {
            if (!BlePermissions.granted(context)) {
                DebugLog.add("presence: not starting — Bluetooth permission missing (open the app to grant)")
                return false
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
                logw("PresenceService: start refused (${e.javaClass.simpleName})")
                DebugLog.add("presence: start refused by the system (${e.javaClass.simpleName})")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }
    }
}
