package org.fivesevenfive.wearvian.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Computes a value once per key on [scope] and keeps it until [clear]: concurrent callers share one
 * in-flight computation, a failure is not cached (the next caller recomputes), and a computation
 * that finishes after [clear] does not repopulate the cache. Pure, so it is JVM unit-tested.
 */
class SecretCache(private val scope: CoroutineScope) {
    private val lock = Any()
    private var cached: Pair<String, ByteArray>? = null
    private var inFlight: Pair<String, Deferred<ByteArray>>? = null
    private var generation = 0

    /** Start computing [key]'s value now if it is neither cached nor already being computed. */
    fun prefetch(key: String, compute: () -> ByteArray) {
        deferredFor(key, compute)
    }

    /** [key]'s value (a copy the caller may keep), computing it at most once across callers. */
    suspend fun get(key: String, compute: () -> ByteArray): ByteArray = deferredFor(key, compute).await().copyOf()

    /** Forget the value (zeroing it) and orphan any in-flight computation. */
    fun clear() = synchronized(lock) {
        generation++
        cached?.second?.fill(0)
        cached = null
        inFlight = null
    }

    private fun deferredFor(key: String, compute: () -> ByteArray): Deferred<ByteArray> = synchronized(lock) {
        // Copied under the lock: clear() zeroes the cached array in place.
        cached?.let { (k, v) -> if (k == key) return@synchronized CompletableDeferred(v.copyOf()) }
        inFlight?.let { (k, d) -> if (k == key && !d.isCompleted) return@synchronized d }
        val gen = generation
        val d = scope.async {
            val value = compute()
            synchronized(lock) {
                if (generation == gen) {
                    cached = key to value.copyOf() // its own copy, so clear() never zeroes a returned one
                    if (inFlight?.first == key) inFlight = null
                }
            }
            value
        }
        inFlight = key to d
        d
    }
}

/**
 * The per-vehicle ECDH secret, kept in memory for the life of the process.
 *
 * The ECDH runs inside StrongBox and takes 0.5–2 s on the watch (measured in device logs), and the
 * one-shot command path used to pay that on every tap, after the BLE connect. The secret is static
 * for a given (watch key, vehicle key) pair, so it is computed once — started early (process start,
 * tile view, alongside the connect) — and reused. Trade-off, accepted: the secret lives in app memory
 * for the process lifetime, as it already did for the whole of a presence session. Commands are still
 * blocked while the watch is locked, and the Keystore key itself (unlockedDeviceRequired) still can't
 * be used locked, so a first computation only happens while the watch is unlocked.
 *
 * Call [clear] whenever the enrollment is reset or the Keystore key is deleted.
 */
object SharedSecretCache {
    private val cache = SecretCache(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    fun prefetch(keyManager: KeyManager, vehiclePublicKeyHex: String) =
        cache.prefetch(vehiclePublicKeyHex) { keyManager.sharedSecret(vehiclePublicKeyHex) }

    suspend fun get(keyManager: KeyManager, vehiclePublicKeyHex: String): ByteArray =
        cache.get(vehiclePublicKeyHex) { keyManager.sharedSecret(vehiclePublicKeyHex) }

    fun clear() = cache.clear()
}
