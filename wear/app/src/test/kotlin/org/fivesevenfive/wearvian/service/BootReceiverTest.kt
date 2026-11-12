package org.fivesevenfive.wearvian.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun startsOnlyOnBootForAnArmedKey() {
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = true))
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = false))
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = true))
        assertFalse(BootReceiver.shouldStart(null, keyArmed = true))
    }
}
