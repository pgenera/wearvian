package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RivianBleTest {

    @Test
    fun dashedUuidParsesAsIs() {
        val u = "01234567-89ab-cdef-0123-456789abcdef"
        assertEquals(UUID.fromString(u), RivianBle.vehicleServiceUuid(u))
    }

    @Test
    fun bare32HexGetsDashed() {
        val expected = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        assertEquals(expected, RivianBle.vehicleServiceUuid("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun uppercaseAnd0xPrefixAreNormalized() {
        val expected = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        assertEquals(expected, RivianBle.vehicleServiceUuid("0x0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        val expected = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        assertEquals(expected, RivianBle.vehicleServiceUuid("  0123456789abcdef0123456789abcdef  "))
    }

    @Test
    fun wrongLengthHexReturnsNull() {
        assertNull(RivianBle.vehicleServiceUuid("0123456789abcdef")) // 16 chars, not 32
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(RivianBle.vehicleServiceUuid("not-a-uuid"))
        assertNull(RivianBle.vehicleServiceUuid(""))
    }

    @Test
    fun protocolConstantsAreStable() {
        // Guard the on-the-wire identifiers against accidental edits.
        assertEquals("Rivian Phone Key", RivianBle.DEVICE_NAME)
        assertEquals("52495356-454e-534f-5253-455256494345", RivianBle.SERVICE_ACTIVE_ENTRY.toString())
        assertEquals("5ae32b92-eafb-471b-afe8-e88eec4a4774", RivianBle.CHAR_ACTIVE_COMMAND.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", RivianBle.CCCD.toString())
    }
}
