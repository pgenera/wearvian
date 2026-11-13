package org.fivesevenfive.wearvian.ble

/**
 * Deadline and retry rules for a one-shot command ([ActiveCommandManager.sendCommand]), kept pure so
 * they are JVM unit-tested. All times are `SystemClock.elapsedRealtime()` milliseconds, the
 * monotonic clock that keeps counting while the CPU sleeps (coroutine timers don't).
 *
 * The live queue already drops a command after [CommandBus.COMMAND_TTL_MS] so a tap never fires
 * late; this is the one-shot equivalent. A tap that has not had its frame written within
 * [DEADLINE_MS] of the tap is abandoned, never written later.
 */
object OneShotPolicy {
    /**
     * Tap → command-frame write. Deliberately generous ("sometimes tile taps take a while"): a first
     * connect to a dormant car, which advertises slowly, can legitimately take up to Android's own
     * ~30 s direct-connect limit, and the handshake after the connect adds 1–2 s. 35 s sits above
     * both, so a connect that lands just before the stack gives up still has time to authenticate
     * and send: every tap that would have succeeded on its first attempt before this limit existed
     * still does. A hopeless connect is ended by the stack's own ~30 s failure (a slow failure, so
     * NOT_REACHABLE and no retry); this deadline only bounds what is left. What it removes is the
     * second ~30 s attempt after a slow failure and the unbounded late firing: the worst case is a
     * command about 35 s after the tap instead of about a minute.
     */
    const val DEADLINE_MS = 35_000L

    /**
     * A connect that fails faster than this (the transient GATT status-133 kind) is worth one quick
     * retry; one that fails slower means the car wasn't reachable for that whole time.
     */
    const val QUICK_CONNECT_FAILURE_MS = 5_000L

    /** A retry needs at least this much of the deadline left (after its pause) to be worth starting. */
    const val MIN_RETRY_WINDOW_MS = 3_000L

    fun deadline(tapAtMs: Long): Long = tapAtMs + DEADLINE_MS

    fun remaining(deadlineMs: Long, nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0)

    fun expired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs

    /** How to report a connect that failed (stack said disconnected) before it ever connected. */
    fun connectFailure(startedMs: Long, failedMs: Long): CommandFailure =
        if (failedMs - startedMs < QUICK_CONNECT_FAILURE_MS) CommandFailure.SEND_FAILED
        else CommandFailure.NOT_REACHABLE

    /** Whether to retry after a first attempt failed with [failure], pausing [pauseMs] first. */
    fun shouldRetry(failure: CommandFailure, deadlineMs: Long, nowMs: Long, pauseMs: Long): Boolean =
        failure.retryable && remaining(deadlineMs, nowMs) - pauseMs >= MIN_RETRY_WINDOW_MS

    /**
     * Until when a tile pre-warm's connect keeps waiting. Normally its own window
     * ([ownUntilMs]); while a tap is queued behind it, until that tap's deadline instead, so the
     * pre-warm's connect *is* the tap's attempt: a tap that arrives during a pre-warm is not cut
     * short (nor made to restart its connect) because the pre-warm gave up first.
     */
    fun prewarmConnectUntil(ownUntilMs: Long, latestWaitingTapDeadlineMs: Long?): Long =
        maxOf(ownUntilMs, latestWaitingTapDeadlineMs ?: Long.MIN_VALUE)
}
