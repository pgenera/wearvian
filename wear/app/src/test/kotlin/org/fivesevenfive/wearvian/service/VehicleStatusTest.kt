package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode tests for the plaintext 0x1c status frame. The byte layout was decoded on-vehicle
 * (docs/passive-entry-protocol.md); these lock it so an accidental bit/mask edit is caught.
 *
 * Frame = `[le32 counter] ‖ [16-byte status]`; only status[0..3] carry the decoded fields:
 *   [0] bit0 = asleep
 *   [1] hi-nibble = locked, lo-nibble = doors (1=closed; all closed → 0x0f)
 *   [2] hi-nibble = locked, bit 0x08 = frunk, bit 0x04 = liftgate (1=closed)
 *   [3] lo-nibble = windows (1=closed; all closed → 0x0f)
 */
class VehicleStatusTest {

    /** Build a 0x1c frame: 4-byte counter (zeroed) followed by the given status bytes. */
    private fun frame(vararg status: Int): ByteArray {
        val b = ByteArray(4 + status.size)
        for (i in status.indices) b[4 + i] = status[i].toByte()
        return b
    }

    @Test
    fun lockedAllClosedAwake() {
        // [0]=awake, [1]=locked + all doors closed, [2]=locked + frunk+liftgate closed, [3]=windows closed
        VehicleStatus.update(frame(0x00, 0xff, 0xac, 0x0f))
        val s = VehicleStatus.state.value
        assertTrue(s.valid)
        assertTrue(s.live)
        assertFalse(s.asleep)
        assertTrue(s.locked)
        assertFalse(s.frunkOpen)
        assertFalse(s.liftgateOpen)
        assertFalse(s.anyDoorOpen)
        assertFalse(s.anyWindowOpen)
    }

    @Test
    fun unlockedFrunkOpen() {
        // [1] hi-nibble 0 = unlocked; [2]=0x04 → frunk bit (0x08) clear = open, liftgate (0x04) set = closed
        VehicleStatus.update(frame(0x00, 0x0f, 0x04, 0x0f))
        val s = VehicleStatus.state.value
        assertFalse(s.locked)
        assertTrue(s.frunkOpen)
        assertFalse(s.liftgateOpen)
    }

    @Test
    fun liftgateOpen() {
        // [2]=0xa8 → locked, frunk (0x08) set = closed, liftgate (0x04) clear = open
        VehicleStatus.update(frame(0x00, 0xff, 0xa8, 0x0f))
        val s = VehicleStatus.state.value
        assertTrue(s.locked)
        assertFalse(s.frunkOpen)
        assertTrue(s.liftgateOpen)
    }

    @Test
    fun asleepDoorAndWindowOpen() {
        // [0]=0x01 asleep; [1]=0xf7 → driver door bit (0x08) clear = open; [3]=0x0e → one window open
        VehicleStatus.update(frame(0x01, 0xf7, 0xac, 0x0e))
        val s = VehicleStatus.state.value
        assertTrue(s.asleep)
        assertTrue(s.anyDoorOpen)
        assertTrue(s.anyWindowOpen)
    }

    @Test
    fun decodesTelemetryFromRealSundayFrame() {
        // Captured on-vehicle 2026-06-07 (sunday-status.log). Ground truth at capture time:
        // SoC 48.4%, range 137 mi (= 220 km), cabin 86°F (= 30°C), unplugged, not charging.
        val frame = hex("01000000" + "100f0c0f0030111edc00005078000000")
        VehicleStatus.update(frame)
        val s = VehicleStatus.state.value
        assertEquals(48, s.socPercent)
        assertEquals(30, s.cabinTempC) // 30°C == 86°F
        assertEquals(220, s.rangeKm)   // 220 km == 137 mi ([8..9] LE16, high byte 0 here)
        assertEquals(VehicleStatus.ChargeState.UNPLUGGED, s.chargeState)
        assertEquals(0, s.chargeTimeRaw)
    }

    @Test
    fun rangeUsesHighByteWhenNotCharging() {
        // mileage.log 2026-06-10: SoC 62%, unplugged, app showed 173 mi. [8..9]=18 01 ⇒ 0x0118 =
        // 280 km = 174 mi. The old [8]-only parse read 0x18 = 24 km ≈ 15 mi (the reported bug).
        VehicleStatus.update(hex("01000000" + "100f0c0f003e111c1801005078000000"))
        val s = VehicleStatus.state.value
        assertEquals(62, s.socPercent)
        assertEquals(VehicleStatus.ChargeState.UNPLUGGED, s.chargeState)
        assertEquals(280, s.rangeKm)   // [8] | [9]<<8 = 0x0118
        assertEquals(0, s.chargeTimeRaw) // not charging ⇒ no ETA ([9] is the range high byte)
    }

    @Test
    fun chargeStateEnumFromByte6() {
        // [6] low nibble, real frames from the 2026-06-07 charge session:
        VehicleStatus.update(hex("01000000" + "100f0c0f0030151adc00005078000000"))
        assertEquals(VehicleStatus.ChargeState.PLUGGED_IDLE, VehicleStatus.state.value.chargeState) // 0x15
        VehicleStatus.update(hex("01000000" + "11ffac0f0030171adc00005078000000"))
        assertEquals(VehicleStatus.ChargeState.FAULT, VehicleStatus.state.value.chargeState) // 0x17 check-charger
        VehicleStatus.update(hex("04000000" + "11ffac0f0030131adc1c005078000000"))
        assertEquals(VehicleStatus.ChargeState.CHARGING, VehicleStatus.state.value.chargeState) // 0x13
    }

    @Test
    fun climateOnFromByte4() {
        // Real frames from the 2026-06-09 climate on→off capture. [4]: 0 off, 0x04 starting,
        // 0x08 running; `& 0x0c` != 0 = on.
        VehicleStatus.update(hex("02000000" + "11ffac0f0032151be50000147800800c")) // [4]=0x00 off
        assertFalse(VehicleStatus.state.value.climateOn)
        VehicleStatus.update(hex("04000000" + "11ffac0f0432151be50000147800800c")) // [4]=0x04 starting
        assertTrue(VehicleStatus.state.value.climateOn)
        VehicleStatus.update(hex("0a000000" + "11ffac0f0832151de50000147800800c")) // [4]=0x08 running
        assertTrue(VehicleStatus.state.value.climateOn)
    }

    @Test
    fun chargeTimeIsLittleEndianAndInverselyTracksCurrent() {
        // Fixed-SoC (50%) amperage sweep 2026-06-09: [9..10] is charge time-to-limit (∝ 1/power),
        // NOT power. raw FALLS as current rises and raw×current ≈ const (~29k) — impossible for
        // power, which must rise with current. See VehicleStatus class doc.
        VehicleStatus.update(hex("01000000" + "10ffac0f0032131be3d005147800800c")) // 20A
        val a20 = VehicleStatus.state.value
        assertEquals(50, a20.socPercent)
        assertEquals(VehicleStatus.ChargeState.CHARGING, a20.chargeState)
        assertEquals(0x05d0, a20.chargeTimeRaw) // 1488 (little-endian d0 05)
        // While charging, range is the 9-bit field [8]+bit0([9]); [9]'s upper bits are the ETA.
        // 0xe3d0 & 0x1ff = 0x0e3 = 227 km (bit0 of 0xd0 = 0, so just [8] here).
        assertEquals(227, a20.rangeKm)
        VehicleStatus.update(hex("09000000" + "11ffac0f0032131be39002147800800c")) // 44A
        val a44 = VehicleStatus.state.value
        assertEquals(0x0290, a44.chargeTimeRaw) // 656 — higher current, less time
        assertEquals(227, a44.rangeKm)          // 0xe390 & 0x1ff = 227
    }

    @Test
    fun rangeIsNineBitsWhileCharging() {
        // mileage-charging.log 2026-06-12: SoC 68%, charging, app showed 193 mi. The range high bit
        // shares [9] with the charge-ETA low byte: 0xcd36 & 0x1ff = 0x136 = 310 km = 193 mi. The old
        // [8]-only parse read 0x36 = 54 km = 34 mi (the reported bug). ETA = [9..10] = 0x00cd = 205.
        VehicleStatus.update(hex("02000000" + "100f0c0f0044131c36cd005078000000"))
        val s = VehicleStatus.state.value
        assertEquals(68, s.socPercent)
        assertEquals(VehicleStatus.ChargeState.CHARGING, s.chargeState)
        assertEquals(310, s.rangeKm)        // 0xcd36 & 0x1ff, NOT 0x36 (=54) and NOT 0xcd36 (=52534)
        assertEquals(0xcd, s.chargeTimeRaw) // [9..10] = 205, unchanged by the range mask
    }

    @Test
    fun chargeEtaSecondsUsesFifteenSecondsPerCount() {
        // Target = the charge LIMIT (a 70→90% A/B jumped raw 654→1184 at fixed SoC/current). The
        // 15 s/count (= raw/4 min) scale is pinned by a simultaneous app reading (10h 13m = 613 min
        // vs our settled raw 2468 ⇒ 14.9 s/count), and the field steps by 4 ⇒ 4×15 = clean 60 s.
        VehicleStatus.update(hex("09000000" + "11ffac0f0032131be3a004147800800c")) // 90%, raw 0x04a0=1184
        val s = VehicleStatus.state.value
        assertEquals(1184, s.chargeTimeRaw)
        assertEquals(1184 * 15, s.chargeEtaSeconds) // 17760 s = 296 min = 4h 56m
    }

    @Test
    fun telemetryIsNullWhenFrameTooShort() {
        // 4-byte counter + only status[0..3]: closures decode, telemetry stays null.
        VehicleStatus.update(frame(0x00, 0xff, 0xac, 0x0f))
        val s = VehicleStatus.state.value
        assertTrue(s.valid)
        assertNull(s.socPercent)
        assertNull(s.cabinTempC)
        assertNull(s.rangeKm)
        assertNull(s.chargeTimeRaw)
        assertEquals(VehicleStatus.ChargeState.UNKNOWN, s.chargeState)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun shortFrameIsIgnored() {
        VehicleStatus.update(frame(0x00, 0x0f, 0x04, 0x0f)) // establish a known live state
        val before = VehicleStatus.state.value
        VehicleStatus.update(ByteArray(7)) // < 8 bytes: not enough for status[0..3]
        assertEquals("short frame must not change state", before, VehicleStatus.state.value)
    }

    @Test
    fun clearMarksStaleButKeepsFields() {
        VehicleStatus.update(frame(0x00, 0xff, 0xac, 0x0f))
        VehicleStatus.clear()
        val s = VehicleStatus.state.value
        assertTrue("fields are retained", s.valid)
        assertFalse("but marked not live", s.live)
        assertTrue("closure state is unchanged", s.locked)
    }

    @Test
    fun clearIsIdempotentAndUpdateGoesLiveAgain() {
        VehicleStatus.update(frame(0x00, 0xff, 0xac, 0x0f))
        VehicleStatus.clear()
        val afterFirstClear = VehicleStatus.state.value
        VehicleStatus.clear() // already stale → no-op
        assertEquals(afterFirstClear, VehicleStatus.state.value)
        VehicleStatus.update(frame(0x00, 0xff, 0xac, 0x0f))
        assertTrue("a fresh frame re-marks the state live", VehicleStatus.state.value.live)
    }
}
