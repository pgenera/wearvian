package org.fivesevenfive.wearvian.comms

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
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

    private val pending = PendingEnrollmentRequests(context)

    /**
     * Send an enrollment request to the companion and suspend until the result
     * arrives (or [timeoutMs] elapses). Throws [NoCompanionException] if no phone
     * advertising the companion capability is reachable.
     *
     * The result can arrive two ways: the durable DataItem at
     * [EnrollmentContract.resultItemPath] (stored by the Data Layer until we delete it), or the
     * legacy one-shot message on [EnrollmentContract.PATH_RESULT] from older companions. Whichever
     * comes first wins. The request id is persisted so [findStoredResult] can still pick the
     * result up if this wait ends first. After saving an ok result, call [acknowledge].
     */
    suspend fun requestEnrollment(
        publicKeyHex: String,
        deviceName: String,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): EnrollmentContract.Result {
        val messageClient = Wearable.getMessageClient(context)
        val dataClient = Wearable.getDataClient(context)
        val capabilityClient = Wearable.getCapabilityClient(context)

        val requestId = UUID.randomUUID().toString()
        logi("requestEnrollment: publicKeyLen=${publicKeyHex.length} timeoutMs=$timeoutMs")
        val deferred = CompletableDeferred<EnrollmentContract.Result>()

        fun offer(source: String, bytes: ByteArray) {
            runCatching { EnrollmentContract.parseResult(bytes) }
                .onSuccess {
                    logi("result via $source: status=${it.status} vehicles=${it.vehicles.size}")
                    if (it.requestId == requestId && !deferred.isCompleted) deferred.complete(it)
                    else logw("result via $source ignored (other request or already completed)")
                }
                .onFailure { logw("failed to parse result payload from $source", it) }
        }

        val messageListener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path != EnrollmentContract.PATH_RESULT) return@OnMessageReceivedListener
            offer("message", event.data)
        }
        val dataListener = DataClient.OnDataChangedListener { events ->
            events.forEach { ev ->
                if (ev.type != DataEvent.TYPE_CHANGED) return@forEach
                val item = ev.dataItem
                if (EnrollmentContract.requestIdFromItemPath(item.uri.path) == null) return@forEach
                payloadOf(item)?.let { offer("data item", it) }
            }
        }
        pending.add(requestId)
        messageClient.addListener(messageListener).await()
        dataClient.addListener(dataListener).await()
        logi("result listeners registered")
        try {
            val targets = resolveTargetNodes(capabilityClient)
            if (targets.isEmpty()) {
                logw("no connected nodes at all — throwing NoCompanionException")
                throw NoCompanionException()
            }
            val payload = EnrollmentContract.buildRequest(requestId, publicKeyHex, deviceName)
            targets.forEach { node ->
                logi("sending request to node=$node path=${EnrollmentContract.PATH_REQUEST} bytes=${payload.size}")
                runCatching { messageClient.sendMessage(node, EnrollmentContract.PATH_REQUEST, payload).await() }
                    .onFailure { logw("sendMessage to $node failed", it) }
            }
            logi("request sent to ${targets.size} node(s); awaiting result (up to ${timeoutMs}ms)")
            // The item may already be stored if the phone answered before our listener saw it.
            findStoredResultFor(setOf(requestId))?.let { offer("stored item", it) }
            val result = withTimeout(timeoutMs) { deferred.await() }
            logi("enrollment result received: status=${result.status}")
            if (!result.isOk) pending.remove(requestId)
            return result
        } finally {
            messageClient.removeListener(messageListener)
            dataClient.removeListener(dataListener)
            logi("result listeners removed")
        }
    }

    /**
     * A result the companion stored for one of our outstanding requests while we were not
     * listening (timed out, screen left, app killed), or null. Never throws.
     */
    suspend fun findStoredResult(): EnrollmentContract.Result? {
        val live = pending.live()
        if (live.isEmpty()) return null
        val results = runCatching {
            readResultItems().mapNotNull { (id, bytes) ->
                if (id !in live) null
                else runCatching { EnrollmentContract.parseResult(bytes) }.getOrNull()
            }
        }.onFailure { logw("findStoredResult: reading data items failed", it) }
            .getOrDefault(emptyList())
        return PendingEnrollmentRequests.pickStoredResult(results, live)
            .also { logi("findStoredResult: outstanding=${live.size} stored=${results.size} usable=${it != null}") }
    }

    private suspend fun findStoredResultFor(ids: Set<String>): ByteArray? =
        runCatching { readResultItems().firstOrNull { it.first in ids }?.second }
            .onFailure { logw("reading stored result failed", it) }
            .getOrNull()

    /**
     * The enrollment from [requestId] is saved: delete every stored result for our outstanding
     * requests (they all carry the same key, and none should be applied again), tell the phone,
     * and forget the outstanding ids. Best-effort; never throws.
     */
    suspend fun acknowledge(requestId: String) {
        val ids = pending.live() + requestId
        runCatching {
            val dataClient = Wearable.getDataClient(context)
            val uris = resultItemUris().filter { it.second in ids }.map { it.first }
            uris.forEach { dataClient.deleteDataItems(it).await() }
            logi("acknowledge: deleted ${uris.size} stored result item(s)")
        }.onFailure { logw("acknowledge: deleting stored results failed", it) }
        pending.clear()
        runCatching {
            val messageClient = Wearable.getMessageClient(context)
            val payload = EnrollmentContract.buildAck(requestId)
            val nodes = Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            nodes.forEach { node ->
                runCatching { messageClient.sendMessage(node, EnrollmentContract.PATH_ACK, payload).await() }
            }
            logi("acknowledge: ack sent to ${nodes.size} node(s)")
        }.onFailure { logw("acknowledge: sending ack failed", it) }
    }

    /** Forget all outstanding requests (reset / wipe), so a late result can't re-enroll the watch. */
    fun forgetOutstanding() {
        pending.clear()
    }

    /** (request id, payload) for every stored result item visible on this node. */
    private suspend fun readResultItems(): List<Pair<String, ByteArray>> {
        val buffer = Wearable.getDataClient(context).dataItems.await()
        try {
            return buffer.mapNotNull { item ->
                val id = EnrollmentContract.requestIdFromItemPath(item.uri.path) ?: return@mapNotNull null
                payloadOf(item)?.let { id to it }
            }
        } finally {
            buffer.release()
        }
    }

    private suspend fun resultItemUris(): List<Pair<Uri, String>> {
        val buffer = Wearable.getDataClient(context).dataItems.await()
        try {
            return buffer.mapNotNull { item ->
                EnrollmentContract.requestIdFromItemPath(item.uri.path)?.let { item.uri to it }
            }
        } finally {
            buffer.release()
        }
    }

    private fun payloadOf(item: DataItem): ByteArray? =
        runCatching { DataMapItem.fromDataItem(item).dataMap.getByteArray(EnrollmentContract.KEY_PAYLOAD) }
            .getOrNull()

    /**
     * Prefer a node that advertises the companion capability; if none does
     * (capability sync is flaky right after sideloading, especially over the
     * cloud relay), fall back to every connected node. The companion's listener
     * service fires on whichever node has the app; others ignore the path.
     */
    private suspend fun resolveTargetNodes(cc: CapabilityClient): List<String> {
        findCompanionNode(cc)?.let {
            logi("using capability-advertised node=$it")
            return listOf(it)
        }
        val nodes = Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
        logi("capability empty; falling back to all connected nodes=$nodes")
        return nodes
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
}
