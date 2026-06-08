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
 *   status[6] lo-nibble = charge state enum                 (decoded 2026-06-07; see [ChargeState])
 *   status[7]           = cabin temperature, °C             (decoded 2026-06-07)
 *   status[8]           = estimated range, km (low byte; 220km==137mi). High byte UNLOCATED —
 *                         the earlier [8..9] LE guess is REFUTED ([9] is the charge-power low
 *                         byte). Correct above ~255km is unknown; needs a >158mi capture.
 *   status[9..10]       = live charge power, LE16 in 1/64 kW per count (= 15.625 W; decoded
 *                         2026-06-07/08). raw 644 → 10.06 kW (on-vehicle the app read ~10 kW
 *                         while a /100 scale wrongly showed 6.4 — the 100/64 ratio gives 1/64 kW).
 *   status[11],[12]     = constant config (0x50/0x78); charge LIMIT + climate setpoint are NOT
 *                         here (both cloud-only — setpoint never varied across captures)
 *
 * The telemetry (SoC/cabin/range/charge) overturns the earlier "battery/range are cloud-only"
 * read: they ARE on BLE here. Ground truth pinned SoC/cabin/range (48.4%→[5]=48; 86°F=30°C→[7]=30;
 * 137mi=220km→[8]=220) and the charge-state enum (the failed/idle/charging narrative).
 */
object VehicleStatus {

    /** Charge state from status[6]'s low nibble (high nibble is a constant 0x1). */
    enum class ChargeState {
        UNKNOWN,      // not yet parsed / unrecognized code
        UNPLUGGED,    // 0x_1
        STARTING,     // 0x_2 — brief negotiating frame before charging
        CHARGING,     // 0x_3 — actively charging (power ramps in [9..10])
        PLUGGED_IDLE, // 0x_5 — cord connected, not charging (e.g. waiting for schedule)
        FAULT,        // 0x_7 — charge fault ("check charger")
    }

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
        /**
         * Estimated remaining range, km (low byte [8] only; 220==137mi). The high byte is
         * unlocated, so this is only correct below ~255 km; null when not present in the frame.
         */
        val rangeKm: Int? = null,
        /** Plug/charge state; [ChargeState.UNKNOWN] when not present or unrecognized. */
        val chargeState: ChargeState = ChargeState.UNKNOWN,
        /**
         * Live charge power in watts ([9..10] little-endian × 1/64 kW = 15.625 W/count); 0 while
         * not charging, null when not present. The 1/64-kW fixed-point scale is from on-vehicle
         * truth (the app read ~10 kW where an earlier ×10-W/count scale wrongly showed 6.4 kW; the
         * 10/6.4 ≈ 100/64 ratio pins it). Use [chargePowerKw] for display.
         */
        val chargePowerW: Int? = null,
    ) {
        /** Live charge power in kW (one decimal mirrors the app); null when [chargePowerW] is null. */
        val chargePowerKw: Double? get() = chargePowerW?.let { it / 1000.0 }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** Parse a raw 0x1c frame (`[le32 counter][16B status]`) and publish (live). */
    fun update(frame: ByteArray) {
        if (frame.size < 4 + 4) return // need at least status[0..3]
        fun s(i: Int) = frame[4 + i].toInt() and 0xff
        val hasTelemetry = frame.size >= 4 + 11 // need status[0..10] for SoC/temp/range/charge
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
            rangeKm = if (hasTelemetry) s(8) else null, // [9] is charge power, not range high byte
            chargeState = if (hasTelemetry) chargeStateOf(s(6)) else ChargeState.UNKNOWN,
            chargePowerW = if (hasTelemetry) (s(9) or (s(10) shl 8)) * 1000 / 64 else null,
        )
    }

    private fun chargeStateOf(b6: Int): ChargeState = when (b6 and 0x0f) {
        0x1 -> ChargeState.UNPLUGGED
        0x2 -> ChargeState.STARTING
        0x3 -> ChargeState.CHARGING
        0x5 -> ChargeState.PLUGGED_IDLE
        0x7 -> ChargeState.FAULT
        else -> ChargeState.UNKNOWN
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
