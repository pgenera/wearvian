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
 * ## Layout — authoritative, recovered from the official app's decompile
 *
 * The byte/bit layout is **not** guessed: it is the official Rivian app's own status schema
 * `n50.f.SCHEMA_VERSION_1` (the `BLEPath_LegacyBleVehicleStatusInterceptor`, class `m6/b` method
 * `A`), a byte-index → {bitmask → field} dictionary. Frame = `[le32 counter] ‖ [status]`; the
 * plaintext 0x1c push carries `status[0..15]`, which are the first 16 bytes of the same
 * SCHEMA_VERSION_1 layout (version byte 0x10 = `status[0] & 0xf0`, size 35). We decode the fields
 * that live in `status[0..10]`; the richer fields the schema places at `[27..34]` (pet mode,
 * pending "next actions", driver occupancy, immobilizer, trailer, power mode) are only in the full
 * 35-byte VAS status message (the app's `requestFullVehicleStatusMessage`), not this push.
 *
 * Boolean sense (schema `n50.d.getDefaultValue`): OPEN_CLOSED — masked bit **0 = open**, else
 * closed; LOCK_UNLOCK — masked **0 = unlocked**, else locked.
 *
 *   status[0] &0x03 = CGM (Gear Guard) arm-alarm: 0 off, 1 armed, 2 n/a, 3 faulted. We read bit0 as
 *                     [State.asleep] — a proxy: Gear Guard arms when the car is parked/unattended,
 *                     so this tracks the car going to sleep. &0x0c = Gear Guard sound alarm.
 *                     (High nibble = schema version, 0x10.)
 *   status[1] lo     = the four DOORS, open/closed: 0x08 L-front, 0x04 R-front, 0x02 L-rear,
 *                     0x01 R-rear (1=closed). hi = the four door LOCK bits (we don't surface these).
 *   status[2] 0x01 = charge-port door open/closed ([State.chargePortDoorOpen]); 0x04 = liftgate;
 *                     0x08 = frunk (1=closed). hi nibble = tonneau/liftgate/tailgate/frunk LOCK bits.
 *                     The charge-port bit read 0 ("open") on every capture — possibly unpopulated in
 *                     the compact push — so it's surfaced on the charge page to CONFIRM on-vehicle by
 *                     physically opening/closing the port and watching whether it tracks. Schema also
 *                     puts tonneau at 0x02 (likewise always 0, not surfaced).
 *   status[3] lo     = the four WINDOWS (same bit order as doors; 1=closed). hi = R1T side bins.
 *   status[4] &0x3c = cabin-preconditioning status (4-bit enum, >>2): 0 undef, 1 initiate,
 *                     2 active, 3 active_warning, 4 complete_maintain, 5 timeout, 6 err_soc_low,
 *                     7 err_sys_fault, 8 unavailable (reported while driving), 9 timeout_complete.
 *                     [State.climateOn] = value in 1..4. (0x02 = R1T tailgate; 0x40 gear-guard lock;
 *                     0x80 bomb-bay-bin lock.) NB: the old "in-motion 0x20" flag was a misread — it's
 *                     bit3 of this enum (value 8 = unavailable while driving); driving is detected
 *                     from [gear], not here.
 *   status[5] &0x7f = state of charge, integer %. Bit 0x80 = anti-theft alarm active (schema field;
 *                     never observed set in a capture, so not surfaced — confirm on-vehicle first).
 *   status[6] hi     = GEAR / PRNDL: 1 Park, 2 Reverse, 3 Neutral, 4 Drive. lo = charge state enum
 *                     ([ChargeState]).
 *   status[7]        = cabin temperature, °C.
 *   status[8..9]     = estimated range, km. A 9-bit field ([8] + bit0 of [9]) that OVERLAPS the
 *                     charge ETA in [9]: full [8..9] LE16 when not charging (280 km == 174 mi), but
 *                     masked to 9 bits while charging (`& 0x1ff`) since [9]'s upper bits are then the
 *                     ETA. Confirmed 2026-06-12 (mileage-charging.log: 0xcd36 & 0x1ff = 310 km).
 *   status[9..10]    = charge ETA to the set LIMIT, LE16, 15 s per count = raw/4 min (NOT power;
 *                     proven by a fixed-SoC amperage sweep where raw ∝ 1/current). [10] &0xc0 =
 *                     battery-level status (normal/low/red/critical), which we don't surface.
 *   status[11..15]   = reserved. The schema maps no fields to bytes 11..26, so this is framing/pad,
 *                     not data — it varies between captures but the app decodes nothing from it.
 */
object VehicleStatus {

    /** Transmission gear from status[6]'s HIGH nibble (PRNDL order). */
    enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

    /**
     * Charge state from status[6]'s low nibble (the high nibble is the [Gear]). Names are ours; the
     * mapping follows the schema's VEHICLE_CHARGING_STATUS enum (`n50.d`): 1 ready, 2 connecting,
     * 3 active, 4 complete, 5 scheduled, 6 vehicle-error, 7 station-error, 8 user-stopped. Value 1
     * is the vehicle's idle/ready baseline, which we've only ever observed unplugged, hence
     * [UNPLUGGED]. The 4-bit nibble can't reach the schema's higher codes (they need a 5th bit that
     * only the full 35-byte message carries).
     */
    enum class ChargeState {
        UNKNOWN,      // not present / unrecognized code
        UNPLUGGED,    // 0x_1 — schema "charging_ready" (idle baseline; only seen unplugged)
        STARTING,     // 0x_2 — schema "charging_connecting" (negotiating)
        CHARGING,     // 0x_3 — schema "charging_active" (ETA-to-limit counts down in [9..10])
        PLUGGED_IDLE, // 0x_5 scheduled / 0x_8 user-stopped — cord connected, not charging
        FAULT,        // 0x_7 — schema "charging_station_error" ("check charger")
    }

    data class State(
        /** True once a 0x1c frame has been parsed (else fields are unknown). */
        val valid: Boolean = false,
        /** True while a current session is confirming this state; false when stale (last-known). */
        val live: Boolean = false,
        /**
         * Proxy for "vehicle asleep". This is really status[0]'s CGM_ARM_ALARM low bit (Gear Guard
         * armed) per the schema; Gear Guard arms when the car is parked and unattended, so it tracks
         * sleep closely enough to drive the watch-presence wake trigger (see PresenceService).
         */
        val asleep: Boolean = false,
        val locked: Boolean = false,
        val frunkOpen: Boolean = false,
        val liftgateOpen: Boolean = false,
        /**
         * Charge-port door open (status[2] bit 0x01; schema sense masked-0 = open). Surfaced on the
         * charge page to confirm on-vehicle whether the compact push actually populates this bit —
         * it read 0 ("open") in every capture, so a physical open/close toggle is the real test.
         */
        val chargePortDoorOpen: Boolean = false,
        val anyDoorOpen: Boolean = false,
        val anyWindowOpen: Boolean = false,
        /**
         * Cabin preconditioning (climate) is running: the status[4] preconditioning enum
         * (`& 0x3c >> 2`) is in 1..4 (initiate / active / active_warning / complete_maintain).
         */
        val climateOn: Boolean = false,
        /**
         * Transmission gear (PRNDL) from status[6]'s high nibble; [Gear.UNKNOWN] when absent.
         * Leaving Park means the car is started and being driven — used to drop presence (see
         * PresenceService driving-doze). Decoded 2026-06-16 from prndl.log.
         */
        val gear: Gear = Gear.UNKNOWN,
        /** State of charge, integer percent; null when the frame is too short to carry it. */
        val socPercent: Int? = null,
        /** Cabin temperature in °C; null when not present in the frame. */
        val cabinTempC: Int? = null,
        /**
         * Estimated remaining range, km. A 9-bit field ([8] + bit0 of [9]) that overlaps the charge
         * ETA in [9]: full [8..9] LE16 when not charging (280==174mi), but masked to 9 bits while
         * charging (`& 0x1ff`), since [9]'s upper bits are then the ETA. null when not in the frame.
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
         * Estimated time to reach the charge LIMIT, in seconds (15 s per raw count = raw/4 min).
         * Time-to-limit, not to 100% — a 70%→90% limit A/B at fixed SoC/current jumped the raw
         * 654→1184. The 15 s/count scale: a simultaneous app reading (10h 13m = 613 min) against
         * our settled raw 2468 gives 14.9 s/count, and the field steps by 4 counts so 4×15 = 60 s
         * is a clean 1-min display resolution (16 s would be 64 s). null when [chargeTimeRaw] null.
         */
        val chargeEtaSeconds: Int? get() = chargeTimeRaw?.let { it * 15 }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** Parse a raw 0x1c frame (`[le32 counter][16B status]`) and publish (live). */
    fun update(frame: ByteArray) {
        if (frame.size < 4 + 4) return // need at least status[0..3]
        fun s(i: Int) = frame[4 + i].toInt() and 0xff
        val hasTelemetry = frame.size >= 4 + 11 // need status[0..10] for SoC/temp/range/charge
        val cs = if (hasTelemetry) chargeStateOf(s(6)) else ChargeState.UNKNOWN
        // Range and charge-ETA OVERLAP in status[9]: range is a 9-bit field (status[8] plus bit0 of
        // [9]), and the charge ETA is [9..10] — so [9]'s upper bits are the ETA low byte while a
        // charge is in progress. When charging, masking range to 9 bits drops those ETA bits; when
        // not charging the ETA is absent, so [9] is a clean range high byte and full [8..9] gives
        // headroom above the 9-bit ceiling (511 km / 317 mi). Confirmed on every captured frame
        // (mileage-charging.log: 0xcd36 & 0x1ff = 310 km = 193 mi, while [8] alone read 54 km = 34 mi).
        val etaActive = cs == ChargeState.CHARGING || cs == ChargeState.STARTING
        // [2] high nibble is the cleanest lock signal (set when locked; clear when unlocked,
        // even with a closure open). [1]'s high nibble flickers (0x7f) mid-unlock.
        _state.value = State(
            valid = true,
            live = true,
            asleep = (s(0) and 0x01) != 0, // CGM_ARM_ALARM low bit — see [State.asleep]
            locked = (s(2) and 0xf0) != 0,
            frunkOpen = (s(2) and 0x08) == 0,
            liftgateOpen = (s(2) and 0x04) == 0,
            chargePortDoorOpen = (s(2) and 0x01) == 0, // schema: masked-0 = open (confirming on-vehicle)
            anyDoorOpen = (s(1) and 0x0f) != 0x0f,
            anyWindowOpen = (s(3) and 0x0f) != 0x0f,
            climateOn = frame.size >= 4 + 5 && ((s(4) and 0x3c) shr 2) in 1..4,
            gear = if (hasTelemetry) gearOf(s(6)) else Gear.UNKNOWN,
            socPercent = if (hasTelemetry) s(5) and 0x7f else null,
            cabinTempC = if (hasTelemetry) s(7) else null,
            rangeKm = when {
                !hasTelemetry -> null
                etaActive -> (s(8) or (s(9) shl 8)) and 0x1ff // 9-bit: [9]'s upper bits are the ETA
                else -> s(8) or (s(9) shl 8)                   // [8..9] LE16 — full range, no ETA in [9]
            },
            chargeState = cs,
            chargeTimeRaw = when {
                !hasTelemetry -> null
                etaActive -> s(9) or (s(10) shl 8)
                else -> 0                       // not charging: no ETA, and [9] is the range high byte
            },
        )
    }

    /** Gear from status[6]'s high nibble (PRNDL order; confirmed on prndl.log P→D→N→R→P). */
    private fun gearOf(b6: Int): Gear = when ((b6 shr 4) and 0x0f) {
        0x1 -> Gear.PARK
        0x2 -> Gear.REVERSE
        0x3 -> Gear.NEUTRAL
        0x4 -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    /** Charge state from status[6]'s low nibble (schema VEHICLE_CHARGING_STATUS; see [ChargeState]). */
    private fun chargeStateOf(b6: Int): ChargeState = when (b6 and 0x0f) {
        0x1 -> ChargeState.UNPLUGGED
        0x2 -> ChargeState.STARTING
        0x3 -> ChargeState.CHARGING
        0x5 -> ChargeState.PLUGGED_IDLE // schema: charging_scheduled
        0x8 -> ChargeState.PLUGGED_IDLE // schema: charging_user_stopped (plugged, stopped by user)
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
