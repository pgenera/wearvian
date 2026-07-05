package org.fivesevenfive.wearvian.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * The **full** vehicle status — the richer, poll-style report the vehicle sends as an encrypted
 * type-0x18 (`TYPE_VEHICLE_STATUS`) message on the active-command channel (P / CHAR_ACTIVE_COMMAND).
 * This is a SUPERSET of the compact plaintext 0x1c push ([VehicleStatus]): it carries the whole
 * `n50.f.SCHEMA_VERSION_1` layout (35 bytes, indices 0..34), including the per-closure **NEXT_ACTION**
 * states (opening / closing / obstructed / not-allowed) and the **charge-port control state** that the
 * compact push doesn't have.
 *
 * ## STATUS: speculative / gated, pending on-vehicle confirmation (2026-07-05)
 *
 * We PROVED (btsnoop + decompile) that the official phone app receives 126-byte type-0x18 frames on
 * this channel and decrypts them to this schema. BUT in every capture our WATCH session instead gets
 * SHORT 20-byte type-0x18 frames (too short to be the full status — below the 30-byte GCM minimum, so
 * [org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.decryptInbound] returns null). Why the watch
 * gets the compact frame and the phone gets the full one is an open on-vehicle question (prime suspect:
 * `keyDeviceSubtype=WATCH`). So this whole path is **inert until the car actually sends us a decryptable
 * full-status frame** — [state] stays [Full.valid]=false and the UI falls back to the 0x1c-only behaviour.
 * When a frame DOES arrive, [update] logs it exhaustively (raw + decrypted + every derived field) so the
 * layout/offset can be confirmed from logs alone. See docs/passive-entry-protocol.md.
 */
object FullVehicleStatus {

    /** Offset of the 35-byte SCHEMA_VERSION_1 status within the decrypted plaintext (per m6/b.A). */
    private const val STATUS_OFFSET = 4
    private const val STATUS_LEN = 35

    /** Coarse motion state for a powered closure, distilled from its schema NEXT_ACTION enum. */
    enum class Motion { IDLE, OPENING, CLOSING, OPEN_BLOCKED, CLOSE_BLOCKED }

    /** Charge-port control state (schema CHARGE_PORT_CONTROL_STATE). */
    enum class ChargePort { UNKNOWN, OPEN, CLOSED, IN_TRANSITION, FAULT, OPENING, CLOSING }

    data class Full(
        val valid: Boolean = false,
        val live: Boolean = false,
        /** status[0] high nibble — the schema version (expected 0x10). Logged to confirm alignment. */
        val schemaVersion: Int = 0,
        val chargePortDoorOpen: Boolean = false,
        val chargePort: ChargePort = ChargePort.UNKNOWN,
        val frunk: Motion = Motion.IDLE,
        val liftgate: Motion = Motion.IDLE,
        val tailgate: Motion = Motion.IDLE,
        val windows: Motion = Motion.IDLE,
        val chargePortDoor: Motion = Motion.IDLE,
    ) {
        /** True while any tracked closure is mid-move — used to show "Opening…/Closing…" feedback. */
        val anyMoving: Boolean get() =
            listOf(frunk, liftgate, tailgate, windows, chargePortDoor).any { it == Motion.OPENING || it == Motion.CLOSING }
    }

    private val _state = MutableStateFlow(Full())
    val state: StateFlow<Full> get() = _state

    /**
     * Parse a DECRYPTED full-status plaintext (the output of `decryptInbound` on a type-0x18 frame)
     * and publish it. Logs exhaustively — raw plaintext, the schema-version sanity byte, and every
     * derived field — so on-vehicle we can confirm the offset/masks from logs without a phone snoop.
     * No-op (with a log) when the plaintext is too short to hold the schema.
     */
    fun update(plaintext: ByteArray, log: Boolean = true) {
        // Parsing always runs (it feeds the UI); ALL of the diagnostic logging is gated by [log] so
        // it's present on debug/debugRelease builds (which we ship to testing) but silent on the
        // production release — the plaintext + derived state are sensitive usage data / dev noise.
        // Callers pass `!BuildConfig.PRODUCTION`.
        if (log) DebugLog.add("fullstatus: plaintext ${plaintext.size}B ${plaintext.toHex()}")
        if (plaintext.size < STATUS_OFFSET + STATUS_LEN) {
            if (log) DebugLog.add("fullstatus: too short for schema (${plaintext.size}B, need ${STATUS_OFFSET + STATUS_LEN}B); not parsed")
            return
        }
        val s = IntArray(STATUS_LEN) { plaintext[STATUS_OFFSET + it].toInt() and 0xff }
        val version = s[0] and 0xf0
        if (version != 0x10 && log) {
            // Not fatal — parse anyway, but flag it: the offset or format may differ from m6/b.A.
            DebugLog.add("fullstatus: WARNING schema-version byte s[0]=0x%02x (expected hi-nibble 0x10) — offset may be off".format(s[0]))
        }

        val chargePortDoorOpen = (s[2] and 0x01) == 0
        // NEXT_ACTION values are assembled per m6/b.A (some fields span two bytes).
        val frunkRaw = ((s[28] and 0x03) shl 2) or ((s[27] and 0xc0) shr 6)
        val liftgateRaw = (s[29] and 0x0f) or ((s[32] and 0x80) shr 3)
        val tailgateRaw = (s[30] and 0x07) or ((s[32] and 0x40) shr 3)
        val windowsRaw = (s[29] and 0xf0) shr 4
        val chargePortDoorRaw = (s[33] and 0xf0) shr 4
        val cpcsRaw = ((s[32] and 0x03) shl 1) or ((s[31] and 0x80) shr 7)

        val full = Full(
            valid = true,
            live = true,
            schemaVersion = version,
            chargePortDoorOpen = chargePortDoorOpen,
            chargePort = chargePortOf(cpcsRaw),
            frunk = motionOf(frunkRaw),
            liftgate = motionOf(liftgateRaw),
            tailgate = motionOf(tailgateRaw),
            windows = windowsMotionOf(windowsRaw),
            chargePortDoor = chargePortDoorMotionOf(chargePortDoorRaw),
        )
        _state.value = full
        if (log) DebugLog.add(
            "fullstatus: v=0x%02x cpDoor=%s cpState=%s frunk=%s(%d) liftgate=%s(%d) tailgate=%s(%d) windows=%s(%d) cpDoorAct=%s(%d)".format(
                version, if (chargePortDoorOpen) "open" else "closed", full.chargePort,
                full.frunk, frunkRaw, full.liftgate, liftgateRaw, full.tailgate, tailgateRaw,
                full.windows, windowsRaw, full.chargePortDoor, chargePortDoorRaw,
            ),
        )
    }

    /** Session ended: keep last-known but mark not-live (mirrors [VehicleStatus.clear]). */
    fun clear() {
        val cur = _state.value
        if (cur.valid && cur.live) _state.value = cur.copy(live = false)
    }

    /**
     * Distill a liftgate/frunk/tailgate/windows NEXT_ACTION code into coarse [Motion]. Shared map
     * (schema *_NEXT_ACTION): 3=opening, 4=closing, 5/9=open-not-available, 6/10=close-not-available,
     * 7=open-not-allowed-faulted, 8=close-not-allowed-faulted; others (allowed / obstructed-but-
     * recoverable / trailer-detected) → IDLE.
     */
    private fun motionOf(raw: Int): Motion = when (raw) {
        3 -> Motion.OPENING
        4 -> Motion.CLOSING
        5, 7, 9 -> Motion.OPEN_BLOCKED
        6, 8, 10 -> Motion.CLOSE_BLOCKED
        else -> Motion.IDLE
    }

    /**
     * Windows have their own NEXT_ACTION map: 3=opening, 4=closing, 5=moving (direction unknown →
     * IDLE), 6/8/12=open-not-available/faulted/uncalibrated, 7/9/11=close-not-available/faulted/
     * uncalibrated; else IDLE.
     */
    private fun windowsMotionOf(raw: Int): Motion = when (raw) {
        3 -> Motion.OPENING
        4 -> Motion.CLOSING
        6, 8, 12 -> Motion.OPEN_BLOCKED
        7, 9, 11 -> Motion.CLOSE_BLOCKED
        else -> Motion.IDLE
    }

    /**
     * Charge-port DOOR next-action has its own map: 4=opening, 11=closing, 2/3=open-not-available/
     * faulted, 9/10=close-not-available/faulted; else IDLE.
     */
    private fun chargePortDoorMotionOf(raw: Int): Motion = when (raw) {
        4 -> Motion.OPENING
        11 -> Motion.CLOSING
        2, 3 -> Motion.OPEN_BLOCKED
        9, 10 -> Motion.CLOSE_BLOCKED
        else -> Motion.IDLE
    }

    /** CHARGE_PORT_CONTROL_STATE map: 0 SNA, 1 open, 2 close, 3 in_transition, 4 fault, 5 opening, 6 closing. */
    private fun chargePortOf(raw: Int): ChargePort = when (raw) {
        1 -> ChargePort.OPEN
        2 -> ChargePort.CLOSED
        3 -> ChargePort.IN_TRANSITION
        4 -> ChargePort.FAULT
        5 -> ChargePort.OPENING
        6 -> ChargePort.CLOSING
        else -> ChargePort.UNKNOWN
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
