package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorSeedTest {
    @Test
    fun skipsThePhoneKeyAndRunningSessionsCaseInsensitively() {
        val saved = setOf("AA:00:00:00:00:01", "aa:00:00:00:00:02", "AA:00:00:00:00:03", "AA:00:00:00:00:09")
        val toStart = SensorSeed.toStart(saved, pkAddress = "aa:00:00:00:00:09", started = setOf("AA:00:00:00:00:02"))
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:03"), toStart)
    }

    @Test
    fun nothingSavedNothingToStart() {
        assertEquals(emptyList<String>(), SensorSeed.toStart(emptySet(), null, emptySet()))
    }
}
