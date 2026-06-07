package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
