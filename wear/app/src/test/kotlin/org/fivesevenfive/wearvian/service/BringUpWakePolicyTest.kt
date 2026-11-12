package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.PresenceStatus.Link
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BringUpWakePolicyTest {
    private fun drop(prev: Link?, now: Link?, held: Boolean = false, active: Boolean = true, paused: Boolean = true) =
        BringUpWakePolicy.onPrimaryChange(prev, now, held, active, paused)

    @Test
    fun sessionsStartingWithoutTheMainWakeLockTakeIt() {
        assertTrue(BringUpWakePolicy.onSessionsStart(mainWakeLockHeld = false))
        assertFalse(BringUpWakePolicy.onSessionsStart(mainWakeLockHeld = true))
    }

    @Test
    fun aDroppedLinkInStandbyOrDozeTakesIt() {
        assertTrue(drop(Link.UP, Link.DOWN))
        assertTrue("DOWN conflated into the reconnect's CONNECTING", drop(Link.UP, Link.CONNECTING))
    }

    @Test
    fun notWhenTheMainWakeLockIsHeldOrHeartbeatsRunOrNotActive() {
        assertFalse(drop(Link.UP, Link.DOWN, held = true))
        assertFalse(drop(Link.UP, Link.DOWN, paused = false))
        assertFalse(drop(Link.UP, Link.DOWN, active = false))
    }

    @Test
    fun aFailedConnectAttemptIsNotADrop() {
        // An absent car cycles CONNECTING → DOWN → CONNECTING: must not keep re-taking the lock.
        assertFalse(drop(Link.CONNECTING, Link.DOWN))
        assertFalse(drop(Link.DOWN, Link.CONNECTING))
        assertFalse(drop(Link.CONNECTED, Link.DOWN))
        assertFalse(drop(null, Link.CONNECTING))
        assertFalse(drop(Link.UP, Link.UP))
    }

    @Test
    fun releasedOnceUpAndBounded() {
        assertTrue(BringUpWakePolicy.done(Link.UP))
        assertFalse(BringUpWakePolicy.done(Link.CONNECTED))
        assertFalse(BringUpWakePolicy.done(null))
        assertTrue(BringUpWakePolicy.MAX_MS <= 30_000L)
    }
}
