package org.fivesevenfive.wearvian.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommandBusTest {

    @Before
    fun drain() {
        while (CommandBus.poll() != null) { /* empty leftovers from other tests */ }
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
        assertEquals(CommandBus.Command(0x03, "unlock"), first)
        assertEquals(CommandBus.Command(0x06, "lock"), second)
        assertNull(CommandBus.poll())
    }
}
