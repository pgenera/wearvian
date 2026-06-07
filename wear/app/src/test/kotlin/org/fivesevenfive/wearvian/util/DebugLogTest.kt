package org.fivesevenfive.wearvian.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {

    @Before
    fun clear() = DebugLog.clear()

    @Test
    fun startsEmpty() {
        assertTrue(DebugLog.flow.value.isEmpty())
    }

    @Test
    fun ringBufferIsBoundedAndKeepsNewest() {
        repeat(250) { DebugLog.add("line $it") }
        val lines = DebugLog.flow.value
        assertEquals(200, lines.size) // MAX
        assertTrue("oldest were dropped, newest kept", lines.last().endsWith("line 249"))
        assertTrue("the first surviving line is 50", lines.first().endsWith("line 50"))
    }

    @Test
    fun bleFormatsDirectionCharSummaryAndBytes() {
        DebugLog.ble("→", "0x20", "command", 64)
        assertTrue(DebugLog.flow.value.last().endsWith("→ 0x20  command  64B"))
    }

    @Test
    fun bleOmitsByteSuffixWhenNull() {
        DebugLog.ble("←", "0x15", "vNonce")
        assertTrue(DebugLog.flow.value.last().endsWith("← 0x15  vNonce"))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        DebugLog.add("something")
        DebugLog.clear()
        assertTrue(DebugLog.flow.value.isEmpty())
    }
}
