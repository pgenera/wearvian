package org.fivesevenfive.wearvian.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name this watch is enrolled under (shown in the vehicle's and Rivian's key lists): the name
 * the user gave the watch if there is one, else its model. Only a label — the companion matches an
 * existing enrollment by public key, never by name.
 */
object DeviceName {
    const val MAX_LEN = 40
    const val FALLBACK = "Wear OS watch"

    /** Pure (unit-tested): first non-blank of [userName], [model]; trimmed and capped. */
    fun resolve(userName: String?, model: String?): String =
        listOf(userName, model).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(MAX_LEN) ?: FALLBACK

    /** `Settings.Global.DEVICE_NAME` needs no permission; a failed read falls back to the model. */
    fun read(context: Context): String = resolve(
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        Build.MODEL,
    )
}
