package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorBookTest {
    private val pk = "AA:00:00:00:00:09"

    /** A loop that started the PK and two sensors (one seeded, one found by the scan). */
    private fun running(): SensorBook<String> = SensorBook<String>().apply {
        primary(pk, "PK")
        seeded = true
        seeder = "seeder"
        started.add("AA:00:00:00:00:01"); labels.add("S1"); sensor("AA:00:00:00:00:01", "S1", "job1")
        started.add("AA:00:00:00:00:02"); labels.add("S2"); sensor("AA:00:00:00:00:02", "S2", "job2")
        rescanMs = RescanSchedule.MAX_MS
        lastScanAt = 12_345L
    }

    @Test
    fun closeSensorsReturnsEverySensorAndTheSeederButNeverThePrimary() {
        val closed = running().closeSensors()
        assertEquals(listOf("S1", "S2"), closed.labels)
        assertEquals(listOf("seeder", "job1", "job2"), closed.handles)
    }

    @Test
    fun closeSensorsKeepsOnlyThePrimaryInTheBookkeeping() {
        val book = running()
        book.closeSensors()
        assertEquals(setOf(pk), book.started)
        assertEquals(setOf("PK"), book.labels)
        assertEquals(0, book.sensorCount)
        assertNull(book.seeder)
    }

    @Test
    fun closeSensorsResetsSeedingAndMakesTheScanDueAtOnce() {
        val book = running()
        book.closeSensors()
        assertFalse(book.seeded)
        assertEquals(0L, book.lastScanAt)
        assertEquals(RescanSchedule.MIN_MS, book.rescanMs)
    }

    @Test
    fun aSeedClaimedButNotYetLaunchedIsReleasedToo() {
        // The seeder adds every saved address to `started` up front, then launches them staggered.
        val book = SensorBook<String>().apply { primary(pk, "PK"); started.add("AA:00:00:00:00:03") }
        book.closeSensors()
        assertEquals(setOf(pk), book.started)
    }

    @Test
    fun afterCloseTheSavedSensorsAreSeededAgainButThePhoneKeyNeverIs() {
        val book = running()
        book.closeSensors()
        val saved = setOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", pk.lowercase())
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), SensorSeed.toStart(saved, pk, book.started))
        // Even without the stored PK address, the running PRIMARY stays in `started`.
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), SensorSeed.toStart(saved, null, book.started))
    }

    @Test
    fun reopenedSensorsGetTheirOldLabelsBack() {
        val book = running()
        book.closeSensors()
        assertEquals("S1", SessionLabels.unique("Rivian Sensor 1", "AA:00:00:00:00:01", book.labels))
    }

    @Test
    fun closingWithNoSensorsIsANoOp() {
        val book = SensorBook<String>().apply { primary(pk, "PK") }
        val closed = book.closeSensors()
        assertTrue(closed.labels.isEmpty())
        assertTrue(closed.handles.isEmpty())
        assertEquals(setOf(pk), book.started)
    }
}
