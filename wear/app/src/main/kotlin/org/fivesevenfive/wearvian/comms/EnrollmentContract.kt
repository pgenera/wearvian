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
    const val PATH_RESULT = "/wearvian/enroll/result"

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
