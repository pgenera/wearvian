package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reliability audit 2026-11-12, finding R4. FAILS on 5271d02.
 *
 * Walking up to the car, the first connects happen at the edge of range, where the link comes up but
 * the handshake often times out. Each of those counts toward the same exponential backoff as "the
 * car is not there at all", so by the time the wearer reaches the door the session can be sitting
 * out a 12-48 s delay with no connection attempt pending. A link that CONNECTS proves the module is
 * awake and in range; the first few handshake failures after a connect should retry quickly.
 */
class ReliabilityAuditReconnectTest {

    private fun worstCaseDelay(p: ReconnectPolicy): Long = (1..50).maxOf { p.nextDelayMs() }

    @Test
    fun primaryRetriesQuicklyAfterAFewHandshakeFailuresOnAConnectedLink() {
        val p = ReconnectPolicy(primary = true, random = Random(7))
        p.record(ReconnectPolicy.Outcome.NO_CONNECT)   // away: direct connect failed, now on autoConnect
        repeat(3) { p.record(ReconnectPolicy.Outcome.CONNECTED) } // back at the edge of range: 3 handshakes fail
        val d = worstCaseDelay(p)
        assertTrue("retry delay after 3 connected-but-unauthenticated attempts was $d ms", d <= 5_000L)
    }

    @Test
    fun sensorThatWasUnreachableRetriesQuicklyOnceItConnects() {
        val p = ReconnectPolicy(primary = false, random = Random(7))
        repeat(ReconnectPolicy.SENSOR_AUTOCONNECT_AFTER) { p.record(ReconnectPolicy.Outcome.NO_CONNECT) } // away
        p.record(ReconnectPolicy.Outcome.CONNECTED) // back in range: first handshake at the edge fails
        val d = worstCaseDelay(p)
        assertTrue("retry delay after the first handshake failure on return was $d ms", d <= 5_000L)
    }
}
