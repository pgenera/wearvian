package org.fivesevenfive.wearvian.comms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.runBlocking
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.loge
import org.fivesevenfive.wearvian.util.logi

/**
 * Receives an imported phone key pushed from the companion app (the HA-key import
 * flow), stores it as the active key + enrollment, and acks back to the phone.
 *
 * This replaces the watch's own keypair: importing switches [KeyManager] to
 * software ECDH with the supplied private key (see [org.fivesevenfive.wearvian.store.ImportedKeyStore]).
 * The next time the app opens it shows ENROLLED, ready to pair over BLE.
 */
class WearImportListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != ImportContract.PATH_KEY) return
        logi("import: received key push from=${event.sourceNodeId} bytes=${event.data.size}")
        val node = event.sourceNodeId
        val key = runCatching { ImportContract.parseKey(event.data) }
            .onFailure { loge("import: failed to parse key payload", it) }
            .getOrNull()
        if (key == null) {
            ack(node, ImportContract.ackError("", "Malformed import payload"))
            return
        }
        runCatching {
            // First vehicle is the active one (the HA key is enrolled to a single vehicle in
            // practice; multi-vehicle accounts can be revisited later).
            val v = key.vehicles.first()
            KeyManager(this).importKey(key.privateKeyPemBase64, key.publicKeyHex)
            EnrollmentStore(this).save(
                Enrollment(
                    userId = key.userId,
                    vehicleId = v.vehicleId,
                    vin = v.vin,
                    vasVehicleId = v.vasVehicleId,
                    vehiclePublicKey = v.vehiclePublicKey,
                    vasPhoneId = v.vasPhoneId,
                    identityId = v.identityId,
                    asWatch = key.asWatch,
                    bonded = false,
                ),
            )
            logi("import: stored imported key + enrollment vin=${v.vin} asWatch=${key.asWatch}")
            v.vin
        }.onSuccess { vin ->
            notifyImported()
            ack(node, ImportContract.ackOk(key.requestId, vin))
        }.onFailure { e ->
            loge("import: failed to store imported key", e)
            ack(node, ImportContract.ackError(key.requestId, e.message ?: "Import failed"))
        }
    }

    private fun ack(nodeId: String, payload: ByteArray) {
        runCatching {
            runBlocking {
                Wearable.getMessageClient(this@WearImportListenerService)
                    .sendMessage(nodeId, ImportContract.PATH_RESULT, payload).await()
            }
            logi("import: ack sent to node=$nodeId")
        }.onFailure { logi("import: ack send failed: ${it.message}") }
    }

    private fun notifyImported() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Key import", NotificationManager.IMPORTANCE_HIGH),
        )
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Rivian key imported")
                .setContentText("Open wearvian to pair with your vehicle.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL = "wearvian_import"
        const val NOTIF_ID = 1002
    }
}
