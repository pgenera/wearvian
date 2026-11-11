package org.fivesevenfive.wearvian.ble

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import org.fivesevenfive.wearvian.util.TtlCache

/**
 * Shared, cheap view of "is the watch locked" for the per-beat anti-theft gate in
 * [VehicleSession]. `KeyguardManager.isDeviceLocked` is a binder call; with up to five
 * links at ~3.3 beats/s each that was ~17 binder calls a second for a value that changes
 * only when the watch comes off the wrist. All sessions now share one cached read that is
 * at most [TTL_MS] old, so a lock still stops every link's heartbeats within one TTL
 * (plus one beat), and PresenceService's own lock monitor tears the BLE down shortly after.
 */
object WatchLock {
    /**
     * Staleness bound for the lock read. Kept well under PresenceService's 2 s lock poll and
     * short next to how long a thief needs to reach a door, so the anti-theft latency is
     * essentially unchanged while the binder rate drops from ~17/s to ~1/s.
     */
    const val TTL_MS = 1_000L

    @Volatile private var cache: TtlCache<Boolean>? = null

    fun isLocked(context: Context): Boolean = (cache ?: create(context)).get()

    @Synchronized
    private fun create(context: Context): TtlCache<Boolean> = cache ?: run {
        val keyguard = context.applicationContext.getSystemService(KeyguardManager::class.java)
        TtlCache(TTL_MS, SystemClock::elapsedRealtime) { keyguard?.isDeviceLocked == true }
            .also { cache = it }
    }
}
