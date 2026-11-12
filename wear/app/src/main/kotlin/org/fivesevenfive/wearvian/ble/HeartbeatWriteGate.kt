package org.fivesevenfive.wearvian.ble

/**
 * Heartbeat write pacing for [VehicleSession], kept pure so it is JVM unit-tested.
 *
 * Android allows one GATT write in flight per client. When a with-response heartbeat write times
 * out, the original write is still pending; issuing the next beat then just gets
 * WRITE_REQUEST_BUSY back, and counting each of those as a stall turned one slow write into a
 * link cycle within ~2.7 s. Instead: while our own write is still pending, skip beats and keep
 * waiting on it, up to [stallMaxMs] from when it was issued; a BUSY rejection is not a stall of
 * ours either (only BUSY that persists for [stallMaxMs] is). Only real failures (a failed callback
 * status, another rejection) count toward [maxFailures] in a row.
 */
class HeartbeatWriteGate(
    private val stallMaxMs: Long = STALL_MAX_MS,
    private val maxFailures: Int = MAX_FAILURES,
) {
    /** State of the session's in-flight write, as the caller sees it before a beat. */
    enum class Pending { NONE, PENDING, DONE_OK, DONE_FAILED }
    enum class Step { WRITE, SKIP, CYCLE }
    enum class Result { OK, TIMED_OUT, BUSY, FAILED }

    private var pendingSinceMs: Long? = null
    private var busySinceMs: Long? = null
    var failures = 0
        private set

    /** Before a beat: write it, skip it (our earlier write is still pending), or cycle the link. */
    fun beforeBeat(nowMs: Long, pending: Pending): Step {
        val since = pendingSinceMs ?: return Step.WRITE // no timed-out write of ours outstanding
        return when (pending) {
            Pending.PENDING -> if (nowMs - since >= stallMaxMs) Step.CYCLE else Step.SKIP
            Pending.DONE_OK, Pending.NONE -> { pendingSinceMs = null; failures = 0; Step.WRITE }
            Pending.DONE_FAILED -> { pendingSinceMs = null; if (++failures >= maxFailures) Step.CYCLE else Step.WRITE }
        }
    }

    /** After issuing a beat at [startedMs]: true if the link should cycle. */
    fun after(result: Result, startedMs: Long, nowMs: Long): Boolean = when (result) {
        Result.OK -> { failures = 0; busySinceMs = null; false }
        Result.TIMED_OUT -> { pendingSinceMs = startedMs; busySinceMs = null; false }
        Result.BUSY -> {
            val since = busySinceMs ?: startedMs.also { busySinceMs = it }
            nowMs - since >= stallMaxMs
        }
        Result.FAILED -> { busySinceMs = null; ++failures >= maxFailures }
    }

    companion object {
        /** How long one write may stay pending (or BUSY persist) before the link is cycled. */
        const val STALL_MAX_MS = 6_000L
        const val MAX_FAILURES = 5
    }
}
