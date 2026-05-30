package org.fivesevenfive.wearvian.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fivesevenfive.wearvian.protocol.RivianGql

/**
 * Direct, one-time calls to Rivian's GraphQL gateway during enrollment:
 * `getUserInfo` (to learn the vehicle's VAS ids + public key) and `EnrollPhone`
 * (to register this watch's public key). Request bodies and parsing come from
 * the JVM-tested [RivianGql]; this class only adds auth headers and does HTTP.
 *
 * Everything here is used exactly once during setup; day-to-day unlock/drive is
 * pure BLE and needs no network.
 */
class RivianCloud(
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val jsonMedia = "application/json".toMediaType()

    private fun baseRequest(tokens: SessionTokens, body: String): Request =
        Request.Builder()
            .url(RivianGql.GATEWAY_URL)
            // Headers mirror rivian-python-client BASE_HEADERS + auth tokens.
            .header("User-Agent", "RivianApp/707 CFNetwork/1237 Darwin/20.4.0")
            .header("Accept", "application/json")
            .header("Apollographql-Client-Name", "com.rivian.ios.consumer-apollo-ios")
            .header("Csrf-Token", tokens.csrfToken)
            .header("A-Sess", tokens.appSessionToken)
            .header("U-Sess", tokens.userSessionToken)
            .post(body.toRequestBody(jsonMedia))
            .build()

    private suspend fun post(tokens: SessionTokens, body: String): String =
        withContext(Dispatchers.IO) {
            http.newCall(baseRequest(tokens, body)).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "Rivian gateway HTTP ${resp.code}: $text" }
                text
            }
        }

    /** Returns the raw response (for enrolled-phone lookup) plus parsed user info. */
    suspend fun getUserInfo(tokens: SessionTokens): Pair<String, RivianGql.UserInfo> {
        val raw = post(tokens, RivianGql.getUserInfoBody())
        return raw to RivianGql.parseUserInfo(raw)
    }

    suspend fun enrollPhone(
        tokens: SessionTokens,
        userId: String,
        vehicleId: String,
        publicKeyHex: String,
        deviceType: String,
        deviceName: String,
    ): Boolean {
        val raw = post(
            tokens,
            RivianGql.enrollPhoneBody(userId, vehicleId, publicKeyHex, deviceType, deviceName),
        )
        return RivianGql.parseEnrollSuccess(raw)
    }
}
