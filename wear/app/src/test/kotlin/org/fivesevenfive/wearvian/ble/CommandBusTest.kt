package org.fivesevenfive.wearvian.ble

import android.os.SystemClock
import org.junit.After
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandBusTest {

    private var nowMs = 1_000L

    @Before
    fun setUp() {
        nowMs = 1_000L
        CommandBus.clock = { nowMs }
        CommandBus.clear()
    }

    @After
    fun tearDown() {
        CommandBus.clear()
        CommandBus.clock = { SystemClock.elapsedRealtime() }
    }

    @Test
    fun pollEmptyReturnsNull() {
        assertNull(CommandBus.poll())
    }

    @Test
    fun submittedCommandsDrainInFifoOrder() {
        CommandBus.submit(0x03, "unlock")
        CommandBus.submit(0x06, "lock")

        val first = CommandBus.poll()
        val second = CommandBus.poll()
        assertEquals(CommandBus.Command(0x03, "unlock", 1_000L), first)
        assertEquals(CommandBus.Command(0x06, "lock", 1_000L), second)
        assertNull(CommandBus.poll())
    }

    @Test
    fun freshCommandIsDelivered() {
        CommandBus.submit(0x03, "unlock")
        nowMs += CommandBus.COMMAND_TTL_MS

        assertEquals(CommandBus.Command(0x03, "unlock", 1_000L), CommandBus.poll())
        assertNull(CommandBus.poll())
    }

    @Test
    fun expiredCommandIsDropped() {
        CommandBus.submit(0x03, "unlock")
        nowMs += CommandBus.COMMAND_TTL_MS + 1

        assertNull(CommandBus.poll())
    }

    @Test
    fun expiredThenFreshReturnsFreshOne() {
        CommandBus.submit(0x03, "unlock")
        nowMs += 3_000L
        CommandBus.submit(0x06, "lock")
        nowMs += 2_500L // unlock is 5,500 ms old (> TTL), lock is 2,500 ms old (<= TTL)

        assertEquals(CommandBus.Command(0x06, "lock", 4_000L), CommandBus.poll())
        assertNull(CommandBus.poll())
    }

    @Test
    fun clearEmptiesQueue() {
        CommandBus.submit(0x03, "unlock")
        CommandBus.submit(0x06, "lock")

        CommandBus.clear()

        assertNull(CommandBus.poll())
    }

    @Test
    fun sentCommandReportsSuccess() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        val polled = CommandBus.poll()!!
        CommandBus.markSent(polled, true)
        assertTrue(CommandBus.awaitOutcome(cmd, pickupMs = 1_000, writeMs = 1_000))
    }

    @Test
    fun failedWriteReportsFailure() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        CommandBus.markSent(CommandBus.poll()!!, false)
        assertFalse(CommandBus.awaitOutcome(cmd, pickupMs = 1_000, writeMs = 1_000))
    }

    @Test
    fun staleDropReportsFailure() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        nowMs += CommandBus.COMMAND_TTL_MS + 1
        assertNull(CommandBus.poll())
        assertFalse(CommandBus.awaitOutcome(cmd, pickupMs = 1_000, writeMs = 1_000))
    }

    @Test
    fun clearReportsFailure() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        CommandBus.clear()
        assertFalse(CommandBus.awaitOutcome(cmd, pickupMs = 1_000, writeMs = 1_000))
    }

    @Test
    fun unclaimedCommandTimesOutAndIsNeverSentLate() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        // No drainer picks it up within the pickup window → failure ...
        assertFalse(CommandBus.awaitOutcome(cmd, pickupMs = 20, writeMs = 20))
        // ... and a drainer that attaches afterwards (still inside the TTL) must not fire it.
        assertNull(CommandBus.poll())
    }

    @Test
    fun claimedCommandGetsTheWriteWindow() = runBlocking {
        val cmd = CommandBus.submit(0x03, "unlock")
        val polled = CommandBus.poll()!! // claimed by the drainer, write still in progress
        val waiter = async { CommandBus.awaitOutcome(cmd, pickupMs = 20, writeMs = 2_000) }
        delay(100) // past the pickup window
        CommandBus.markSent(polled, true)
        assertTrue(waiter.await())
    }

    @Test
    fun noteManualRecordsAManualActionWithoutQueueing() {
        nowMs = 42_000L
        CommandBus.noteManual()
        assertEquals(42_000L, CommandBus.lastSubmitMs)
        assertNull("nothing queued for the session to fire", CommandBus.poll())
    }
}
