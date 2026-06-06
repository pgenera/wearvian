package org.fivesevenfive.wearvian.ble

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
    data class Command(val code: Int, val label: String)

    // Unlimited + non-blocking: a tap should never block the UI, and the PRIMARY
    // session drains at heartbeat cadence (~300 ms), so the queue stays tiny.
    private val queue = Channel<Command>(Channel.UNLIMITED)

    /** Enqueue a command for the live PRIMARY session to send on its next heartbeat tick. */
    fun submit(code: Int, label: String) {
        DebugLog.add("cmdbus: queued $label (0x%04x)".format(code))
        queue.trySend(Command(code, label))
    }

    /** Non-blocking dequeue for the PRIMARY session loop; null when nothing is pending. */
    fun poll(): Command? = queue.tryReceive().getOrNull()
}
