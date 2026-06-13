package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VIN → body-style decode. The 4th VIN character is the line: T=R1T, S=R1S; anything else UNKNOWN
 * (treated as the R1S default by callers). The R1S vector is a real VIN from the capture archive.
 */
class VehicleModelTest {

    @Test
    fun realR1sVinDecodesToR1s() {
        // Confirmed R1S VIN from the capture archive (4th char = 'S').
        val m = VehicleModel.fromVin("7PDSGABA6PN021246")
        assertEquals(VehicleModel.R1S, m)
        assertFalse(m.isTruck)
    }

    @Test
    fun r1tVinDecodesToR1t() {
        // Same structure with the line char flipped to 'T'.
        val m = VehicleModel.fromVin("7PDTGABA6PN021246")
        assertEquals(VehicleModel.R1T, m)
        assertTrue(m.isTruck)
    }

    @Test
    fun caseInsensitive() {
        assertEquals(VehicleModel.R1T, VehicleModel.fromVin("7pdtgaba6pn021246"))
        assertEquals(VehicleModel.R1S, VehicleModel.fromVin("7pdsgaba6pn021246"))
    }

    @Test
    fun unknownOrShortVinIsUnknownAndNotTruck() {
        // Empty, too-short to have a 4th char, and an unrecognized line char all fall back to UNKNOWN.
        for (vin in listOf("", "7PD", "7PDXGABA6PN021246")) {
            val m = VehicleModel.fromVin(vin)
            assertEquals("'$vin' should be UNKNOWN", VehicleModel.UNKNOWN, m)
            assertFalse(m.isTruck)
        }
    }
}
