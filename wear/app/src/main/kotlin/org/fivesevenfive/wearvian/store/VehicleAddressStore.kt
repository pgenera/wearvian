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

    /**
     * The PRIMARY phone-key module's address, recorded at pairing once the module proved it is the
     * enrolled vehicle. Connecting by this (not by the bonded device NAME) can't pick a leftover
     * bond from an earlier enrollment or another vehicle. Null for installs paired before this.
     */
    var phoneKeyAddress: String?
        get() = prefs.getString(KEY_PK, null)
        set(value) { prefs.edit().putString(KEY_PK, value).apply() }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY = "addresses"
        const val KEY_PK = "phone_key_address"
    }
}
