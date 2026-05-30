package org.fivesevenfive.wearvian.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Session tokens handed back by the auth broker (and originally minted by Rivian). */
data class SessionTokens(
    val csrfToken: String,
    val appSessionToken: String,
    val userSessionToken: String,
)

data class BrokerSession(val nonce: String, val browserUrl: String, val pollUrl: String)

/**
 * Talks to the standalone auth broker (`auth-server/`):
 *   1. [createSession] -> nonce + a URL to render as a QR code
 *   2. user authenticates in a browser
 *   3. [awaitTokens] polls until the broker has the tokens, then returns them
 *
 * The broker only ever sees the user's Rivian credentials; the watch's private
 * key is never involved.
 */
class AuthBrokerClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun createSession(brokerBaseUrl: String): BrokerSession = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(brokerBaseUrl.trimEnd('/') + "/session")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Broker /session failed: HTTP ${resp.code}" }
            val json = JSONObject(body)
            BrokerSession(
                nonce = json.getString("nonce"),
                browserUrl = json.getString("browser_url"),
                pollUrl = json.getString("poll_url"),
            )
        }
    }

    /** Poll the broker until the browser sign-in completes (or we time out). */
    suspend fun awaitTokens(
        pollUrl: String,
        timeoutMs: Long = 5 * 60_000,
        intervalMs: Long = 2_000,
    ): SessionTokens = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val req = Request.Builder().url(pollUrl).get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val j = JSONObject(body)
                        return@withContext SessionTokens(
                            csrfToken = j.getString("csrfToken"),
                            appSessionToken = j.getString("appSessionToken"),
                            userSessionToken = j.getString("userSessionToken"),
                        )
                    }
                    202 -> Unit // still pending
                    else -> error("Broker poll failed: HTTP ${resp.code} $body")
                }
            }
            delay(intervalMs)
        }
        error("Timed out waiting for browser sign-in")
    }
}
