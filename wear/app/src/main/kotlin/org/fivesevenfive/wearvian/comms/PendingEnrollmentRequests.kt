package org.fivesevenfive.wearvian.comms

import android.content.Context
import android.content.SharedPreferences

/**
 * Request ids this watch has sent to the companion and not yet saved a result for, with the time
 * each was sent. Persisted so a result the companion stored as a DataItem can still be picked up
 * after the watch timed out, left the screen, or was killed. Request ids are random UUIDs and not
 * secret; no key material or identifiers are kept here.
 */
class PendingEnrollmentRequests(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wearvian_enroll_requests", Context.MODE_PRIVATE)

    fun add(requestId: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(PREFIX + requestId, nowMs).apply()
    }

    fun remove(requestId: String) {
        prefs.edit().remove(PREFIX + requestId).apply()
    }

    /** Ids still worth accepting a result for; expired ones are dropped from storage. */
    fun live(nowMs: Long = System.currentTimeMillis()): Set<String> {
        val all = prefs.all
            .filterKeys { it.startsWith(PREFIX) }
            .mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix(PREFIX) to it } }
            .toMap()
        val keep = liveIds(all, nowMs)
        val expired = all.keys - keep
        if (expired.isNotEmpty()) {
            prefs.edit().apply { expired.forEach { remove(PREFIX + it) } }.apply()
        }
        return keep
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFIX = "req:"

        /**
         * How long a sent request may still be answered. The companion can complete well after
         * the watch's 5-minute wait (the user finishes sign-in later); a day covers that without
         * letting a forgotten request enroll the watch weeks later.
         */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** Pure: the ids in [sentAtMs] that are not older than [MAX_AGE_MS] (or from the future). */
        fun liveIds(sentAtMs: Map<String, Long>, nowMs: Long): Set<String> =
            sentAtMs.filterValues { nowMs - it in 0..MAX_AGE_MS }.keys

        /**
         * Pure: the stored result to apply, if any — an ok result for one of our [liveIds] that
         * names at least one vehicle. Every request carried the same public key, so any one of
         * them is a valid enrollment of this watch.
         */
        fun pickStoredResult(
            results: List<EnrollmentContract.Result>,
            liveIds: Set<String>,
        ): EnrollmentContract.Result? =
            results.firstOrNull { it.isOk && it.requestId in liveIds && it.vehicles.isNotEmpty() }
    }
}
