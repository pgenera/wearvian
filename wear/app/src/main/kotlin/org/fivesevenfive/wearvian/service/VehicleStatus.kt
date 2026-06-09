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
 *   status[4]           = climate/HVAC state (decoded 2026-06-09): 0 off, 0x04 preconditioning
 *                         starting, 0x08 running. `& 0x0c` != 0 = climate on.
 *   status[5]           = state of charge, integer %        (decoded 2026-06-07)
 *   status[6] lo-nibble = charge state enum                 (decoded 2026-06-07; see [ChargeState])
 *   status[7]           = cabin temperature, °C             (decoded 2026-06-07)
 *   status[8]           = estimated range, km (low byte; 220km==137mi). High byte UNLOCATED —
 *                         the earlier [8..9] LE guess is REFUTED ([9] is the charge-time low
 *                         byte). Correct above ~255km is unknown; needs a >158mi capture.
 *   status[9..10]       = charge ETA to the set LIMIT, LE16, ≈ 16 s per count (NOT power). Proven
 *                         by a fixed-SoC (50%) amperage sweep 2026-06-09 (20A→1483, 28A→1042,
 *                         44A→654: falls as current rises, raw×current ≈ const ⇒ time ∝ 1/power,
 *                         impossible for power) plus a 70%→90% limit A/B (raw 654→1184 ⇒ tracks the
 *                         limit, not 100%). Scale pinned to ~1% by a direct app reading (90% limit,
 *                         9.7 kW: raw ~1184 ↔ app "5h 18m"). The old "1/70 kW power" read was a
 *                         coincidence — both prior captures sat in the same ~9–10 kW band where
 *                         power and time are numerically degenerate. True charge POWER is not in
 *                         this frame at all (no other byte tracks current across the sweep).
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
        UNKNOWN,      // not yet parsed / unrecognized code (0x_8 seen once at charge start, ETA 0
                      // — semantics unidentified, so it intentionally maps here and the UI shows
                      // no state line for it)
        UNPLUGGED,    // 0x_1
        STARTING,     // 0x_2 — brief negotiating frame before charging
        CHARGING,     // 0x_3 — actively charging (ETA-to-limit counts down in [9..10])
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
        /**
         * Cabin preconditioning (climate) is running. status[4] is a dedicated HVAC-state byte (0
         * with climate off across every charge/idle capture); a climate on→off capture showed it
         * step 0 → 0x04 (starting) → 0x08 (running) → 0 (off). We treat `0x0c` (either bit) as on.
         */
        val climateOn: Boolean = false,
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
         * Charge ETA to the set limit, raw LE16 count from status[9..10] (NOT power — see the
         * class doc); 0 while not charging, null when not present. Use [chargeEtaSeconds] for a
         * real duration. Kept raw because the scale is empirical.
         */
        val chargeTimeRaw: Int? = null,
    ) {
        /**
         * Estimated time to reach the charge LIMIT, in seconds (≈ 16 s per raw count). Time-to-
         * limit, not to 100% — a 70%→90% limit A/B at fixed SoC/current jumped the raw 654→1184.
         * The 16 s/count scale is pinned to ~1% by a direct app reading (90% limit, 9.7 kW: raw
         * ~1184 ↔ app "5h 18m" = 318 min). null when [chargeTimeRaw] is null.
         */
        val chargeEtaSeconds: Int? get() = chargeTimeRaw?.let { it * 16 }
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
            climateOn = frame.size >= 4 + 5 && (s(4) and 0x0c) != 0,
            socPercent = if (hasTelemetry) s(5) else null,
            cabinTempC = if (hasTelemetry) s(7) else null,
            rangeKm = if (hasTelemetry) s(8) else null, // [9] is charge-time low byte, not range high byte
            chargeState = if (hasTelemetry) chargeStateOf(s(6)) else ChargeState.UNKNOWN,
            chargeTimeRaw = if (hasTelemetry) (s(9) or (s(10) shl 8)) else null,
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
