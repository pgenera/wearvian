package org.fivesevenfive.wearvian.ble

/**
 * Bookkeeping for the tile's link pre-warm ([ActiveCommandManager.prewarm]), kept pure so it is
 * JVM unit-tested. Tile enter/leave are recorded synchronously, as they arrive, so the background
 * pre-warm can check — before connecting and again before keeping the link — that it is still
 * wanted, whatever order the background work runs in.
 *
 * A pre-warm belongs to the *visit* (enter→leave) that started it, identified by [generation]: a
 * leave, or a leave followed by a new enter, makes an older visit's pre-warm unwanted. A command
 * waiting for the link keeps a pre-warm wanted even after the tile leaves view (tap, then wrist
 * down: finish the connect the tap is waiting on rather than start it again cold).
 */
class PrewarmGate {
    private var visible = false
    private var generation = 0
    private var waitingCommands = 0
    /** Deadlines ([OneShotPolicy]) of the taps waiting, one entry per [commandWaiting] that gave one. */
    private val waitingDeadlines = mutableListOf<Long>()

    /** Tile came into view; returns the visit id. A repeated enter keeps the current visit. */
    @Synchronized fun entered(): Int {
        if (!visible) { visible = true; generation++ }
        return generation
    }

    /** Tile left view. Returns true if an in-flight pre-warm should be cancelled now (nobody waits on it). */
    @Synchronized fun left(): Boolean {
        if (visible) { visible = false; generation++ }
        return waitingCommands == 0
    }

    /** Whether a pre-warm for [visit] should still connect / keep its link. */
    @Synchronized fun wanted(visit: Int, serviceRunning: Boolean): Boolean =
        !serviceRunning && ((visible && generation == visit) || waitingCommands > 0)

    /** Whether [visit] is the visit in view now (a second enter for it needs no second pre-warm). */
    @Synchronized fun isCurrent(visit: Int): Boolean = visible && generation == visit

    /** A tap is queued for the link; [deadlineMs] (if any) is when it gives up. */
    @Synchronized fun commandWaiting(deadlineMs: Long? = null) {
        waitingCommands++
        deadlineMs?.let { waitingDeadlines += it }
    }

    /** That tap finished; pass the same [deadlineMs] it registered with. */
    @Synchronized fun commandDone(deadlineMs: Long? = null) {
        if (waitingCommands > 0) waitingCommands--
        deadlineMs?.let { waitingDeadlines.remove(it) }
    }

    /** The latest deadline among the waiting taps, or null if none is waiting with one. */
    @Synchronized fun latestWaitingDeadline(): Long? = waitingDeadlines.maxOrNull()

    companion object {
        /**
         * A held link is usable only within [holdMs] of opening, by the monotonic clock that keeps
         * counting while the CPU sleeps — a coroutine delay doesn't, so the expiry timer alone can't
         * be trusted to bound it.
         */
        fun fresh(openedAtMs: Long, nowMs: Long, holdMs: Long): Boolean = nowMs - openedAtMs in 0..holdMs
    }
}
