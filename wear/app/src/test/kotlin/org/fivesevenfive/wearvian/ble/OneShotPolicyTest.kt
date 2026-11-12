package org.fivesevenfive.wearvian.ble

import org.fivesevenfive.wearvian.ble.OneShotPolicy.DEADLINE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotPolicyTest {
    private val tap = 1_000_000L
    private val deadline = OneShotPolicy.deadline(tap)

    @Test
    fun deadlineIsGenerousButUnderTheStacksConnectLimit() {
        assertEquals(tap + 28_000L, deadline)
        assertTrue("our timeout must decide, not the stack's ~30 s", DEADLINE_MS < 30_000L)
    }

    @Test
    fun expiryAndRemainingUseTheMonotonicClock() {
        assertFalse(OneShotPolicy.expired(deadline, tap + DEADLINE_MS - 1))
        assertTrue(OneShotPolicy.expired(deadline, tap + DEADLINE_MS))
        assertEquals(1L, OneShotPolicy.remaining(deadline, tap + DEADLINE_MS - 1))
        assertEquals(0L, OneShotPolicy.remaining(deadline, tap + 60_000L)) // never negative
    }

    @Test
    fun slowConnectFailureIsNotReachableAndNotRetried() {
        // Android's ~30 s direct-connect give-up (or anything slower than the quick window).
        val f = OneShotPolicy.connectFailure(startedMs = tap, failedMs = tap + 27_000L)
        assertEquals(CommandFailure.NOT_REACHABLE, f)
        assertFalse(OneShotPolicy.shouldRetry(f, deadline, tap + 27_000L, pauseMs = 500L))
    }

    @Test
    fun quickConnectFailureGetsOneRetryWhileDeadlineRemains() {
        val f = OneShotPolicy.connectFailure(startedMs = tap, failedMs = tap + 800L) // status 133
        assertEquals(CommandFailure.SEND_FAILED, f)
        assertTrue(OneShotPolicy.shouldRetry(f, deadline, tap + 800L, pauseMs = 500L))
    }

    @Test
    fun noRetryWhenTooLittleDeadlineIsLeft() {
        val now = deadline - OneShotPolicy.MIN_RETRY_WINDOW_MS // exactly the window, before the pause
        assertFalse(OneShotPolicy.shouldRetry(CommandFailure.SEND_FAILED, deadline, now, pauseMs = 500L))
        assertTrue(OneShotPolicy.shouldRetry(CommandFailure.SEND_FAILED, deadline, now, pauseMs = 0L))
        assertFalse(OneShotPolicy.shouldRetry(CommandFailure.SEND_FAILED, deadline, deadline + 1, pauseMs = 0L))
    }

    @Test
    fun nonRetryableFailuresAreNeverRetried() {
        for (f in CommandFailure.entries.filter { !it.retryable }) {
            assertFalse(f.name, OneShotPolicy.shouldRetry(f, deadline, tap, pauseMs = 0L))
        }
        assertFalse(CommandFailure.EXPIRED.retryable)
    }

    @Test
    fun prewarmKeepsItsOwnWindowWithNoTapWaiting() {
        assertEquals(tap + 10_000L, OneShotPolicy.prewarmConnectUntil(tap + 10_000L, null))
    }

    @Test
    fun aTapQueuedBehindAPrewarmExtendsItsConnectToTheTapsDeadline() {
        val tapDuringPrewarm = OneShotPolicy.deadline(tap + 4_000L)
        assertEquals(tapDuringPrewarm, OneShotPolicy.prewarmConnectUntil(tap + 10_000L, tapDuringPrewarm))
        // Never shortened by a tap.
        assertEquals(tap + 10_000L, OneShotPolicy.prewarmConnectUntil(tap + 10_000L, tap + 2_000L))
    }

    @Test
    fun gateReportsTheLatestWaitingDeadline() {
        val gate = PrewarmGate()
        assertNull(gate.latestWaitingDeadline())
        gate.commandWaiting(5_000L)
        gate.commandWaiting(9_000L)
        assertEquals(9_000L, gate.latestWaitingDeadline())
        gate.commandDone(9_000L)
        assertEquals(5_000L, gate.latestWaitingDeadline())
        gate.commandDone(5_000L)
        assertNull(gate.latestWaitingDeadline())
    }
}
