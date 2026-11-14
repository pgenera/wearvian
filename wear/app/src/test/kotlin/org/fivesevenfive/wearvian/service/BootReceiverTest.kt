package org.fivesevenfive.wearvian.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    private val off = BedtimeState.INACTIVE

    @Test
    fun startsOnlyOnBootForAnArmedKey() {
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = true, off))
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = false, off))
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = true, off))
        assertFalse(BootReceiver.shouldStart(null, keyArmed = true, off))
    }

    @Test
    fun bootDuringBedtimeDoesNotStart() {
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = true, BedtimeState.ACTIVE))
        // Unreadable bedtime fails safe to the old behavior.
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = true, BedtimeState.UNKNOWN))
    }

    @Test
    fun upgradeStartsOnlyForAnArmedKeyOutsideBedtime() {
        assertTrue(UpgradeReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = true, off))
        assertTrue(UpgradeReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = true, BedtimeState.UNKNOWN))
        assertFalse(UpgradeReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = false, off))
        assertFalse(UpgradeReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, keyArmed = true, BedtimeState.ACTIVE))
        assertFalse(UpgradeReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, keyArmed = true, off))
    }
}
