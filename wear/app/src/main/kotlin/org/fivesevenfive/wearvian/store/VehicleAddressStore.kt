package org.fivesevenfive.wearvian.store

import android.content.Context

/**
 * Remembers the vehicle's BLE MAC addresses (PRIMARY phone-key module + sensors)
 * learned during active presence sessions. These seed the offloaded proximity-wake
 * scan filters so the OS can wake us when any of them comes into range. Not secret
 * (just device addresses), so plain prefs. See docs/proximity-wake.md.
 */
class VehicleAddressStore(context: Context) {

    private val prefs = context.getSharedPreferences("wearvian_vehicle_addrs", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    /** Add an address; no-op if already known. */
    fun add(address: String) {
        val cur = load().toMutableSet()
        if (cur.add(address)) prefs.edit().putStringSet(KEY, cur).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY = "addresses"
    }
}
