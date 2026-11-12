package org.fivesevenfive.wearvian.ble

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.fivesevenfive.wearvian.util.DebugLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide queue of active commands waiting to ride the *live* presence session.
 *
 * Why this exists (decoded 2026-06-06, docs/passive-entry-protocol.md): closure
 * commands — charge-port, windows, liftgate — are gated on the vehicle considering
 * the phone *present* via the sustained heartbeat+ranging session. A one-shot
 * connect→command (see [ActiveCommandManager]) is enough for lock/unlock but those
 * gated commands get acked-then-dropped. So when [org.fivesevenfive.wearvian.service.PresenceService]
 * is running, the UI/Tile enqueue here instead, and the PRIMARY [VehicleSession]
 * drains the queue inside its heartbeat loop — sending each command frame with the
 * session's *running* counter (a fresh one-shot's `counter=0` would look stale
 * mid-session).
 *
 * Only the PRIMARY phone-key session drains the bus; sensor sessions ignore it.
 *
 * Every command carries an outcome: the drainer reports [markSent] after the frame write, and
 * a command that goes stale, is cleared on teardown, or is never picked up reports `false`, so
 * the caller can [awaitOutcome] and tell the user instead of the tap silently doing nothing.
 */
object CommandBus {
    /** Max age of a queued command before [poll] discards it as stale instead of firing late. */
    const val COMMAND_TTL_MS = 5_000L

    /** Monotonic clock in ms; injectable so JVM unit tests can advance time without Robolectric. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Extra wait past [COMMAND_TTL_MS] for the drainer to pick a command up before giving up on it. */
    const val PICKUP_GRACE_MS = 1_000L

    /** Once picked up, how long to wait for the frame write to finish (the session's write timeout + margin). */
    const val WRITE_WAIT_MS = 6_000L

    data class Command(
        val code: Int,
        val label: String,
        val submittedAtMs: Long = clock(),
    ) {
        // Not part of equals/hashCode (body properties): identity of the queued item, not its value.
        private val state = AtomicInteger(PENDING)
        internal val outcome = CompletableDeferred<Boolean>()

        /** Drainer takes ownership; false if the waiter already gave up on it (so it must not be sent). */
        internal fun claim(): Boolean = state.compareAndSet(PENDING, CLAIMED)

        /** Give up on an unsent command; false if the drainer already claimed it. */
        internal fun expire(): Boolean =
            state.compareAndSet(PENDING, EXPIRED).also { if (it) outcome.complete(false) }

        private companion object {
            const val PENDING = 0
            const val CLAIMED = 1
            const val EXPIRED = 2
        }
    }

    // Unlimited + non-blocking: a tap should never block the UI, and the PRIMARY
    // session drains at heartbeat cadence (~300 ms), so the queue stays tiny.
    private val queue = Channel<Command>(Channel.UNLIMITED)

    /** elapsedRealtime() of the last submit — a "manual action" signal for watch-mode burst presence
     *  (the heartbeat loop skips draining while paused, so a tap has to wake the burst to be sent). */
    @Volatile var lastSubmitMs = 0L
        private set

    /**
     * Record a manual command sent outside the queue (a successful one-shot), so a watch key bursts
     * presence once its service is active — the usual return to the car is a one-shot unlock from the
     * tile, and drive-enable needs the heartbeats that the burst starts.
     */
    fun noteManual() {
        lastSubmitMs = clock()
    }

    /** Enqueue a command for the live PRIMARY session to send on its next heartbeat tick. */
    fun submit(code: Int, label: String): Command {
        DebugLog.add("cmdbus: queued $label (0x%04x)".format(code))
        val now = clock()
        lastSubmitMs = now
        val cmd = Command(code, label, now)
        queue.trySend(cmd)
        ReconnectHurry.hurry() // a session waiting out a backoff should come up for this now
        return cmd
    }

    /** Report the result of writing a claimed command's frame (called by the PRIMARY session). */
    fun markSent(cmd: Command, ok: Boolean) {
        cmd.outcome.complete(ok)
    }

    /**
     * Wait for [cmd]'s outcome: true once its frame was written on the live session, false if it
     * failed, went stale, was cleared, or no drainer picked it up within [pickupMs]. A command that
     * was picked up just before the deadline gets [writeMs] more to finish its write.
     */
    suspend fun awaitOutcome(
        cmd: Command,
        pickupMs: Long = COMMAND_TTL_MS + PICKUP_GRACE_MS,
        writeMs: Long = WRITE_WAIT_MS,
    ): Boolean {
        withTimeoutOrNull(pickupMs) { cmd.outcome.await() }?.let { return it }
        if (cmd.expire()) {
            DebugLog.add("cmdbus: ${cmd.label} not picked up — no live session")
            return false
        }
        return withTimeoutOrNull(writeMs) { cmd.outcome.await() } ?: false
    }

    /**
     * Non-blocking dequeue for the PRIMARY session loop; null when nothing fresh is pending.
     * Commands older than [COMMAND_TTL_MS] are discarded so a tap during an outage/pause never fires late.
     */
    fun poll(): Command? {
        val now = clock()
        while (true) {
            val cmd = queue.tryReceive().getOrNull() ?: return null
            val ageMs = now - cmd.submittedAtMs
            if (ageMs > COMMAND_TTL_MS) {
                cmd.expire()
                DebugLog.add("cmdbus: dropped stale ${cmd.label} (age=${ageMs}ms)")
                continue
            }
            if (!cmd.claim()) continue // the tapper already gave up on it — don't fire it late
            return cmd
        }
    }

    /** Discard all queued commands (called when the PRIMARY session tears down). */
    fun clear() {
        var dropped = 0
        while (true) {
            val cmd = queue.tryReceive().getOrNull() ?: break
            cmd.expire()
            dropped++
        }
        if (dropped > 0) DebugLog.add("cmdbus: cleared $dropped pending")
    }
}
