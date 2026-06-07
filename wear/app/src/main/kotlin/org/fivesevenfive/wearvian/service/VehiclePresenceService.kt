package org.fivesevenfive.wearvian.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.loge

/**
 * Bound by the OS when the associated Rivian phone key comes into / out of range (set up via
 * [org.fivesevenfive.wearvian.ble.CompanionManager.startObserving]). [onDeviceAppeared] is
 * **allowlisted to start a foreground service from the background**, so it can cold-start
 * [PresenceService] on approach even from a fully-stopped process — the case the offloaded-scan
 * broadcast path couldn't handle (Android-12 FGS-start limit). See docs/proximity-wake.md.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class VehiclePresenceService : CompanionDeviceService() {

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val armed = SettingsStore(this).keyArmed
        val enrolled = EnrollmentStore(this).load() != null
        DebugLog.add("cdm: device appeared ${associationInfo.deviceMacAddress} (armed=$armed enrolled=$enrolled)")
        // Respect the user's intent: only wake if the key is on and we're enrolled.
        if (!armed || !enrolled) {
            DebugLog.add("cdm: appeared but key off / not enrolled — ignoring")
            return
        }
        runCatching { PresenceService.start(this) }
            .onFailure {
                loge("cdm: presence start failed", it)
                DebugLog.add("cdm: presence start failed — ${it.message}")
            }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Don't tear down here — a brief BLE blip would falsely stop an active session. The
        // presence service's own idle timer (IDLE_TIMEOUT_MS with no link) handles teardown.
        DebugLog.add("cdm: device disappeared ${associationInfo.deviceMacAddress}")
    }
}
