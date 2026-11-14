package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.BedtimeState.ACTIVE
import org.fivesevenfive.wearvian.service.BedtimeState.INACTIVE
import org.fivesevenfive.wearvian.service.BedtimeState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimePolicyTest {

    @Test
    fun eitherKeyOnIsActive() {
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = 1, samsung = -1))
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = -1, samsung = 1))
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = null, samsung = 1))
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = 1, samsung = null))
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = 0, samsung = 1))
        assertEquals(ACTIVE, BedtimePolicy.state(pixel = 1, samsung = 1))
    }

    @Test
    fun aPresentKeyThatIsNotOnIsInactive() {
        assertEquals(INACTIVE, BedtimePolicy.state(pixel = 0, samsung = -1))
        assertEquals(INACTIVE, BedtimePolicy.state(pixel = -1, samsung = 0))
        assertEquals(INACTIVE, BedtimePolicy.state(pixel = null, samsung = 0))
        assertEquals(INACTIVE, BedtimePolicy.state(pixel = 0, samsung = 0))
        assertEquals(INACTIVE, BedtimePolicy.state(pixel = 2, samsung = -1)) // only 1 means on
    }

    @Test
    fun neitherKeyPresentOrReadableIsUnknown() {
        assertEquals(UNKNOWN, BedtimePolicy.state(pixel = -1, samsung = -1))
        assertEquals(UNKNOWN, BedtimePolicy.state(pixel = null, samsung = null))
        assertEquals(UNKNOWN, BedtimePolicy.state(pixel = null, samsung = -1))
        assertEquals(UNKNOWN, BedtimePolicy.state(pixel = -1, samsung = null))
    }

    @Test
    fun keyTurnsOffOnlyOnTheRisingEdge() {
        assertTrue(BedtimePolicy.shouldTurnKeyOff(INACTIVE, ACTIVE))
        assertTrue(BedtimePolicy.shouldTurnKeyOff(UNKNOWN, ACTIVE))
        // Already in bedtime: a key the user turned back on stays on.
        assertFalse(BedtimePolicy.shouldTurnKeyOff(ACTIVE, ACTIVE))
        // Bedtime ending, or never readable, never touches the key.
        for (prev in BedtimeState.values()) {
            assertFalse(BedtimePolicy.shouldTurnKeyOff(prev, INACTIVE))
            assertFalse(BedtimePolicy.shouldTurnKeyOff(prev, UNKNOWN))
        }
    }

    @Test
    fun backgroundStartsBlockedOnlyDuringBedtime() {
        assertFalse(BedtimePolicy.allowBackgroundStart(ACTIVE))
        assertTrue(BedtimePolicy.allowBackgroundStart(INACTIVE))
        assertTrue(BedtimePolicy.allowBackgroundStart(UNKNOWN))
    }
}
