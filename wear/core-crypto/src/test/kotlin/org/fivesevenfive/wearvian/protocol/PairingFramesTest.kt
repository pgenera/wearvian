package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingFramesTest {

    @Test
    fun normalizeStripsDashesAndLowercases() {
        assertEquals("aabbccdd", PairingFrames.normalizeId("AABB-CCDD"))
    }

    @Test
    fun phoneIdBytesEncodeHex() {
        val bytes = PairingFrames.phoneIdBytes("00FF-10")
        assertEquals("00ff10", RivianCrypto.toHex(bytes))
    }

    @Test
    fun vehicleIdMatchIsCaseAndDashInsensitive() {
        val received = RivianCrypto.fromHex("0a1b2c3d")
        assertTrue(PairingFrames.vehicleIdMatches(received, "0A1B-2C3D"))
        assertFalse(PairingFrames.vehicleIdMatches(received, "ffffffff"))
    }

    @Test
    fun pairingWriteConcatenatesNonceThenHmac() {
        val nonce = ByteArray(16) { it.toByte() }
        val hmac = ByteArray(32) { (it + 100).toByte() }
        val frame = PairingFrames.pairingWrite(nonce, hmac)
        assertEquals(48, frame.size)
        assertEquals(RivianCrypto.toHex(nonce) + RivianCrypto.toHex(hmac), RivianCrypto.toHex(frame))
    }

    @Test
    fun pairingWriteRejectsWrongSizes() {
        assertFailsWith<IllegalArgumentException> {
            PairingFrames.pairingWrite(ByteArray(15), ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            PairingFrames.pairingWrite(ByteArray(16), ByteArray(31))
        }
    }
}
