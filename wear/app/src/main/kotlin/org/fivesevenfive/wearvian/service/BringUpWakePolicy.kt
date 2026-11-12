package org.fivesevenfive.wearvian.service

/**
 * When [PresenceService] takes its bounded "bring-up" wake lock, kept pure so it is JVM
 * unit-tested. In watch-key standby and in driving-doze no main wake lock is held, so the timers
 * that notice a dropped link and reconnect (and, for a watch key, the ECDH + connect + handshake
 * after a passive wake or an unlock) only run while the CPU happens to be awake. The bring-up wake
 * lock covers those windows; it is released once the PRIMARY link is UP, and can never be held
 * longer than [MAX_MS].
 */
object BringUpWakePolicy {
    const val MAX_MS = 30_000L

    /** Sessions are starting: take it unless the main wake lock is already held. */
    fun onSessionsStart(mainWakeLockHeld: Boolean): Boolean = !mainWakeLockHeld

    /**
     * The PRIMARY link changed from [prev] to [now]. Take it when an UP link dropped (seen as any
     * non-UP state after UP, since the state flow can conflate DOWN into the CONNECTING that
     * follows) while active with heartbeats paused (watch standby / driving-doze) and no main wake
     * lock. A connect attempt that fails (CONNECTING → DOWN) is not a drop, so an absent car does
     * not keep re-taking it.
     */
    fun onPrimaryChange(
        prev: PresenceStatus.Link?, now: PresenceStatus.Link?,
        mainWakeLockHeld: Boolean, active: Boolean, heartbeatsPaused: Boolean,
    ): Boolean =
        prev == PresenceStatus.Link.UP && now != PresenceStatus.Link.UP &&
            !mainWakeLockHeld && active && heartbeatsPaused

    /** Release once the PRIMARY is authenticated again. */
    fun done(now: PresenceStatus.Link?): Boolean = now == PresenceStatus.Link.UP
}
