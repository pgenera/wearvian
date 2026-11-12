package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.LockSyncPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LockSyncPolicyTest {
    @Test
    fun agreementIsANoOp() {
        for (wasPassive in listOf(false, true)) {
            assertEquals(Action.NONE, LockSyncPolicy.decide(keyguardLocked = true, believedLocked = true, wasPassive = wasPassive))
            assertEquals(Action.NONE, LockSyncPolicy.decide(keyguardLocked = false, believedLocked = false, wasPassive = wasPassive))
        }
    }

    @Test
    fun aStaleUnlockedFieldIsCorrectedToLock() {
        // The stale lock state in passive: a watch taken off while passive keeps locked == false.
        assertEquals(Action.LOCK, LockSyncPolicy.decide(keyguardLocked = true, believedLocked = false, wasPassive = false))
    }

    @Test
    fun unlockRestoresTheRememberedMode() {
        assertEquals(Action.UNLOCK_TO_PASSIVE, LockSyncPolicy.decide(keyguardLocked = false, believedLocked = true, wasPassive = true))
        assertEquals(Action.UNLOCK_TO_ACTIVE, LockSyncPolicy.decide(keyguardLocked = false, believedLocked = true, wasPassive = false))
    }
}
