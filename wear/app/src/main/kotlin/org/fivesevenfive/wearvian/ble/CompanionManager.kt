package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import org.fivesevenfive.wearvian.util.DebugLog
import java.util.concurrent.Executor

/**
 * Wraps [CompanionDeviceManager] to drive passive mode's wake-on-approach.
 *
 * CDM is the OS-sanctioned "wake my app when my companion device is near" API: after a
 * one-time user-approved association (a system dialog), the OS observes the device's BLE
 * presence and invokes [org.fivesevenfive.wearvian.service.VehiclePresenceService] —
 * crucially, that callback is **allowlisted to start a foreground service from the
 * background**, the exact thing the Android-12 limit blocks for our own broadcast path. So
 * with CDM we can fully stop the presence service when idle and let the OS cold-start it on
 * approach. See docs/proximity-wake.md.
 *
 * Requires API 33 (AssociationInfo); the watch is API 34+. Callers guard on [isSupported].
 */
@SuppressLint("MissingPermission")
class CompanionManager(private val context: Context) {

    private val cdm: CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** CDM presence-observation needs API 33 (AssociationInfo). Watch is 34+; phones may be older. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cdm != null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associations(): List<AssociationInfo> =
        runCatching { cdm?.myAssociations.orEmpty() }.getOrDefault(emptyList())

    /** Whether the Rivian phone key has already been associated (so no dialog is needed). */
    fun isAssociated(): Boolean = isSupported && associations().isNotEmpty()

    /**
     * Ask CDM to associate the Rivian phone key. The system surfaces a chooser dialog whose
     * [IntentSender] is handed to [onPending] — the caller (an Activity) must launch it. On
     * confirm, [onCreated] fires (and the OS persists the association). [onError] on failure.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAssociation(
        onPending: (IntentSender) -> Unit,
        onCreated: (AssociationInfo) -> Unit,
        onError: (CharSequence?) -> Unit,
    ) {
        val cdm = cdm ?: return onError("no CompanionDeviceManager")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(ScanFilter.Builder().setDeviceName(RivianBle.DEVICE_NAME).build())
                    .build(),
            )
            .setSingleDevice(true) // one phone-key module — skip the list, confirm the single hit
            .build()
        val executor = Executor { it.run() }
        cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                DebugLog.add("cdm: association pending — launching system dialog")
                onPending(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                DebugLog.add("cdm: associated ${associationInfo.deviceMacAddress}")
                onCreated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) {
                DebugLog.add("cdm: association failed — $error")
                onError(error)
            }
        })
    }

    /** Start OS presence observation for every association → wakes [VehiclePresenceService]. */
    fun startObserving() {
        if (!isSupported) return
        forEachAssociationAddress { mac ->
            @Suppress("DEPRECATION") // (String) form works API 31+; the request form is API 35-only
            runCatching { cdm?.startObservingDevicePresence(mac) }
                .onFailure { DebugLog.add("cdm: startObserving $mac failed — ${it.message}") }
                .onSuccess { DebugLog.add("cdm: observing $mac") }
        }
    }

    /** Stop OS presence observation (passive mode turned off / key deactivated). */
    fun stopObserving() {
        if (!isSupported) return
        forEachAssociationAddress { mac ->
            @Suppress("DEPRECATION")
            runCatching { cdm?.stopObservingDevicePresence(mac) }
                .onFailure { DebugLog.add("cdm: stopObserving $mac failed — ${it.message}") }
        }
        DebugLog.add("cdm: observation stopped")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun forEachAssociationAddress(block: (String) -> Unit) {
        associations().forEach { a -> a.deviceMacAddress?.toString()?.uppercase()?.let(block) }
    }
}
