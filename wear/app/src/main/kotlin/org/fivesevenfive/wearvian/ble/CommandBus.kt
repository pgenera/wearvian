package org.fivesevenfive.wearvian.ble

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import org.fivesevenfive.wearvian.util.DebugLog

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
 */
object CommandBus {
    /** Max age of a queued command before [poll] discards it as stale instead of firing late. */
    const val COMMAND_TTL_MS = 5_000L

    /** Monotonic clock in ms; injectable so JVM unit tests can advance time without Robolectric. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    data class Command(
        val code: Int,
        val label: String,
        val submittedAtMs: Long = clock(),
    )

    // Unlimited + non-blocking: a tap should never block the UI, and the PRIMARY
    // session drains at heartbeat cadence (~300 ms), so the queue stays tiny.
    private val queue = Channel<Command>(Channel.UNLIMITED)

    /** elapsedRealtime() of the last submit — a "manual action" signal for watch-mode burst presence
     *  (the heartbeat loop skips draining while paused, so a tap has to wake the burst to be sent). */
    @Volatile var lastSubmitMs = 0L
        private set

    /** Enqueue a command for the live PRIMARY session to send on its next heartbeat tick. */
    fun submit(code: Int, label: String) {
        DebugLog.add("cmdbus: queued $label (0x%04x)".format(code))
        val now = clock()
        lastSubmitMs = now
        queue.trySend(Command(code, label, now))
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
                DebugLog.add("cmdbus: dropped stale ${cmd.label} (age=${ageMs}ms)")
                continue
            }
            return cmd
        }
    }

    /** Discard all queued commands (called when the PRIMARY session tears down). */
    fun clear() {
        var dropped = 0
        while (queue.tryReceive().isSuccess) dropped++
        if (dropped > 0) DebugLog.add("cmdbus: cleared $dropped pending")
    }
}
