package org.fivesevenfive.wearvian.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    @Test fun prefersUserName() = assertEquals("Phil's watch", DeviceName.resolve(" Phil's watch ", "Pixel Watch 4"))
    @Test fun fallsBackToModel() {
        assertEquals("Pixel Watch 4", DeviceName.resolve(null, "Pixel Watch 4"))
        assertEquals("Pixel Watch 4", DeviceName.resolve("   ", "Pixel Watch 4"))
    }
    @Test fun fallsBackWhenNothingKnown() = assertEquals(DeviceName.FALLBACK, DeviceName.resolve(null, ""))
    @Test fun capsLength() = assertEquals(DeviceName.MAX_LEN, DeviceName.resolve("x".repeat(100), null).length)
}
