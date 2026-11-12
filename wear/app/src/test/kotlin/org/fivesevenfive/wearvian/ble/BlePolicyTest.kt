package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.async
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
        // New rule (reliability audit R5): a connect forgets the NO_CONNECT backoff and retries
        // quickly a few times, then backs off again; only authentication resets everything.
        val p = ReconnectPolicy(primary = false, random = Random(2))
        repeat(5) { p.record(ReconnectPolicy.Outcome.NO_CONNECT) }
        assertNotEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
        p.record(ReconnectPolicy.Outcome.CONNECTED)
        assertEquals("earlier NO_CONNECTs no longer count", 1, p.failures)
        assertTrue(p.nextDelayMs() <= 3_000L)
        repeat(ReconnectPolicy.QUICK_CONNECTED_RETRIES - 1) {
            p.record(ReconnectPolicy.Outcome.CONNECTED)
            assertTrue("still quick within the first few", p.nextDelayMs() <= 3_000L)
        }
        repeat(3) { p.record(ReconnectPolicy.Outcome.CONNECTED) } // a sensor that never authenticates
        assertTrue("backs off again after that", p.nextDelayMs() > 3_000L)
        p.record(ReconnectPolicy.Outcome.AUTHENTICATED)
        assertEquals(0, p.failures)
        assertEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
    }

    @Test
    fun aPermanentlyBrokenSensorStillReachesTheCap() {
        val p = ReconnectPolicy(primary = false, random = Random(3))
        repeat(20) { p.record(ReconnectPolicy.Outcome.CONNECTED) }
        assertTrue(p.nextDelayMs() >= ReconnectPolicy.MAX_MS * 3 / 4)
    }

    @Test
    fun aNoConnectAfterAConnectedRunKeepsBackingOff() {
        val p = ReconnectPolicy(primary = false, random = Random(4))
        repeat(2) { p.record(ReconnectPolicy.Outcome.CONNECTED) }
        p.record(ReconnectPolicy.Outcome.NO_CONNECT) // walked off again
        assertEquals(3, p.failures)
        assertEquals(0, p.connectedFailures)
        assertTrue(p.nextDelayMs() > ReconnectPolicy.BASE_MS * 2)
    }
}

class ReconnectStableSessionTest {
    @Test
    fun aSessionDroppedRightAfterAuthenticatingDoesNotResetTheBackoff() {
        val p = ReconnectPolicy(primary = true, random = Random(5))
        repeat(ReconnectPolicy.QUICK_CONNECTED_RETRIES + 3) {
            p.record(ReconnectPolicy.Outcome.AUTHENTICATED, authenticatedForMs = 500L)
        }
        assertTrue("backs off instead of retrying every 1.5 s forever", p.nextDelayMs() > 3_000L)
        assertFalse("still counts as connected: no autoConnect fallback", p.useAutoConnect)
    }

    @Test
    fun aSessionThatStayedUpResetsTheBackoff() {
        val p = ReconnectPolicy(primary = true, random = Random(5))
        repeat(8) { p.record(ReconnectPolicy.Outcome.NO_CONNECT) }
        p.record(ReconnectPolicy.Outcome.AUTHENTICATED, authenticatedForMs = ReconnectPolicy.MIN_STABLE_SESSION_MS)
        assertEquals(0, p.failures)
        assertEquals(ReconnectPolicy.BASE_MS, p.nextDelayMs())
    }

    @Test
    fun aShortAuthenticatedSessionStillRetriesQuicklyAtFirst() {
        val p = ReconnectPolicy(primary = true, random = Random(5))
        p.record(ReconnectPolicy.Outcome.AUTHENTICATED, authenticatedForMs = 500L)
        assertTrue(p.nextDelayMs() <= 3_000L)
    }
}

class ReconnectHurryTest {
    @Test
    fun hurryCutsAWaitShort() = kotlinx.coroutines.runBlocking {
        val waiter = async(kotlinx.coroutines.Dispatchers.Default) { ReconnectHurry.delayUnlessHurried(30_000L) }
        // Signal until the waiter has subscribed and returned (it snapshots the generation first).
        while (!waiter.isCompleted) { ReconnectHurry.hurry(); kotlinx.coroutines.delay(10) }
        assertTrue(waiter.await())
    }

    @Test
    fun anUnhurriedWaitRunsOut() = kotlinx.coroutines.runBlocking {
        assertFalse(ReconnectHurry.delayUnlessHurried(20L))
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
