package org.fivesevenfive.wearvian.store

import android.content.Context

/** User-facing app settings (plain prefs; nothing secret). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_settings", Context.MODE_PRIVATE)

    /**
     * Whether the user has the mobile key armed (operational intent, not a preference).
     * Distinct from "presence service currently running": when auto power-save drops the
     * service to passive, the key is still armed — the UI shows "Key passive", not "off".
     *
     * Defaults to false (no key ⇒ not armed): presence only ever auto-starts once bonded, and
     * enrollment writes this true explicitly, so the default is only read pre-key — where armed
     * would be meaningless.
     */
    var keyArmed: Boolean
        get() = prefs.getBoolean(KEY_KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_KEY_ARMED, value).apply()

    /**
     * Debug-only override: force the UI into R1T (truck) mode regardless of the enrolled VIN, so the
     * tailgate UI can be exercised on a non-R1T vehicle. Only togglable from the debug Settings page;
     * defaults OFF so production (no Settings page) is unaffected. See [VehicleModel].
     */
    var forceR1t: Boolean
        get() = prefs.getBoolean(KEY_FORCE_R1T, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_R1T, value).apply()

    /**
     * Persisted passive-idle mode. Honored ONLY on a near-instant restart — an app upgrade
     * (`MY_PACKAGE_REPLACED` → [org.fivesevenfive.wearvian.service.PresenceService.ACTION_UPGRADE_RESTORE]) —
     * so the key resumes passive instead of snapping back to ACTIVE (re-acquiring the wake lock + BLE
     * while parked nearby, the parked-idle battery win lost). Every OTHER start (app open, OS restart)
     * deliberately ignores this and comes up active: after a longer gap the car may have come and gone,
     * and a stale "parked nearby" would wrongly ignore its real return. Written by PresenceService on
     * each active↔passive transition (active clears it).
     */
    var passiveIdle: Boolean
        get() = prefs.getBoolean(KEY_PASSIVE_IDLE, false)
        set(value) = prefs.edit().putBoolean(KEY_PASSIVE_IDLE, value).apply()

    /**
     * The `seenDeparture` sub-state saved alongside [passiveIdle]: false = "parked nearby" (car still
     * in range — wait for it to leave before a return can wake us), true = the car had departed (a
     * return should wake). On the upgrade restore it's RESTORED, not recomputed: a fresh process has no
     * link, which recompute would read as "departed", arming a PK autoConnect that bounces straight
     * back to active next to the parked car — exactly the "parked-nearby passive didn't persist" symptom.
     */
    var passiveSeenDeparture: Boolean
        get() = prefs.getBoolean(KEY_PASSIVE_SEEN_DEPARTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_PASSIVE_SEEN_DEPARTURE, value).apply()

    /**
     * The nonce of the last tile tap [org.fivesevenfive.wearvian.tile.KeyTileService] has already
     * acted on. Each tile render stamps its command clickables with a fresh nonce; the tile only
     * dispatches when the tapped clickable's nonce differs from this. Wear re-invokes onTileRequest
     * for refreshes (scroll-into-view, our TileRefresher requestUpdate on 0x1c changes) and
     * re-delivers the LAST click's state, so without this a stale lastClickableId re-fires the last
     * command on every refresh — e.g. a lingering "unlock" firing as the user reaches for "lock".
     * Persisted (not just in-process) so a tile-triggered process restart can't replay a stale tap.
     * [Int.MIN_VALUE] = "nothing handled yet" (no live render nonce will collide with it in practice).
     */
    var lastTileClickNonce: Int
        get() = prefs.getInt(KEY_LAST_TILE_CLICK_NONCE, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_LAST_TILE_CLICK_NONCE, value).apply()

    private companion object {
        const val KEY_KEY_ARMED = "key_armed"
        const val KEY_FORCE_R1T = "force_r1t"
        const val KEY_PASSIVE_IDLE = "passive_idle"
        const val KEY_PASSIVE_SEEN_DEPARTURE = "passive_seen_departure"
        const val KEY_LAST_TILE_CLICK_NONCE = "last_tile_click_nonce"
    }
}
