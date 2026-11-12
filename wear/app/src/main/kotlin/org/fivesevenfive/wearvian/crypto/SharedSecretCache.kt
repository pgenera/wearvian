package org.fivesevenfive.wearvian.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** The watch was locked when the secret was asked for: nothing was returned or computed. */
class WatchLockedException : IllegalStateException("watch locked — secret unavailable")

/**
 * Computes a value once per key on [scope] and keeps it until [clear]: concurrent callers share one
 * in-flight computation, a failure is not cached (the next caller recomputes), and a computation
 * that finishes after [clear] does not repopulate the cache. Pure, so it is JVM unit-tested.
 *
 * [isLocked] is read fresh on every [get] / [prefetch] (and again before a result is cached or
 * handed out): while it reads true the cache is cleared and nothing is returned or computed, so
 * no caller can obtain the value on a locked watch however stale its own lock view is.
 */
class SecretCache(private val scope: CoroutineScope, private val isLocked: () -> Boolean = { false }) {
    private val lock = Any()
    private var cached: Pair<String, ByteArray>? = null
    private var inFlight: Pair<String, Deferred<ByteArray>>? = null
    private var generation = 0

    /** Start computing [key]'s value now if it is neither cached nor already being computed.
     *  While locked it only clears (no throw: prefetch is fire-and-forget). */
    fun prefetch(key: String, compute: () -> ByteArray) {
        if (isLocked()) { clear(); return }
        synchronized(lock) {
            if (cached?.first == key) return // already cached: nothing to start, nothing to copy
            startLocked(key, compute)
        }
    }

    /**
     * [key]'s value (a copy the caller owns and should zero), computing it at most once across
     * callers. @throws WatchLockedException if the watch reads locked before or after the wait.
     */
    suspend fun get(key: String, compute: () -> ByteArray): ByteArray {
        if (isLocked()) { clear(); throw WatchLockedException() }
        val d = synchronized(lock) {
            // Copied under the lock: clear() zeroes the cached array in place.
            cached?.let { (k, v) -> if (k == key) return v.copyOf() }
            startLocked(key, compute)
        }
        val value = d.await() // shared by every waiter: never handed out or zeroed directly
        if (isLocked()) { clear(); throw WatchLockedException() }
        return value.copyOf()
    }

    /** Forget the value (zeroing it) and orphan any in-flight computation. */
    fun clear() = synchronized(lock) {
        generation++
        cached?.second?.fill(0)
        cached = null
        inFlight = null
    }

    private fun startLocked(key: String, compute: () -> ByteArray): Deferred<ByteArray> {
        inFlight?.let { (k, d) -> if (k == key && !d.isCompleted) return d }
        val gen = generation
        val d = scope.async {
            val value = compute()
            if (isLocked()) { value.fill(0); clear(); throw WatchLockedException() }
            synchronized(lock) {
                if (generation == gen) {
                    cached = key to value.copyOf() // its own copy, so clear() never zeroes a returned one
                    if (inFlight?.first == key) inFlight = null
                }
            }
            value
        }
        inFlight = key to d
        return d
    }
}

/**
 * The per-vehicle ECDH secret, kept in memory only while the watch is unlocked.
 *
 * The ECDH runs inside StrongBox and takes 0.5–2 s on the watch (measured in device logs), and the
 * one-shot command path used to pay that on every tap, after the BLE connect. The secret is static
 * for a given (watch key, vehicle key) pair, so it is computed once — started early (tile view,
 * alongside the connect, on unlock) — and reused while the watch stays unlocked.
 *
 * It is cleared (zeroed) as soon as the watch is seen locked, so a locked watch holds no usable
 * secret and the Keystore key's unlockedDeviceRequired stays the backstop for a new derivation:
 * PresenceService's lock monitor, the command paths and the tile clear it on a locked keyguard
 * reading, and [org.fivesevenfive.wearvian.WearvianApp] re-checks the keyguard after screen-off
 * ([LockSecretPolicy]) so it is cleared even with the key off. On unlock it is prefetched again so
 * the recompute overlaps the reconnect.
 *
 * Also call [clear] whenever the enrollment is reset or the Keystore key is deleted.
 */
object SharedSecretCache {
    /** Fresh keyguard read, installed by [org.fivesevenfive.wearvian.WearvianApp] at process start. */
    @Volatile var lockCheck: (() -> Boolean)? = null

    private val cache = SecretCache(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { lockCheck?.invoke() == true }

    fun prefetch(keyManager: KeyManager, vehiclePublicKeyHex: String) =
        cache.prefetch(vehiclePublicKeyHex) { keyManager.sharedSecret(vehiclePublicKeyHex) }

    /** @throws WatchLockedException while the watch is locked (the cache is cleared too). */
    suspend fun get(keyManager: KeyManager, vehiclePublicKeyHex: String): ByteArray =
        cache.get(vehiclePublicKeyHex) { keyManager.sharedSecret(vehiclePublicKeyHex) }

    fun clear() = cache.clear()
}
