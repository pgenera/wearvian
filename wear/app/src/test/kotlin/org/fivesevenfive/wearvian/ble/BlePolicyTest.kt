package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    @Test
    fun primaryFallsBackToAutoConnectAfterOneFailedConnect() {
        val p = ReconnectPolicy(primary = true)
        assertFalse(p.useAutoConnect)
        p.record(ReconnectPolicy.Outcome.NO_CONNECT)
        assertTrue(p.useAutoConnect)
    }

    @Test
    fun handshakeFailureDoesNotForceAutoConnect() {
        val p = ReconnectPolicy(primary = true)
        p.record(ReconnectPolicy.Outcome.NO_CONNECT)
        p.record(ReconnectPolicy.Outcome.CONNECTED) // connected, then the handshake threw
        assertFalse(p.useAutoConnect)
    }

    @Test
    fun sensorsSwitchToAutoConnectAfterAFewFailedConnects() {
        val p = ReconnectPolicy(primary = false)
        repeat(ReconnectPolicy.SENSOR_AUTOCONNECT_AFTER - 1) { p.record(ReconnectPolicy.Outcome.NO_CONNECT) }
        assertFalse(p.useAutoConnect)
        p.record(ReconnectPolicy.Outcome.NO_CONNECT)
        assertTrue(p.useAutoConnect)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val p = ReconnectPolicy(primary = false, random = Random(1))
        assertEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
        var prevUpper = 0L
        repeat(12) {
            p.record(ReconnectPolicy.Outcome.NO_CONNECT)
            val d = p.nextDelayMs()
            assertTrue(d in ReconnectPolicy.BASE_MS..ReconnectPolicy.MAX_MS)
            prevUpper = maxOf(prevUpper, d)
        }
        assertTrue("reaches the cap region", prevUpper >= ReconnectPolicy.MAX_MS * 3 / 4)
    }

    @Test
    fun backoffResetsOnlyOnAuthentication() {
        val p = ReconnectPolicy(primary = false, random = Random(2))
        repeat(5) { p.record(ReconnectPolicy.Outcome.NO_CONNECT) }
        p.record(ReconnectPolicy.Outcome.CONNECTED)
        assertEquals(6, p.failures)
        assertNotEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
        p.record(ReconnectPolicy.Outcome.AUTHENTICATED)
        assertEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
    }
}

class RescanScheduleTest {

    @Test
    fun backsOffWhenNothingNewAndResetsOnNewSensor() {
        var ms = RescanSchedule.MIN_MS
        ms = RescanSchedule.next(ms, foundNew = false)
        assertEquals(30_000L, ms)
        repeat(10) { ms = RescanSchedule.next(ms, foundNew = false) }
        assertEquals(RescanSchedule.MAX_MS, ms)
        assertEquals(RescanSchedule.MIN_MS, RescanSchedule.next(ms, foundNew = true))
    }

    @Test
    fun failedScanKeepsTheInterval() {
        assertEquals(60_000L, RescanSchedule.next(60_000L, foundNew = null))
    }
}

class SessionLabelsTest {

    @Test
    fun namedSensorsKeepTheirShortLabel() {
        val used = mutableSetOf("PK")
        assertEquals("S1", SessionLabels.unique("Rivian Sensor 1", "AA:BB:CC:DD:EE:01", used))
        assertEquals("S2", SessionLabels.unique("Rivian Sensor 2", "AA:BB:CC:DD:EE:02", used))
    }

    @Test
    fun namelessSensorsNeverCollide() {
        val used = mutableSetOf<String>()
        val a = SessionLabels.unique(null, "AA:BB:CC:DD:EE:01", used)
        val b = SessionLabels.unique(null, "AA:BB:CC:DD:EE:02", used)
        assertNotEquals(a, b)
        assertEquals("S?-EE01", a)
    }

    @Test
    fun duplicateNamesAreDisambiguated() {
        val used = mutableSetOf<String>()
        val a = SessionLabels.unique("Rivian Sensor 1", "AA:BB:CC:DD:EE:01", used)
        val b = SessionLabels.unique("Rivian Sensor 1", "AA:BB:CC:DD:EE:02", used)
        val c = SessionLabels.unique("Something", "AA:BB:CC:DD:EE:03", used)
        val d = SessionLabels.unique("Something", "AA:BB:CC:DD:EE:03", used)
        assertEquals(4, setOf(a, b, c, d).size)
        assertEquals("PK2", c)
    }
}
