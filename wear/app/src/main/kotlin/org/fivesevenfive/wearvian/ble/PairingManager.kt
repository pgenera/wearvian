package org.fivesevenfive.wearvian.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.PairingFrames
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw
import org.fivesevenfive.wearvian.util.toHexString
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Performs the local BLE pairing handshake with the vehicle, mirroring
 * `ble.pair_phone` from the reference client, then triggers Android bonding.
 *
 * Handshake (phone = central, vehicle "Rivian Phone Key" = peripheral):
 *   scan by name -> connect -> discover -> enable notifications ->
 *   write vasPhoneId -> verify echoed vasVehicleId -> write nonce‖HMAC ->
 *   createBond().
 *
 * Once bonded + enrolled, the vehicle's proximity sensors handle passive unlock
 * and drive enablement; there is no explicit "drive" command (see docs/passive-entry-protocol.md).
 *
 * Permission note: callers must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class PairingManager(
    private val context: Context,
    private val keyManager: KeyManager,
) {
    sealed interface Progress {
        data object Scanning : Progress
        data object Connecting : Progress
        data object Handshaking : Progress
        data object Bonding : Progress
        data class Failed(val reason: String) : Progress
        data object Bonded : Progress
    }

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // Single-slot deferreds: the handshake is strictly sequential.
    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    private var charWritten = CompletableDeferred<Boolean>()
    // Written by the pairing coroutine, read/completed by the GATT callback thread → concurrent.
    private val notifications = ConcurrentHashMap<UUID, CompletableDeferred<ByteArray>>()

    suspend fun pair(
        enrollment: Enrollment,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = runCatching {
        logi("pair: start for vin=${enrollment.vin} vasPhoneId=${enrollment.vasPhoneId}")
        onProgress(Progress.Scanning)
        val device = scanForVehicle()
        logi("pair: found device ${device.name} ${device.address} bondState=${device.bondState}")

        // A bond left over from a PREVIOUS enrollment is orphaned once the vehicle's
        // key changes (e.g. you cleared app data + re-enrolled). Android then tries to
        // encrypt the link with the stale LTK, the vehicle reports key-missing
        // (encryption failure 0x6), and the link is torn down before the handshake can
        // run — exactly the "Timed out talking to the vehicle" we hit. Clear it so we
        // connect fresh; the Rivian handshake runs in the clear (no encryption needed).
        removeStaleBond(device)

        onProgress(Progress.Connecting)
        val gatt = device.connectGatt(context, false, gattCallback)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            logi("pair: GATT connected; discovering services")
            gatt.discoverServices()
            withTimeout(GATT_OP_TIMEOUT_MS) { servicesReady.await() }
            logi("pair: services discovered: ${gatt.services.map { it.uuid }}")

            onProgress(Progress.Handshaking)
            val phoneIdChar = requireChar(gatt, RivianBle.CHAR_PHONE_ID_VEHICLE_ID)
            val nonceChar = requireChar(gatt, RivianBle.CHAR_PHONE_NONCE_VEHICLE_NONCE)
            enableNotifications(gatt, phoneIdChar)
            enableNotifications(gatt, nonceChar)
            logi("pair: notifications enabled on phoneId+nonce chars")

            // Step 1: announce our phone id and confirm we're talking to the right car.
            notifications[phoneIdChar.uuid] = CompletableDeferred()
            writeChar(gatt, phoneIdChar, PairingFrames.phoneIdBytes(enrollment.vasPhoneId))
            logi("pair: wrote vasPhoneId; awaiting echoed vehicle id")
            val vehicleId = withTimeout(GATT_OP_TIMEOUT_MS) { notifications.getValue(phoneIdChar.uuid).await() }
            logi("pair: vehicle echoed id=${vehicleId.toHexString().take(12)}… (matching vasVehicleId)")
            require(PairingFrames.vehicleIdMatches(vehicleId, enrollment.vasVehicleId)) {
                "Vehicle id mismatch — wrong vehicle or wrong enrollment"
            }

            // Step 2: prove possession of the enrolled key via HMAC over a fresh nonce.
            val nonce = ByteArray(PairingFrames.PHONE_NONCE_LEN).also { SecureRandom().nextBytes(it) }
            val hmac = keyManager.signNonce(enrollment.vehiclePublicKey, nonce)
            // Don't log the nonce/HMAC values — they're the pairing auth proof. Sizes only.
            logi("pair: nonce(${nonce.size}B)+hmac(${hmac.size}B) ready; writing pairing frame")
            notifications[nonceChar.uuid] = CompletableDeferred()
            writeChar(gatt, nonceChar, PairingFrames.pairingWrite(nonce, hmac))
            withTimeout(GATT_OP_TIMEOUT_MS) { notifications.getValue(nonceChar.uuid).await() }
            logi("pair: vehicle acked pairing frame")

            // Step 3: vehicle is authenticated — trigger OS-level bonding.
            onProgress(Progress.Bonding)
            bond(device)
            logi("pair: bonded OK")
            onProgress(Progress.Bonded)
        } finally {
            // Keep the bond, but this transient setup connection can close;
            // PresenceService owns the long-lived connection afterwards.
            gatt.disconnect()
            gatt.close()
        }
    }.onFailure {
        val reason = if (it is TimeoutCancellationException) "Timed out talking to the vehicle" else (it.message ?: "Pairing failed")
        onProgress(Progress.Failed(reason))
    }

    private suspend fun scanForVehicle(): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth is off")
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                logi("scan: hit name=${result.device.name} addr=${result.device.address} rssi=${result.rssi}")
                if (!found.isCompleted) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                logw("scan: failed errorCode=$errorCode")
            }
        }
        val filter = ScanFilter.Builder().setDeviceName(RivianBle.DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        logi("scan: starting for name='${RivianBle.DEVICE_NAME}' (timeout ${SCAN_TIMEOUT_MS}ms)")
        scanner.startScan(listOf(filter), settings, cb)
        return try {
            withTimeout(SCAN_TIMEOUT_MS) { found.await() }
        } catch (e: TimeoutCancellationException) {
            error("Couldn't find your vehicle. Make sure you selected \"Set Up\" for this key in the car.")
        } finally {
            scanner.stopScan(cb)
        }
    }

    private fun requireChar(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic {
        // The pairing characteristics aren't all under the Active Entry service,
        // so search every discovered service.
        for (service in gatt.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        error("Characteristic $uuid not found on vehicle")
    }

    private suspend fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(RivianBle.CCCD) ?: error("Missing CCCD on ${char.uuid}")
        descriptorWritten = CompletableDeferred()
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        withTimeout(GATT_OP_TIMEOUT_MS) { descriptorWritten.await() }
    }

    private suspend fun writeChar(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        charWritten = CompletableDeferred()
        gatt.writeCharacteristic(
            char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        withTimeout(GATT_OP_TIMEOUT_MS) { charWritten.await() }
    }

    /**
     * Remove any existing OS bond before connecting. After a re-enrollment the
     * vehicle holds a new key, so a bond from the prior enrollment is orphaned and
     * makes Android's auto-encryption fail with key-missing — pairing then times out
     * at the first GATT op. Clearing it lets us connect unencrypted (the handshake is
     * in the clear); a fresh matching bond is re-established at the end of [pair].
     * `removeBond()` is a hidden API, so call it reflectively.
     */
    private suspend fun removeStaleBond(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        val gone = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    == BluetoothDevice.BOND_NONE
                ) {
                    gone.complete(true)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            logi("pair: clearing stale bond (bondState=${device.bondState}) before fresh connect")
            val started = runCatching {
                device.javaClass.getMethod("removeBond").invoke(device) as Boolean
            }.getOrElse { logw("pair: removeBond() reflection failed — ${it.message}"); false }
            if (started) {
                runCatching { withTimeout(BOND_TIMEOUT_MS) { gone.await() } }
                    .onFailure { logw("pair: bond removal not confirmed in time; continuing") }
            }
            logi("pair: bond cleared (bondState=${device.bondState})")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            logi("bond: already bonded")
            return
        }
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                logi("bond: EXTRA_BOND_STATE=$state")
                when (state) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            logi("bond: calling createBond()")
            require(device.createBond()) { "Failed to start bonding" }
            val ok = withTimeout(BOND_TIMEOUT_MS) { bonded.await() }
            require(ok) { "Bonding was rejected" }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logi("gatt: onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!connected.isCompleted) connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Disconnected"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logi("gatt: onServicesDiscovered status=$status")
            servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            logi("gatt: onDescriptorWrite ${descriptor.characteristic.uuid} status=$status")
            descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            logi("gatt: onCharacteristicWrite ${characteristic.uuid} status=$status")
            charWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ value-carrying callback (minSdk is 33).
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            logi("gatt: onCharacteristicChanged ${characteristic.uuid} value=${value.toHexString()}")
            notifications[characteristic.uuid]?.complete(value)
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
        // Match the official app's direct-connect watchdog (ap/h1: 0x88b8 = 35 s); 10 s was too
        // short for a dormant module and made pairing take several tries. GATT_OP matches the app's
        // 5 s operation watchdog (l60/i).
        const val CONNECT_TIMEOUT_MS = 35_000L
        const val GATT_OP_TIMEOUT_MS = 5_000L
        // Bonding can be slow (OS retries, user confirmation); give it plenty of headroom so a slow
        // bond doesn't fail the pairing and force a retry.
        const val BOND_TIMEOUT_MS = 60_000L
    }
}
