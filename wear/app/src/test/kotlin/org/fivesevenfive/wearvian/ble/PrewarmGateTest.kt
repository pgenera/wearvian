package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrewarmGateTest {
    private val gate = PrewarmGate()

    @Test
    fun wantedWhileItsVisitIsInView() {
        val v = gate.entered()
        assertTrue(gate.wanted(v, serviceRunning = false))
    }

    @Test
    fun swipePastBeforeTheBackgroundWorkRunsIsNotWanted() {
        val v = gate.entered()
        assertTrue("nobody waits → cancel", gate.left())
        assertFalse(gate.wanted(v, serviceRunning = false))
    }

    @Test
    fun leaveThenReenterOnlyTheNewVisitIsWanted() {
        val old = gate.entered()
        gate.left()
        val new = gate.entered()
        assertFalse(gate.wanted(old, serviceRunning = false))
        assertTrue(gate.wanted(new, serviceRunning = false))
    }

    @Test
    fun repeatedEnterKeepsTheSameVisit() {
        val v = gate.entered()
        assertEquals(v, gate.entered())
        assertTrue(gate.isCurrent(v))
    }

    @Test
    fun runningServiceOwnsTheLink() {
        val v = gate.entered()
        assertFalse(gate.wanted(v, serviceRunning = true))
        gate.commandWaiting()
        assertFalse(gate.wanted(v, serviceRunning = true))
    }

    @Test
    fun waitingTapKeepsThePrewarmAfterTheTileLeaves() {
        val v = gate.entered()
        gate.commandWaiting()
        assertFalse("a command waits → don't cancel", gate.left())
        assertTrue(gate.wanted(v, serviceRunning = false))
        gate.commandDone()
        assertFalse(gate.wanted(v, serviceRunning = false))
    }

    @Test
    fun commandDoneNeverGoesNegative() {
        gate.commandDone()
        val v = gate.entered()
        gate.left()
        assertFalse(gate.wanted(v, serviceRunning = false))
    }

    @Test
    fun freshnessUsesTheHoldWindow() {
        assertTrue(PrewarmGate.fresh(openedAtMs = 1_000, nowMs = 16_000, holdMs = 15_000))
        assertFalse("CPU slept past the window", PrewarmGate.fresh(1_000, 16_001, 15_000))
        assertFalse("clock went backwards", PrewarmGate.fresh(1_000, 999, 15_000))
    }
}
