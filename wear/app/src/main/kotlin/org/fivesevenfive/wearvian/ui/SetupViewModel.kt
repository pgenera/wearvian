package org.fivesevenfive.wearvian.ui

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.ble.PairingManager
import org.fivesevenfive.wearvian.comms.CompanionEnrollmentClient
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.util.loge
import org.fivesevenfive.wearvian.util.logi

enum class Phase { LOADING, NEEDS_SETUP, AWAITING_COMPANION, ENROLLED, PAIRING, BONDED, ERROR }

data class SetupUiState(
    val phase: Phase = Phase.LOADING,
    val detail: String? = null,
    val presenceRunning: Boolean = false,
)

/** Drives the full enroll -> pair -> presence flow and exposes UI state. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val store = EnrollmentStore(app)
    private val keyManager = KeyManager()
    private val companion = CompanionEnrollmentClient(app)

    private val _state = mutableStateOf(SetupUiState())
    val state: State<SetupUiState> get() = _state

    fun refresh() {
        val e = store.load()
        logi("refresh: enrolled=${e != null} bonded=${e?.bonded} vin=${e?.vin}")
        _state.value = when {
            e == null -> SetupUiState(Phase.NEEDS_SETUP)
            e.bonded -> SetupUiState(Phase.BONDED)
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
                PresenceService.start(getApplication())
                _state.value = SetupUiState(Phase.BONDED, presenceRunning = true)
            }.onFailure {
                loge("startPairing failed", it)
                _state.value = SetupUiState(Phase.ERROR, detail = it.message ?: "Pairing failed")
            }
        }
    }

    fun setPresence(on: Boolean) {
        logi("setPresence: $on")
        if (on) PresenceService.start(getApplication()) else PresenceService.stop(getApplication())
        _state.value = _state.value.copy(presenceRunning = on)
    }

    fun reset() {
        logi("reset: clearing enrollment + key, stopping presence")
        PresenceService.stop(getApplication())
        store.clear()
        keyManager.deleteKey()
        _state.value = SetupUiState(Phase.NEEDS_SETUP)
    }
}
