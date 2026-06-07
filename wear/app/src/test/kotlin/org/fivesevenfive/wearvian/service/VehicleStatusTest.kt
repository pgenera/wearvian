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
        // SoC 48.4%, range 137 mi (= 220 km), cabin 86°F (= 30°C).
        val frame = hex("01000000" + "100f0c0f0030111edc00005078000000")
        VehicleStatus.update(frame)
        val s = VehicleStatus.state.value
        assertEquals(48, s.socPercent)
        assertEquals(30, s.cabinTempC) // 30°C == 86°F
        assertEquals(220, s.rangeKm)   // 220 km == 137 mi
    }

    @Test
    fun rangeIsLittleEndian16Bit() {
        // status[8]=0x0a, status[9]=0x01 → 0x010a = 266 km (a documented prior capture)
        VehicleStatus.update(hex("00000000" + "10070c0f003b110e0a01005078000000"))
        assertEquals(266, VehicleStatus.state.value.rangeKm)
        assertEquals(59, VehicleStatus.state.value.socPercent)
        assertEquals(14, VehicleStatus.state.value.cabinTempC)
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
