package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode tests for the full (type-0x18) vehicle status. The exact frame layout is speculative until
 * confirmed on-vehicle (see [FullVehicleStatus]), so these pin the SCHEMA_VERSION_1 assembly we
 * implemented against synthetic frames — they lock the bit math, not real captures.
 *
 * The decrypted plaintext carries the 35-byte status at offset 4, so status index i = plaintext[4+i].
 */
class FullVehicleStatusTest {

    /** Build a decrypted plaintext: 4-byte prefix + 35 status bytes (indices set via [set]). */
    private fun plaintext(set: Map<Int, Int>): ByteArray {
        val b = ByteArray(4 + 35)
        for ((i, v) in set) b[4 + i] = v.toByte()
        return b
    }

    @Test
    fun parsesNextActionsAndChargePort() {
        // s[0]=0x10 version; s[2] bit0 clear = charge-port door open; s[28]=0x02 & s[27]=0 → frunk
        // raw 8 = CLOSE_BLOCKED; s[29]=0x43 → liftgate 3 (opening) + windows 4 (closing);
        // s[31]=0x80 & s[32]=0 → charge-port-control raw 1 = OPEN; s[33]=0x40 → charge-port door
        // action raw 4 = OPENING.
        FullVehicleStatus.update(
            plaintext(mapOf(0 to 0x10, 2 to 0x00, 28 to 0x02, 29 to 0x43, 31 to 0x80, 33 to 0x40)),
        )
        val f = FullVehicleStatus.state.value
        assertTrue(f.valid)
        assertEquals(0x10, f.schemaVersion)
        assertTrue(f.chargePortDoorOpen)
        assertEquals(FullVehicleStatus.ChargePort.OPEN, f.chargePort)
        assertEquals(FullVehicleStatus.Motion.CLOSE_BLOCKED, f.frunk)
        assertEquals(FullVehicleStatus.Motion.OPENING, f.liftgate)
        assertEquals(FullVehicleStatus.Motion.CLOSING, f.windows)
        assertEquals(FullVehicleStatus.Motion.OPENING, f.chargePortDoor)
        assertTrue(f.anyMoving)
    }

    @Test
    fun idleWhenAllClear() {
        FullVehicleStatus.update(plaintext(mapOf(0 to 0x10, 2 to 0x01))) // s[2] bit0 set = port closed
        val f = FullVehicleStatus.state.value
        assertTrue(f.valid)
        assertFalse(f.chargePortDoorOpen)
        assertEquals(FullVehicleStatus.Motion.IDLE, f.frunk)
        assertEquals(FullVehicleStatus.Motion.IDLE, f.liftgate)
        assertFalse(f.anyMoving)
    }

    @Test
    fun shortPlaintextDoesNotClobberState() {
        FullVehicleStatus.update(plaintext(mapOf(0 to 0x10))) // establish a valid state
        assertTrue(FullVehicleStatus.state.value.valid)
        FullVehicleStatus.update(ByteArray(20)) // too short for the schema (< 39B)
        assertTrue("a short frame must not overwrite the last good status", FullVehicleStatus.state.value.valid)
    }
}
