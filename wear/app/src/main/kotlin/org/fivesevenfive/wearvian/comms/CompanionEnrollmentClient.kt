package org.fivesevenfive.wearvian.comms

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Watch side of enrollment: asks the companion phone app to perform the Rivian
 * cloud login + EnrollPhone for this watch's public key, and awaits the result
 * over the Wear OS Data Layer. Foreground-only is fine — enrollment is an
 * interactive, one-time (then ~monthly) step.
 *
 * The watch's private key never leaves the watch; only [publicKeyHex] is sent.
 */
class CompanionEnrollmentClient(private val context: Context) {

    class NoCompanionException :
        Exception("No phone with the wearvian companion app was found. Install it and open it.")

    /**
     * Send an enrollment request to the companion and suspend until the result
     * arrives (or [timeoutMs] elapses). Throws [NoCompanionException] if no phone
     * advertising the companion capability is reachable.
     */
    suspend fun requestEnrollment(
        publicKeyHex: String,
        deviceName: String,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): EnrollmentContract.Result {
        val messageClient = Wearable.getMessageClient(context)
        val capabilityClient = Wearable.getCapabilityClient(context)

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<EnrollmentContract.Result>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path != EnrollmentContract.PATH_RESULT) return@OnMessageReceivedListener
            runCatching { EnrollmentContract.parseResult(event.data) }
                .onSuccess { if (it.requestId == requestId && !deferred.isCompleted) deferred.complete(it) }
        }
        messageClient.addListener(listener).await()
        try {
            val node = findCompanionNode(capabilityClient) ?: throw NoCompanionException()
            val payload = EnrollmentContract.buildRequest(requestId, publicKeyHex, deviceName)
            messageClient.sendMessage(node, EnrollmentContract.PATH_REQUEST, payload).await()
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    private suspend fun findCompanionNode(cc: CapabilityClient): String? {
        val info = cc.getCapability(EnrollmentContract.CAP_PHONE, CapabilityClient.FILTER_REACHABLE).await()
        return info.nodes.firstOrNull { it.isNearby }?.id ?: info.nodes.firstOrNull()?.id
    }
}
