package org.fivesevenfive.wearvian.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingActivityPolicyTest {
    @Test fun olderWearOsKeepsTheOngoingActivity() {
        assertTrue(OngoingActivityPolicy.shouldDecorate(33)) // Wear OS 4
        assertTrue(OngoingActivityPolicy.shouldDecorate(34)) // Wear OS 5
        assertTrue(OngoingActivityPolicy.shouldDecorate(36)) // Wear OS 6
    }

    @Test fun wearOs7AndNewerSkipsIt() {
        assertFalse(OngoingActivityPolicy.shouldDecorate(37)) // Wear OS 7
        assertFalse(OngoingActivityPolicy.shouldDecorate(38))
    }
}
