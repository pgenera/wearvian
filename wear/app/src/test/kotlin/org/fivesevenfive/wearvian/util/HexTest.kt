package org.fivesevenfive.wearvian.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun matchesFormatBasedEncoding() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(it) }, bytes.toHexString())
    }

    @Test
    fun emptyIsEmpty() {
        assertEquals("", ByteArray(0).toHexString())
    }
}
