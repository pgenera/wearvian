package org.fivesevenfive.wearvian

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.crypto.LockSecretPolicy
import org.fivesevenfive.wearvian.crypto.SharedSecretCache
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.util.CrashLog
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Process entry point. Installs the persistent crash recorder before any other component
 * runs so a background crash in a service/BLE/tile callback leaves a durable trace on disk
 * (debugging builds only — see [CrashLog]).
 */
class WearvianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Before anything can ask for the secret: the cache reads the keyguard fresh on every use.
        val keyguard = getSystemService(KeyguardManager::class.java)
        SharedSecretCache.lockCheck = { keyguard?.isDeviceLocked == true }
        // Open the encrypted enrollment store (Keystore IPC + keyset decrypt) off the main thread, so
        // the tile / service / UI find it cached instead of each paying that cost on their thread.
        Thread({ EnrollmentStore.warmUp(this) }, "enrollment-warmup").start()
        // Surface a previous crash so it's visible in the log/debug screen the next time the app
        // runs — not just silently on disk. (No-op on production, where nothing is recorded.)
        CrashLog.reports(this).firstOrNull()?.let {
            DebugLog.add("⚠ prior crash recorded: ${it.name} (pull ${it.path})")
        }
        watchLockForSecret(keyguard)
    }

    /**
     * Clear the cached ECDH secret when the watch locks, whether or not PresenceService (which has
     * its own lock monitor) is running, and prefetch it again on unlock. See [LockSecretPolicy].
     */
    private fun watchLockForSecret(keyguard: KeyguardManager?) {
        val handler = Handler(Looper.getMainLooper())
        val policy = LockSecretPolicy(
            isLocked = { keyguard?.isDeviceLocked == true },
            clear = {
                SharedSecretCache.clear()
                DebugLog.add("secret: watch locked — cached secret cleared")
            },
            prefetch = { prefetchSecret(this) },
            schedule = { delayMs, action ->
                val r = Runnable(action)
                handler.postDelayed(r, delayMs)
                LockSecretPolicy.Cancellable { handler.removeCallbacks(r) }
            },
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> policy.onScreenOff()
                    Intent.ACTION_SCREEN_ON -> policy.onScreenOn()
                    Intent.ACTION_USER_PRESENT -> policy.onUserPresent()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // System broadcasts are still delivered to a not-exported receiver.
        runCatching { registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
            .onFailure { DebugLog.add("secret: lock receiver not registered (${it.javaClass.simpleName})") }
    }

    companion object {
        /** Start recomputing the secret in the background, if enrolled (off the main thread: the
         *  enrollment store load can hit the Keystore). */
        fun prefetchSecret(context: Context) {
            val app = context.applicationContext
            Thread({
                runCatching {
                    val enrollment = EnrollmentStore(app).load() ?: return@runCatching
                    SharedSecretCache.prefetch(KeyManager(), enrollment.vehiclePublicKey)
                }
            }, "secret-prefetch").start()
        }
    }
}
