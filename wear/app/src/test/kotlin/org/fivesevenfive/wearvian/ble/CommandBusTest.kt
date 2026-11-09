package org.fivesevenfive.wearvian.ble

import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
