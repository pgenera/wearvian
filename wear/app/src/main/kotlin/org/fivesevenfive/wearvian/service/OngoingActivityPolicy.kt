package org.fivesevenfive.wearvian.service

/**
 * Whether to decorate the presence notification with a Wear OS OngoingActivity (the watch-face key
 * icon). On Wear OS 7 and newer (Android 17, API 37) the watch face renders it as a wide curved
 * banner that overlaps bottom complications, so there we post the plain foreground-service
 * notification instead. Older watches keep the icon.
 */
object OngoingActivityPolicy {
    /** First API level of Wear OS 7 (Android 17). Literal: compileSdk predates a named constant. */
    const val WEAR_OS_7_SDK_INT = 37

    fun shouldDecorate(sdkInt: Int): Boolean = sdkInt < WEAR_OS_7_SDK_INT
}
