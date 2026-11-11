package org.fivesevenfive.wearvian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DebugLogTest {

    @Before
    fun clear() {
        DebugLog.bufferEnabled = true
        DebugLog.clear()
    }

    @After
    fun restore() {
        DebugLog.bufferEnabled = true
    }

    @Test
    fun disabledBufferKeepsNothing() {
        DebugLog.bufferEnabled = false
        repeat(5) { DebugLog.add("line $it") }
        assertTrue(DebugLog.snapshot().isEmpty())
    }

    @Test
    fun flowEmitsCurrentSnapshot() = runBlocking {
        DebugLog.add("a")
        DebugLog.add("b")
        val emitted = DebugLog.flow.first()
        assertEquals(2, emitted.size)
        assertTrue(emitted.last().endsWith("b"))
    }

    @Test
    fun startsEmpty() {
        assertTrue(DebugLog.snapshot().isEmpty())
    }

    @Test
    fun ringBufferIsBoundedAndKeepsNewest() {
        repeat(250) { DebugLog.add("line $it") }
        val lines = DebugLog.snapshot()
        assertEquals(200, lines.size) // MAX
        assertTrue("oldest were dropped, newest kept", lines.last().endsWith("line 249"))
        assertTrue("the first surviving line is 50", lines.first().endsWith("line 50"))
    }

    @Test
    fun bleFormatsDirectionCharSummaryAndBytes() {
        DebugLog.ble("→", "0x20", "command", 64)
        assertTrue(DebugLog.snapshot().last().endsWith("→ 0x20  command  64B"))
    }

    @Test
    fun bleOmitsByteSuffixWhenNull() {
        DebugLog.ble("←", "0x15", "vNonce")
        assertTrue(DebugLog.snapshot().last().endsWith("← 0x15  vNonce"))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        DebugLog.add("something")
        DebugLog.clear()
        assertTrue(DebugLog.snapshot().isEmpty())
    }
}
