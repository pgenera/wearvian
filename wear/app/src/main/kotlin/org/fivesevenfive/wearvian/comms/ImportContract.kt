package org.fivesevenfive.wearvian.comms

import org.json.JSONObject

/**
 * Wire contract for the phone -> watch *key import* handoff (distinct from the
 * watch-initiated enrollment in [EnrollmentContract]).
 *
 * Here the companion app already holds an enrolled private key (negotiated by the
 * Home Assistant Rivian integration and scanned from a QR), resolves the vehicle
 * crypto material from the Rivian cloud, and PUSHES the whole bundle to the watch.
 * The watch stores the imported key + enrollment and acks. Must stay in sync with
 * the companion's `ImportContract`.
 */
object ImportContract {
    const val VERSION = 1

    const val PATH_KEY = "/wearvian/import/key"
    const val PATH_RESULT = "/wearvian/import/result"

    data class ImportedKey(
        val requestId: String,
        /** base64 of a PKCS#8 PEM P-256 private key (the HA storage format). */
        val privateKeyPemBase64: String,
        /** matching X9.62 uncompressed public point (hex). */
        val publicKeyHex: String,
        val userId: String,
        /** Registered with Rivian as a WATCH key vs a phone key (derived from keyDeviceSubtype). */
        val asWatch: Boolean,
        val vehicles: List<EnrollmentContract.VehicleResult>,
    )

    /** Parse the phone -> watch import payload. Throws on malformed/empty input. */
    fun parseKey(bytes: ByteArray): ImportedKey {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val priv = o.getString("privateKey")
        val pub = o.getString("publicKey")
        require(priv.isNotBlank() && pub.isNotBlank()) { "import payload missing key material" }
        val arr = o.optJSONArray("vehicles")
        val vehicles = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                add(
                    EnrollmentContract.VehicleResult(
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
        require(vehicles.isNotEmpty()) { "import payload has no vehicles" }
        return ImportedKey(
            requestId = o.optString("requestId"),
            privateKeyPemBase64 = priv,
            publicKeyHex = pub,
            userId = o.optString("userId"),
            asWatch = o.optBoolean("asWatch", false),
            vehicles = vehicles,
        )
    }

    fun ackOk(requestId: String, vin: String): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .put("status", "ok")
            .put("vin", vin)
            .toString().toByteArray(Charsets.UTF_8)

    fun ackError(requestId: String, error: String): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .put("status", "error")
            .put("error", error)
            .toString().toByteArray(Charsets.UTF_8)
}
