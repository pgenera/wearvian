package org.fivesevenfive.wearvian.service

import org.fivesevenfive.wearvian.service.PresenceStatus.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PresenceStatusTest {

    @Before
    fun reset() = PresenceStatus.reset()

    @Test
    fun resetState() {
        assertEquals("Stopped", PresenceStatus.summary.value)
        assertFalse(PresenceStatus.connected.value)
        assertEquals("", PresenceStatus.snapshot())
    }

    @Test
    fun connectingIsNotConnected() {
        PresenceStatus.set("PK", Link.CONNECTING)
        assertEquals("Connecting to vehicle…", PresenceStatus.summary.value)
        assertFalse(PresenceStatus.connected.value)
    }

    @Test
    fun oneUpLinkIsConnectedSingular() {
        PresenceStatus.set("PK", Link.UP)
        assertEquals("1 link · Vehicle connected", PresenceStatus.summary.value)
        assertTrue(PresenceStatus.connected.value)
    }

    @Test
    fun multipleUpLinksArePluralized() {
        PresenceStatus.set("PK", Link.UP)
        PresenceStatus.set("S1", Link.UP)
        assertEquals("2 links · Vehicle connected", PresenceStatus.summary.value)
        assertTrue(PresenceStatus.connected.value)
    }

    @Test
    fun allLinksDownIsDisconnected() {
        PresenceStatus.set("PK", Link.UP)
        PresenceStatus.set("PK", Link.DOWN)
        assertEquals("Vehicle disconnected", PresenceStatus.summary.value)
        assertFalse(PresenceStatus.connected.value)
    }

    @Test
    fun aDownLinkAlongsideAnUpLinkStaysConnected() {
        PresenceStatus.set("PK", Link.UP)
        PresenceStatus.set("S1", Link.CONNECTING)
        PresenceStatus.set("S1", Link.DOWN)
        assertEquals("1 link · Vehicle connected", PresenceStatus.summary.value)
        assertTrue(PresenceStatus.connected.value)
    }

    @Test
    fun snapshotIsSortedByLabel() {
        PresenceStatus.set("S2", Link.CONNECTING)
        PresenceStatus.set("PK", Link.UP)
        PresenceStatus.set("S1", Link.CONNECTING)
        PresenceStatus.set("S1", Link.DOWN)
        assertEquals("PK=UP S1=DOWN S2=CONNECTING", PresenceStatus.snapshot())
    }

    @Test
    fun lateDownAfterResetDoesNotRepopulate() {
        PresenceStatus.set("PK", Link.UP)
        PresenceStatus.reset()
        // A cancelled session's finally block lands after the service already reset the status.
        PresenceStatus.set("PK", Link.DOWN)
        assertEquals("Stopped", PresenceStatus.summary.value)
        assertEquals("", PresenceStatus.snapshot())
    }

    @Test
    fun newSessionAfterResetIsTracked() {
        PresenceStatus.reset()
        PresenceStatus.set("PK", Link.CONNECTING)
        PresenceStatus.set("PK", Link.DOWN)
        assertEquals("PK=DOWN", PresenceStatus.snapshot())
    }

    @Test
    fun primaryLinkTracksOnlyThePkLabel() {
        assertEquals(null, PresenceStatus.primaryLink.value)
        PresenceStatus.set("S1", PresenceStatus.Link.UP)
        assertEquals(null, PresenceStatus.primaryLink.value)
        PresenceStatus.set(PresenceStatus.PRIMARY_LABEL, PresenceStatus.Link.CONNECTING)
        PresenceStatus.set(PresenceStatus.PRIMARY_LABEL, PresenceStatus.Link.UP)
        assertEquals(PresenceStatus.Link.UP, PresenceStatus.primaryLink.value)
        PresenceStatus.reset()
        assertEquals(null, PresenceStatus.primaryLink.value)
    }
}
