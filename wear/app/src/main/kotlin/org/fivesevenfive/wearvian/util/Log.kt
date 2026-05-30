package org.fivesevenfive.wearvian.util

import android.util.Log

/**
 * Tiny logging helpers so every wearvian log line shares one tag and is easy to
 * filter with `adb logcat -s wearvian`. Verbose by design — this is a personal
 * debugging build.
 */
const val TAG = "wearvian"

fun logd(msg: String) {
    Log.d(TAG, msg)
}

fun logi(msg: String) {
    Log.i(TAG, msg)
}

fun logw(msg: String, t: Throwable? = null) {
    if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
}

fun loge(msg: String, t: Throwable? = null) {
    if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
}

/** Hex-encode for logging non-secret protocol bytes (ids, nonces, MACs). */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
