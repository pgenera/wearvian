package org.fivesevenfive.wearvian.service

import android.content.Context
import android.net.Uri
import android.provider.Settings

/**
 * Reads Wear OS bedtime mode from the two UNDOCUMENTED `Settings.Global` keys (see [BedtimePolicy]
 * for the rules and why UNKNOWN means "do nothing"). No permission is needed to read
 * `Settings.Global`; each read is still guarded, and a failure counts as unreadable.
 */
object BedtimeMode {
    /** Pixel Watch / stock Wear OS. */
    const val PIXEL_KEY = "bedtime_mode"

    /** Samsung Galaxy Watch. */
    const val SAMSUNG_KEY = "setting_bedtime_mode_running_state"

    /** Content URIs to observe for changes (valid even if the key doesn't exist yet). */
    fun uris(): List<Uri> = listOf(PIXEL_KEY, SAMSUNG_KEY).mapNotNull { runCatching { Settings.Global.getUriFor(it) }.getOrNull() }

    fun read(context: Context): BedtimeState {
        val resolver = context.contentResolver
        fun get(key: String): Int? = runCatching { Settings.Global.getInt(resolver, key, BedtimePolicy.ABSENT) }.getOrNull()
        return BedtimePolicy.state(pixel = get(PIXEL_KEY), samsung = get(SAMSUNG_KEY))
    }
}
