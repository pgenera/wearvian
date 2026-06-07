package org.fivesevenfive.wearvian.util

/**
 * Whether the app UI ([org.fivesevenfive.wearvian.ui.MainActivity]) is in the foreground.
 * Set from the activity's start/stop. Used to skip tile refreshes while the app is up — the
 * tile is hidden behind the app then, and the app shows the live state itself.
 */
object AppForeground {
    @Volatile var inForeground: Boolean = false
}
