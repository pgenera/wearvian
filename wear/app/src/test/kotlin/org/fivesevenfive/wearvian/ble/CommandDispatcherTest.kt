package org.fivesevenfive.wearvian.ble

import org.fivesevenfive.wearvian.ble.CommandDispatcher.Route
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDispatcherTest {

    @Test
    fun lockedWatchBlocksEveryPath() {
        for (running in listOf(false, true)) for (passive in listOf(false, true)) {
            assertEquals(Route.BLOCKED_LOCKED, CommandDispatcher.route(watchLocked = true, running, passive))
        }
    }

    @Test
    fun activeServiceRidesTheLiveSession() {
        assertEquals(Route.LIVE_SESSION, CommandDispatcher.route(watchLocked = false, serviceRunning = true, passive = false))
    }

    @Test
    fun passiveOrStoppedUsesOneShot() {
        assertEquals(Route.ONE_SHOT, CommandDispatcher.route(watchLocked = false, serviceRunning = true, passive = true))
        assertEquals(Route.ONE_SHOT, CommandDispatcher.route(watchLocked = false, serviceRunning = false, passive = false))
    }

    @Test
    fun onlyUnlockWhileParkedNearbyWakesTheKey() {
        assertTrue(CommandDispatcher.wakesKeyAfterOneShot(Cmd.UNLOCK_ALL, parkedNearby = true))
        assertFalse(CommandDispatcher.wakesKeyAfterOneShot(Cmd.UNLOCK_ALL, parkedNearby = false))
        assertFalse(CommandDispatcher.wakesKeyAfterOneShot(Cmd.LOCK_ALL, parkedNearby = true))
    }

    @Test
    fun onlyGenericSendFailuresAreRetried() {
        assertTrue(CommandFailure.SEND_FAILED.retryable)
        assertEquals(CommandFailure.SEND_FAILED, ActiveCommandManager.failureOf(IllegalStateException("disconnected")))
        for (f in listOf(CommandFailure.WATCH_LOCKED, CommandFailure.NOT_BONDED, CommandFailure.NOT_REACHABLE)) {
            assertFalse(f.retryable)
            assertEquals(f, ActiveCommandManager.failureOf(CommandException(f, "x")))
        }
    }
}
