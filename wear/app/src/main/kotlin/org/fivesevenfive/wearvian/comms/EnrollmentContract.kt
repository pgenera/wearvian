package org.fivesevenfive.wearvian.comms

import org.json.JSONObject

/**
 * Wire contract for the watch <-> phone Data Layer enrollment handoff. Must stay
 * in sync with the companion app's `EnrollmentContract` (contract documented in
 * docs/companion-enrollment-protocol.md). This is the watch side: it builds the
 * request and parses the result.
 */
object EnrollmentContract {
    const val VERSION = 1

    // Capabilities (each side advertises one via res/values/wear.xml).
    const val CAP_PHONE = "wearvian_companion_enrollment"
    const val CAP_WATCH = "wearvian_watch"

    // Message paths.
    const val PATH_REQUEST = "/wearvian/enroll/request"
    /** Legacy, best-effort result message (still sent by the companion for older watches). */
    const val PATH_RESULT = "/wearvian/enroll/result"
    /** Watch -> phone: the result for `requestId` has been saved; the phone may drop its copy. */
    const val PATH_ACK = "/wearvian/enroll/ack"

    /**
     * Durable result: the companion also puts the result as a DataItem at
     * `RESULT_ITEM_PREFIX + requestId`, which the Data Layer stores and syncs whenever the watch
     * is reachable, even if the watch app was not running or had stopped listening. The watch
     * deletes the item once the enrollment is saved. Payload bytes live under [KEY_PAYLOAD].
     */
    const val RESULT_ITEM_PREFIX = "/wearvian/enroll/result/"
    const val KEY_PAYLOAD = "payload"

    fun resultItemPath(requestId: String): String = RESULT_ITEM_PREFIX + requestId

    /** The request id a result DataItem path refers to, or null if [path] is not one. */
    fun requestIdFromItemPath(path: String?): String? =
        path?.takeIf { it.startsWith(RESULT_ITEM_PREFIX) }
            ?.removePrefix(RESULT_ITEM_PREFIX)
            ?.takeIf { it.isNotEmpty() && '/' !in it }

    /** Build the watch -> phone acknowledgement payload. */
    fun buildAck(requestId: String): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .toString()
            .toByteArray(Charsets.UTF_8)

    /** Parse an acknowledgement; null when it is malformed. */
    fun parseAck(bytes: ByteArray): String? =
        runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("requestId") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /** Build the watch -> phone enrollment request payload. */
    fun buildRequest(requestId: String, publicKeyHex: String, deviceName: String): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .put("publicKey", publicKeyHex)
            .put("deviceName", deviceName)
            .put("deviceType", "watch")
            .toString()
            .toByteArray(Charsets.UTF_8)

    data class VehicleResult(
        val vehicleId: String,
        val vin: String,
        val vasVehicleId: String,
        val vehiclePublicKey: String,
        val vasPhoneId: String,
        val identityId: String,
    )

    data class Result(
        val requestId: String,
        val status: String,
        val error: String?,
        val userId: String,
        val vehicles: List<VehicleResult>,
        /** True = registered with Rivian as a WATCH (no passive lock/unlock); false = phone (full
         *  proximity). Absent from old companions → defaults to false (phone). */
        val asWatch: Boolean = false,
    ) {
        val isOk: Boolean get() = status == "ok"
    }

    /** Parse the phone -> watch result payload. */
    fun parseResult(bytes: ByteArray): Result {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val status = o.optString("status")
        if (status != "ok") {
            return Result(
                requestId = o.optString("requestId"),
                status = status.ifEmpty { "error" },
                error = o.optString("error", "Enrollment failed"),
                userId = "",
                vehicles = emptyList(),
            )
        }
        val arr = o.optJSONArray("vehicles")
        val vehicles = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                add(
                    VehicleResult(
                        vehicleId = v.optString("vehicleId"),
                        vin = v.optString("vin"),
                        vasVehicleId = v.optString("vasVehicleId"),
                        vehiclePublicKey = v.optString("vehiclePublicKey"),
                        vasPhoneId = v.optString("vasPhoneId"),
                        identityId = v.optString("identityId"),
                    ),
                )
            }
        }
        return Result(
            requestId = o.optString("requestId"),
            status = status,
            error = null,
            userId = o.optString("userId"),
            vehicles = vehicles,
            asWatch = o.optBoolean("asWatch", false), // absent (old companion) → phone
        )
    }
}
