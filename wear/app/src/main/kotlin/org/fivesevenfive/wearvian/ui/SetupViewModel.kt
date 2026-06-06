package org.fivesevenfive.wearvian.ui

import android.app.Application
import android.app.KeyguardManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.fivesevenfive.wearvian.ble.ActiveCommandManager
import org.fivesevenfive.wearvian.ble.CommandBus
import org.fivesevenfive.wearvian.ble.PairingManager
import org.fivesevenfive.wearvian.ble.ProximityWake
import org.fivesevenfive.wearvian.comms.CompanionEnrollmentClient
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.loge
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw

enum class Phase { LOADING, NEEDS_SETUP, AWAITING_COMPANION, ENROLLED, PAIRING, BONDED, ERROR }

data class SetupUiState(
    val phase: Phase = Phase.LOADING,
    val detail: String? = null,
    val presenceRunning: Boolean = false,
    /** Command codes currently being sent over BLE — drives the in-flight throb on each button. */
    val inFlight: Set<Int> = emptySet(),
    /** Auto power-save (proximity wake) toggle, surfaced on the settings screen. */
    val proximityWakeEnabled: Boolean = true,
    /** Whether the watch has a secure lock set; if not, the anti-theft gating can't engage. */
    val deviceSecure: Boolean = true,
    /** Whether the mobile key is armed (intent). With [presenceRunning] this gives the
     *  tri-state: off / active (running) / passive (armed but power-saving). */
    val keyArmed: Boolean = false,
)

/** How long a command tap throbs when routed to the live session (fire-and-forget). */
private const val COMMAND_INFLIGHT_MS = 1_200L

/** Drives the full enroll -> pair -> presence flow and exposes UI state. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val store = EnrollmentStore(app)
    private val settings = SettingsStore(app)
    private val keyManager = KeyManager()
    private val companion = CompanionEnrollmentClient(app)

    private val _state = mutableStateOf(SetupUiState())
    val state: State<SetupUiState> get() = _state

    init {
        // Live tri-state: when the presence service starts/stops (e.g. the proximity wake
        // brings it up on approach, or it idles to passive), reflect it on the BONDED
        // screen without waiting for a resume. Re-read keyArmed so off/active/passive stay
        // consistent (e.g. the notification "Off" both stops the service and disarms).
        viewModelScope.launch {
            PresenceService.running.collect { running ->
                if (_state.value.phase == Phase.BONDED) {
                    _state.value = _state.value.copy(presenceRunning = running, keyArmed = settings.keyArmed)
                }
            }
        }
    }

    fun refresh() {
        val e = store.load()
        // Log key presence on every launch so persistence across app updates is
        // verifiable from logcat: enrolled+hasKey should stay true after an update.
        logi("refresh: enrolled=${e != null} hasKey=${keyManager.hasKey()} bonded=${e?.bonded} vin=${e?.vin}")
        _state.value = when {
            e == null -> SetupUiState(Phase.NEEDS_SETUP)
            // Reflect the real foreground-service state so the toggle doesn't desync
            // when the UI recomposes (e.g. returning from the debug console).
            e.bonded -> {
                val running = PresenceService.isRunning
                // Armed but the service isn't running → make "Key passive" real: register the
                // offloaded proximity wake so the OS starts us when the car comes into range
                // (and fires immediately if it's already nearby → flips to active).
                if (settings.keyArmed && !running) {
                    ProximityWake.armForVehicle(getApplication(), e.vasVehicleId)
                }
                SetupUiState(
                    Phase.BONDED,
                    presenceRunning = running,
                    proximityWakeEnabled = settings.proximityWakeEnabled,
                    deviceSecure = isDeviceSecure(),
                    keyArmed = settings.keyArmed,
                )
            }
            else -> SetupUiState(Phase.ENROLLED, detail = e.vin)
        }
    }

    /**
     * One-time enrollment via the companion phone app over the Wear Data Layer.
     * The watch generates its keypair and sends only the public key; the phone
     * performs the Rivian cloud login + EnrollPhone and returns the VAS IDs.
     */
    fun startEnrollment() {
        logi("startEnrollment: begin")
        viewModelScope.launch {
            runCatching {
                _state.value = SetupUiState(
                    Phase.AWAITING_COMPANION,
                    detail = "Open wearvian companion on your phone and sign in to Rivian…",
                )
                val publicKeyHex = keyManager.ensureKey()
                logi("startEnrollment: public key ready (len=${publicKeyHex.length}); contacting companion")
                val result = companion.requestEnrollment(publicKeyHex, deviceName = "Pixel Watch 4")
                if (!result.isOk) error(result.error ?: "Enrollment failed")
                val vehicle = result.vehicles.firstOrNull()
                    ?: error("No vehicles on this Rivian account")
                logi("startEnrollment: enrolled vin=${vehicle.vin} vasPhoneId=${vehicle.vasPhoneId} identityId=${vehicle.identityId}")

                store.save(
                    Enrollment(
                        userId = result.userId,
                        vehicleId = vehicle.vehicleId,
                        vin = vehicle.vin,
                        vasVehicleId = vehicle.vasVehicleId,
                        vehiclePublicKey = vehicle.vehiclePublicKey,
                        vasPhoneId = vehicle.vasPhoneId,
                        identityId = vehicle.identityId,
                        bonded = false,
                    ),
                )
                _state.value = SetupUiState(Phase.ENROLLED, detail = vehicle.vin)
                logi("startEnrollment: stored enrollment; phase=ENROLLED")
            }.onFailure {
                loge("startEnrollment failed", it)
                _state.value = SetupUiState(Phase.ERROR, detail = it.message ?: "Enrollment failed")
            }
        }
    }

    /** Local BLE pairing + bonding. Requires BLE permissions to be granted first. */
    fun startPairing() {
        val enrollment = store.load() ?: run {
            logi("startPairing: not enrolled -> NEEDS_SETUP")
            _state.value = SetupUiState(Phase.NEEDS_SETUP); return
        }
        logi("startPairing: begin for vin=${enrollment.vin}")
        viewModelScope.launch {
            val pm = PairingManager(getApplication(), keyManager)
            _state.value = SetupUiState(Phase.PAIRING, detail = "Starting…")
            pm.pair(enrollment) { progress ->
                logi("pairing progress: $progress")
                _state.value = SetupUiState(Phase.PAIRING, detail = progress.toString())
            }.onSuccess {
                logi("startPairing: bonded; starting presence service")
                store.setBonded(true)
                settings.keyArmed = true
                PresenceService.start(getApplication())
                _state.value = SetupUiState(
                    Phase.BONDED,
                    presenceRunning = true,
                    keyArmed = true,
                    proximityWakeEnabled = settings.proximityWakeEnabled,
                    deviceSecure = isDeviceSecure(),
                )
            }.onFailure {
                loge("startPairing failed", it)
                _state.value = SetupUiState(Phase.ERROR, detail = it.message ?: "Pairing failed")
            }
        }
    }

    /**
     * Send an authenticated active-entry command (e.g. lock/unlock) over BLE. The
     * command code is held in [SetupUiState.inFlight] for the duration so the UI can
     * throb + disable that button until the send completes or times out. Duplicate
     * taps while in flight are ignored. [ActiveCommandManager.sendCommand] is fully
     * time-bounded and never throws, so the code always clears.
     */
    fun sendCommand(commandCode: Int, label: String) {
        val enrollment = store.load() ?: run { logw("sendCommand: not enrolled"); return }
        if (commandCode in _state.value.inFlight) {
            logi("sendCommand: $label already in flight — ignoring"); return
        }
        logi("sendCommand: $label (0x%04x)".format(commandCode))
        _state.value = _state.value.copy(inFlight = _state.value.inFlight + commandCode)
        viewModelScope.launch {
            try {
                if (PresenceService.isRunning) {
                    // Live session up: ride it so presence-gated closures (charge-port,
                    // windows, liftgate) are accepted, and use its running counter. The
                    // PRIMARY VehicleSession sends on its next heartbeat tick (~300 ms).
                    CommandBus.submit(commandCode, label)
                    delay(COMMAND_INFLIGHT_MS) // brief throb; the send is fire-and-forget on the session
                } else {
                    // Key off / no session: one-shot connect→handshake→command. Fine for
                    // lock/unlock (the security module answers half-asleep).
                    withContext(Dispatchers.IO) {
                        ActiveCommandManager(getApplication(), keyManager).sendCommand(enrollment, commandCode, label)
                    }
                }
            } finally {
                _state.value = _state.value.copy(inFlight = _state.value.inFlight - commandCode)
            }
        }
    }

    fun setPresence(on: Boolean) {
        logi("setPresence: $on")
        settings.keyArmed = on // arm/disarm intent — survives auto power-save going passive
        if (on) PresenceService.start(getApplication()) else PresenceService.stop(getApplication())
        _state.value = _state.value.copy(presenceRunning = on, keyArmed = on)
    }

    /**
     * Toggle auto power-save (proximity wake). Persisted; [PresenceService] reads it when
     * deciding whether to drop to passive. Takes effect on the next idle cycle.
     */
    /** True iff the watch has a secure lock (PIN/pattern/password) — required for the
     *  remove-from-wrist anti-theft gating to actually engage. */
    private fun isDeviceSecure(): Boolean =
        getApplication<Application>().getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Manually enter passive mode: arm the key (if not already) and either drop a running
     * service to passive now, or just register the proximity wake if it isn't running. The
     * live running flow flips the UI to "Key passive".
     */
    fun startPassive() {
        val e = store.load() ?: return
        logi("startPassive: manual")
        settings.keyArmed = true
        if (PresenceService.isRunning) {
            PresenceService.goPassiveNow(getApplication())
        } else {
            ProximityWake.armForVehicle(getApplication(), e.vasVehicleId)
        }
        _state.value = _state.value.copy(keyArmed = true)
    }

    fun setProximityWake(on: Boolean) {
        logi("setProximityWake: $on")
        settings.proximityWakeEnabled = on
        _state.value = _state.value.copy(proximityWakeEnabled = on)
    }

    /**
     * Clears the local enrollment/pairing cache and stops presence, but **keeps the
     * watch's non-exportable Keystore key**. Re-enrolling then reuses the same public
     * key — which the vehicle already trusts — so this never forces a re-key (and
     * never requires deleting/re-adding the key on the vehicle). Use [wipeIdentity]
     * for the rare, deliberate full reset.
     */
    fun reset() {
        logi("reset: clearing enrollment cache + stopping presence (KEEPING Keystore key)")
        settings.keyArmed = false
        PresenceService.stop(getApplication())
        store.clear()
        _state.value = SetupUiState(Phase.NEEDS_SETUP)
    }

    /**
     * Destructive: also deletes the non-exportable Keystore key, forcing generation
     * of a brand-new key and a full re-enrollment (the old phone key must be removed
     * on the vehicle). Not wired to a casual button — only call behind explicit
     * confirmation.
     */
    fun wipeIdentity() {
        logw("wipeIdentity: deleting Keystore key + enrollment — full re-key + re-enroll required")
        settings.keyArmed = false
        PresenceService.stop(getApplication())
        store.clear()
        keyManager.deleteKey()
        _state.value = SetupUiState(Phase.NEEDS_SETUP)
    }
}
