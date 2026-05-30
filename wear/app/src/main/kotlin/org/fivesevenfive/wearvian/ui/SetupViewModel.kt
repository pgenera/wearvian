package org.fivesevenfive.wearvian.ui

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.BuildConfig
import org.fivesevenfive.wearvian.ble.PairingManager
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.net.AuthBrokerClient
import org.fivesevenfive.wearvian.net.RivianCloud
import org.fivesevenfive.wearvian.protocol.RivianGql
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore

enum class Phase { LOADING, NEEDS_SETUP, AWAITING_BROWSER, ENROLLING, ENROLLED, PAIRING, BONDED, ERROR }

data class SetupUiState(
    val phase: Phase = Phase.LOADING,
    val qr: ImageBitmap? = null,
    val browserUrl: String? = null,
    val detail: String? = null,
    val presenceRunning: Boolean = false,
)

/** Drives the full enroll -> pair -> presence flow and exposes UI state. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val store = EnrollmentStore(app)
    private val keyManager = KeyManager()
    private val broker = AuthBrokerClient()
    private val cloud = RivianCloud()

    private val _state = mutableStateOf(SetupUiState())
    val state: State<SetupUiState> get() = _state

    fun refresh() {
        val e = store.load()
        _state.value = when {
            e == null -> SetupUiState(Phase.NEEDS_SETUP)
            e.bonded -> SetupUiState(Phase.BONDED)
            else -> SetupUiState(Phase.ENROLLED, detail = e.vin)
        }
    }

    /** One-time cloud enrollment via the QR/browser handoff. */
    fun startEnrollment() {
        viewModelScope.launch {
            runCatching {
                _state.value = SetupUiState(Phase.LOADING, detail = "Contacting auth server…")
                val session = broker.createSession(BuildConfig.BROKER_URL)
                _state.value = SetupUiState(
                    Phase.AWAITING_BROWSER,
                    qr = qrImageBitmap(session.browserUrl),
                    browserUrl = session.browserUrl,
                    detail = "Scan to sign in",
                )
                val tokens = broker.awaitTokens(session.pollUrl)

                _state.value = SetupUiState(Phase.ENROLLING, detail = "Reading vehicle…")
                val (_, info) = cloud.getUserInfo(tokens)
                val vehicle = info.vehicles.firstOrNull()
                    ?: error("No vehicles on this Rivian account")

                val publicKeyHex = keyManager.ensureKey()
                val ok = cloud.enrollPhone(
                    tokens, info.userId, vehicle.vehicleId, publicKeyHex,
                    deviceType = "watch", deviceName = "wearvian",
                )
                require(ok) { "Rivian rejected enrollment" }

                // Read back our VAS phone id + identity id for this key.
                val (raw2, _) = cloud.getUserInfo(tokens)
                val enrolled: RivianGql.EnrolledPhone =
                    RivianGql.findEnrolledPhone(raw2, publicKeyHex)
                        ?: error("Enrolled, but couldn't read back phone id")

                store.save(
                    Enrollment(
                        userId = info.userId,
                        vehicleId = vehicle.vehicleId,
                        vin = vehicle.vin,
                        vasVehicleId = vehicle.vasVehicleId,
                        vehiclePublicKey = vehicle.vehiclePublicKey,
                        vasPhoneId = enrolled.vasPhoneId,
                        identityId = enrolled.identityId,
                        bonded = false,
                    ),
                )
                _state.value = SetupUiState(Phase.ENROLLED, detail = vehicle.vin)
            }.onFailure {
                _state.value = SetupUiState(Phase.ERROR, detail = it.message ?: "Enrollment failed")
            }
        }
    }

    /** Local BLE pairing + bonding. Requires BLE permissions to be granted first. */
    fun startPairing() {
        val enrollment = store.load() ?: run {
            _state.value = SetupUiState(Phase.NEEDS_SETUP); return
        }
        viewModelScope.launch {
            val pm = PairingManager(getApplication(), keyManager)
            _state.value = SetupUiState(Phase.PAIRING, detail = "Starting…")
            pm.pair(enrollment) { progress ->
                _state.value = SetupUiState(Phase.PAIRING, detail = progress.toString())
            }.onSuccess {
                store.setBonded(true)
                PresenceService.start(getApplication())
                _state.value = SetupUiState(Phase.BONDED, presenceRunning = true)
            }.onFailure {
                _state.value = SetupUiState(Phase.ERROR, detail = it.message ?: "Pairing failed")
            }
        }
    }

    fun setPresence(on: Boolean) {
        if (on) PresenceService.start(getApplication()) else PresenceService.stop(getApplication())
        _state.value = _state.value.copy(presenceRunning = on)
    }

    fun reset() {
        PresenceService.stop(getApplication())
        store.clear()
        keyManager.deleteKey()
        _state.value = SetupUiState(Phase.NEEDS_SETUP)
    }
}
