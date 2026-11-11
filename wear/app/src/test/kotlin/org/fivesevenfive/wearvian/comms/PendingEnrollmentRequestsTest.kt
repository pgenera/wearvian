package org.fivesevenfive.wearvian.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingEnrollmentRequestsTest {

    private fun ok(id: String, vehicles: Int = 1) = EnrollmentContract.Result(
        requestId = id,
        status = "ok",
        error = null,
        userId = "u",
        vehicles = List(vehicles) { EnrollmentContract.VehicleResult("v$it", "VIN", "vv", "04", "vp", "id") },
    )

    private fun err(id: String) = EnrollmentContract.Result(id, "error", "nope", "", emptyList())

    @Test
    fun liveIdsDropsExpiredAndFutureEntries() {
        val now = 10 * PendingEnrollmentRequests.MAX_AGE_MS
        val live = PendingEnrollmentRequests.liveIds(
            mapOf(
                "fresh" to now - 1_000,
                "edge" to now - PendingEnrollmentRequests.MAX_AGE_MS,
                "old" to now - PendingEnrollmentRequests.MAX_AGE_MS - 1,
                "future" to now + 1_000,
            ),
            now,
        )
        assertEquals(setOf("fresh", "edge"), live)
    }

    @Test
    fun picksOkResultForOutstandingRequestOnly() {
        val picked = PendingEnrollmentRequests.pickStoredResult(
            listOf(ok("someone-else"), err("mine-1"), ok("mine-2")),
            setOf("mine-1", "mine-2"),
        )
        assertEquals("mine-2", picked?.requestId)
    }

    @Test
    fun ignoresOkResultWithoutVehicles() {
        assertNull(PendingEnrollmentRequests.pickStoredResult(listOf(ok("mine", vehicles = 0)), setOf("mine")))
    }

    @Test
    fun nothingOutstandingPicksNothing() {
        assertNull(PendingEnrollmentRequests.pickStoredResult(listOf(ok("a")), emptySet()))
    }
}
