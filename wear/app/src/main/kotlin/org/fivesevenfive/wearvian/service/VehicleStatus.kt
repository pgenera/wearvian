package org.fivesevenfive.wearvian.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live vehicle state parsed from the plaintext CHAR_VEHICLE_STATUS (0x1c) frames the
 * PRIMARY [org.fivesevenfive.wearvian.ble.VehicleSession] receives (only the PRIMARY link
 * subscribes 0x1c). Lets the UI reflect real lock/closure state instead of guessing.
 *
 * State lives **in memory only** — it survives the UI closing while the foreground-service
 * process is alive (so reopening the app shows the last-known state), but is gone once the
 * process is killed (and so never survives a reboot); nothing is written to disk. When there's
 * no live session, [clear] keeps the last value but marks it [State.live]`=false` (stale). The UI
 * shows the same affordances from a stale state, just dimmed (gray instead of white) to signal it
 * isn't being confirmed right now.
 *
 * Frame = `[le32 counter] ‖ [16-byte status]`; layout decoded on-vehicle 2026-06-06/06-07
 * (docs/passive-entry-protocol.md):
 *   status[0] bit0      = asleep
 *   status[1] hi-nibble = locked;  lo-nibble = doors  (bit 0x08 driver, 0x04 passenger,
 *                                                       0x02 rear-driver, 0x01 rear-pass; 1=closed)
 *   status[2] hi-nibble = locked;  bit 0x08 = frunk, bit 0x04 = liftgate  (1=closed)
 *   status[3]           = windows (same bit layout as doors; 1=closed)
 *   status[5]           = state of charge, integer %        (decoded 2026-06-07)
 *   status[7]           = cabin temperature, °C             (decoded 2026-06-07)
 *   status[8..9]        = estimated range, km, little-endian (decoded 2026-06-07)
 *   status[6],[11],[12] = constant config (0x11/0x50/0x78); charge-port + climate setpoint
 *                         are NOT here (setpoint unlocated; charge-port is cloud only)
 *
 * The telemetry trio (SoC/cabin/range) overturns the earlier "battery/range are cloud-only"
 * read: they ARE on BLE here. Today's ground truth pinned the units (48.4% → [5]=48;
 * 86°F=30°C → [7]=30; 137mi=220km → [8..9]=220), and three captures cross-check to a ~282-mi
 * full-charge range. Charge LIMIT still isn't in this frame (cloud only).
 */
object VehicleStatus {

    data class State(
        /** True once a 0x1c frame has been parsed (else fields are unknown). */
        val valid: Boolean = false,
        /** True while a current session is confirming this state; false when stale (last-known). */
        val live: Boolean = false,
        val asleep: Boolean = false,
        val locked: Boolean = false,
        val frunkOpen: Boolean = false,
        val liftgateOpen: Boolean = false,
        val anyDoorOpen: Boolean = false,
        val anyWindowOpen: Boolean = false,
        /** State of charge, integer percent; null when the frame is too short to carry it. */
        val socPercent: Int? = null,
        /** Cabin temperature in °C; null when not present in the frame. */
        val cabinTempC: Int? = null,
        /** Estimated remaining range in km; null when not present in the frame. */
        val rangeKm: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** Parse a raw 0x1c frame (`[le32 counter][16B status]`) and publish (live). */
    fun update(frame: ByteArray) {
        if (frame.size < 4 + 4) return // need at least status[0..3]
        fun s(i: Int) = frame[4 + i].toInt() and 0xff
        val hasTelemetry = frame.size >= 4 + 10 // need status[0..9] for SoC/temp/range
        // [2] high nibble is the cleanest lock signal (set when locked; clear when unlocked,
        // even with a closure open). [1]'s high nibble flickers (0x7f) mid-unlock.
        _state.value = State(
            valid = true,
            live = true,
            asleep = (s(0) and 0x01) != 0,
            locked = (s(2) and 0xf0) != 0,
            frunkOpen = (s(2) and 0x08) == 0,
            liftgateOpen = (s(2) and 0x04) == 0,
            anyDoorOpen = (s(1) and 0x0f) != 0x0f,
            anyWindowOpen = (s(3) and 0x0f) != 0x0f,
            socPercent = if (hasTelemetry) s(5) else null,
            cabinTempC = if (hasTelemetry) s(7) else null,
            rangeKm = if (hasTelemetry) s(8) or (s(9) shl 8) else null,
        )
    }

    /**
     * Session stopped: keep the last-known state but mark it stale, so the UI keeps showing the
     * same affordances dimmed rather than blanking out. No-op if we never had a state.
     */
    fun clear() {
        val cur = _state.value
        if (cur.valid && cur.live) _state.value = cur.copy(live = false)
    }
}
