package org.fivesevenfive.wearvian.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live vehicle state parsed from the plaintext CHAR_VEHICLE_STATUS (0x1c) frames the
 * PRIMARY [org.fivesevenfive.wearvian.ble.VehicleSession] receives (only the PRIMARY link
 * subscribes 0x1c). Lets the UI reflect real lock/closure state instead of guessing.
 *
 * Frame = `[le32 counter] ‖ [16-byte status]`; layout decoded on-vehicle 2026-06-06
 * (docs/passive-entry-protocol.md):
 *   status[0] bit0      = asleep
 *   status[1] hi-nibble = locked;  lo-nibble = doors  (bit 0x08 driver, 0x04 passenger,
 *                                                       0x02 rear-driver, 0x01 rear-pass; 1=closed)
 *   status[2] hi-nibble = locked;  bit 0x08 = frunk, bit 0x04 = liftgate  (1=closed)
 *   status[3]           = windows (same bit layout as doors; 1=closed)
 *   status[4..15]       = static config (charge-port + battery are NOT here — cloud only)
 */
object VehicleStatus {

    data class State(
        /** True once a 0x1c frame has been parsed this session (else fields are unknown). */
        val valid: Boolean = false,
        val asleep: Boolean = false,
        val locked: Boolean = false,
        val frunkOpen: Boolean = false,
        val liftgateOpen: Boolean = false,
        val anyDoorOpen: Boolean = false,
        val anyWindowOpen: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** Parse a raw 0x1c frame (`[le32 counter][16B status]`) and publish. */
    fun update(frame: ByteArray) {
        if (frame.size < 4 + 4) return // need at least status[0..3]
        fun s(i: Int) = frame[4 + i].toInt() and 0xff
        // [2] high nibble is the cleanest lock signal (set when locked; clear when unlocked,
        // even with a closure open). [1]'s high nibble flickers (0x7f) mid-unlock.
        _state.value = State(
            valid = true,
            asleep = (s(0) and 0x01) != 0,
            locked = (s(2) and 0xf0) != 0,
            frunkOpen = (s(2) and 0x08) == 0,
            liftgateOpen = (s(2) and 0x04) == 0,
            anyDoorOpen = (s(1) and 0x0f) != 0x0f,
            anyWindowOpen = (s(3) and 0x0f) != 0x0f,
        )
    }

    /** Reset to unknown (presence stopped / no session). */
    fun clear() {
        _state.value = State()
    }
}
