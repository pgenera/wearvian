package org.fivesevenfive.wearvian.ble

import org.fivesevenfive.wearvian.ble.HeartbeatWriteGate.Pending
import org.fivesevenfive.wearvian.ble.HeartbeatWriteGate.Result
import org.fivesevenfive.wearvian.ble.HeartbeatWriteGate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatWriteGateTest {
    private val g = HeartbeatWriteGate()

    @Test
    fun healthyBeatsJustWrite() {
        repeat(10) {
            assertEquals(Step.WRITE, g.beforeBeat(it * 300L, Pending.NONE))
            assertFalse(g.after(Result.OK, it * 300L, it * 300L + 20))
        }
    }

    @Test
    fun oneStalledWriteNoLongerCyclesTheLinkWithinThreeSeconds() {
        // The audit's cascade: write at 0 times out at 1.5 s, then beats every 0.3 s.
        assertEquals(Step.WRITE, g.beforeBeat(0L, Pending.NONE))
        assertFalse(g.after(Result.TIMED_OUT, 0L, 1_500L))
        var t = 1_800L
        while (t < HeartbeatWriteGate.STALL_MAX_MS) {
            assertEquals("skip while our write is pending (t=$t)", Step.SKIP, g.beforeBeat(t, Pending.PENDING))
            t += 300L
        }
        assertEquals(Step.CYCLE, g.beforeBeat(HeartbeatWriteGate.STALL_MAX_MS, Pending.PENDING))
    }

    @Test
    fun aLateCompletionResumesNormally() {
        g.after(Result.TIMED_OUT, 0L, 1_500L)
        assertEquals(Step.SKIP, g.beforeBeat(1_800L, Pending.PENDING))
        assertEquals(Step.WRITE, g.beforeBeat(2_100L, Pending.DONE_OK))
        assertEquals(0, g.failures)
        assertEquals(Step.WRITE, g.beforeBeat(2_400L, Pending.NONE))
    }

    @Test
    fun aLateCompletionConsumedElsewhereReadsAsDone() {
        g.after(Result.TIMED_OUT, 0L, 1_500L)
        assertEquals(Step.WRITE, g.beforeBeat(2_000L, Pending.NONE)) // the command drain took it
    }

    @Test
    fun busyIsNotAStallUnlessItPersists() {
        assertFalse(g.after(Result.BUSY, 0L, 0L))
        assertFalse(g.after(Result.BUSY, 300L, 300L))
        assertEquals(0, g.failures)
        assertFalse(g.after(Result.OK, 600L, 620L)) // cleared
        assertFalse(g.after(Result.BUSY, 1_000L, 1_000L))
        assertFalse(g.after(Result.BUSY, 1_000L + HeartbeatWriteGate.STALL_MAX_MS - 1, 1_000L + HeartbeatWriteGate.STALL_MAX_MS - 1))
        assertTrue(g.after(Result.BUSY, 1_000L + HeartbeatWriteGate.STALL_MAX_MS, 1_000L + HeartbeatWriteGate.STALL_MAX_MS))
    }

    @Test
    fun realFailuresStillCycleAfterFiveInARow() {
        repeat(HeartbeatWriteGate.MAX_FAILURES - 1) { assertFalse(g.after(Result.FAILED, 0L, 0L)) }
        assertTrue(g.after(Result.FAILED, 0L, 0L))
    }

    @Test
    fun aSuccessResetsTheFailureRun() {
        repeat(HeartbeatWriteGate.MAX_FAILURES - 1) { g.after(Result.FAILED, 0L, 0L) }
        g.after(Result.OK, 0L, 0L)
        assertFalse(g.after(Result.FAILED, 0L, 0L))
    }

    @Test
    fun aPendingWriteThatFailsCountsOnce() {
        g.after(Result.TIMED_OUT, 0L, 1_500L)
        assertEquals(Step.WRITE, g.beforeBeat(2_000L, Pending.DONE_FAILED))
        assertEquals(1, g.failures)
    }
}
