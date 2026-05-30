package org.fivesevenfive.wearvian.comms

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw
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
        logi("requestEnrollment: requestId=$requestId publicKey=${publicKeyHex.take(16)}… len=${publicKeyHex.length} timeoutMs=$timeoutMs")
        val deferred = CompletableDeferred<EnrollmentContract.Result>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            logi("onMessageReceived path=${event.path} from=${event.sourceNodeId} bytes=${event.data.size}")
            if (event.path != EnrollmentContract.PATH_RESULT) return@OnMessageReceivedListener
            runCatching { EnrollmentContract.parseResult(event.data) }
                .onSuccess {
                    logi("parsed result: status=${it.status} requestId=${it.requestId} vehicles=${it.vehicles.size} error=${it.error}")
                    if (it.requestId == requestId && !deferred.isCompleted) deferred.complete(it)
                    else logw("result requestId mismatch (got ${it.requestId}, want $requestId) or already completed")
                }
                .onFailure { logw("failed to parse result payload", it) }
        }
        messageClient.addListener(listener).await()
        logi("result listener registered on $PATH_RESULT")
        try {
            val node = findCompanionNode(capabilityClient) ?: run {
                logw("no companion node advertising '${EnrollmentContract.CAP_PHONE}' — throwing NoCompanionException")
                throw NoCompanionException()
            }
            val payload = EnrollmentContract.buildRequest(requestId, publicKeyHex, deviceName)
            logi("sending request to node=$node path=${EnrollmentContract.PATH_REQUEST} bytes=${payload.size}")
            messageClient.sendMessage(node, EnrollmentContract.PATH_REQUEST, payload).await()
            logi("request sent; awaiting result (up to ${timeoutMs}ms)")
            val result = withTimeout(timeoutMs) { deferred.await() }
            logi("enrollment result received: status=${result.status}")
            return result
        } finally {
            messageClient.removeListener(listener)
            logi("result listener removed")
        }
    }

    private suspend fun findCompanionNode(cc: CapabilityClient): String? {
        // Diagnostics: what does the watch actually see over the Data Layer?
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            logi("connectedNodes=${nodes.map { "${it.displayName}/${it.id}(nearby=${it.isNearby})" }}")
            val all = cc.getAllCapabilities(CapabilityClient.FILTER_REACHABLE).await()
            logi("allReachableCapabilities=${all.keys}")
        }.onFailure { logw("diagnostic node/capability query failed", it) }

        val info = cc.getCapability(EnrollmentContract.CAP_PHONE, CapabilityClient.FILTER_REACHABLE).await()
        logi("capability '${EnrollmentContract.CAP_PHONE}' -> nodes=${info.nodes.map { "${it.displayName}/${it.id}(nearby=${it.isNearby})" }}")
        return info.nodes.firstOrNull { it.isNearby }?.id ?: info.nodes.firstOrNull()?.id
    }

    private companion object {
        const val PATH_RESULT = EnrollmentContract.PATH_RESULT
    }
}
