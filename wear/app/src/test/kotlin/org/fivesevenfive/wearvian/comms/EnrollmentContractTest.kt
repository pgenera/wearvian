package org.fivesevenfive.wearvian.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentContractTest {

    @Test
    fun buildRequestCarriesAllFields() {
        val bytes = EnrollmentContract.buildRequest("req-1", "04abcd", "Pixel Watch")
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(EnrollmentContract.VERSION, o.getInt("v"))
        assertEquals("req-1", o.getString("requestId"))
        assertEquals("04abcd", o.getString("publicKey"))
        assertEquals("Pixel Watch", o.getString("deviceName"))
        assertEquals("watch", o.getString("deviceType"))
    }

    @Test
    fun parseOkResultMapsVehicles() {
        val json = """
            {
              "status": "ok",
              "requestId": "req-1",
              "userId": "user-42",
              "vehicles": [
                {
                  "vehicleId": "veh-1", "vin": "VIN123", "vasVehicleId": "vas-v",
                  "vehiclePublicKey": "04ff", "vasPhoneId": "vas-p", "identityId": "id-1"
                }
              ]
            }
        """.trimIndent()
        val r = EnrollmentContract.parseResult(json.toByteArray(Charsets.UTF_8))
        assertTrue(r.isOk)
        assertNull(r.error)
        assertEquals("user-42", r.userId)
        assertEquals(1, r.vehicles.size)
        val v = r.vehicles[0]
        assertEquals("veh-1", v.vehicleId)
        assertEquals("VIN123", v.vin)
        assertEquals("vas-v", v.vasVehicleId)
        assertEquals("04ff", v.vehiclePublicKey)
        assertEquals("vas-p", v.vasPhoneId)
        assertEquals("id-1", v.identityId)
    }

    @Test
    fun parseErrorResultHasNoVehicles() {
        val json = """{"status":"error","requestId":"req-2","error":"login failed"}"""
        val r = EnrollmentContract.parseResult(json.toByteArray(Charsets.UTF_8))
        assertFalse(r.isOk)
        assertEquals("login failed", r.error)
        assertEquals("req-2", r.requestId)
        assertTrue(r.vehicles.isEmpty())
    }

    @Test
    fun parseMissingStatusIsTreatedAsError() {
        val r = EnrollmentContract.parseResult("""{"requestId":"x"}""".toByteArray(Charsets.UTF_8))
        assertFalse(r.isOk)
        assertEquals("error", r.status)
    }

    @Test
    fun okResultWithNoVehiclesArrayIsEmptyNotCrash() {
        val r = EnrollmentContract.parseResult("""{"status":"ok","userId":"u"}""".toByteArray(Charsets.UTF_8))
        assertTrue(r.isOk)
        assertTrue(r.vehicles.isEmpty())
    }

    @Test
    fun resultItemPathRoundTrips() {
        val path = EnrollmentContract.resultItemPath("req-9")
        assertTrue(path.startsWith(EnrollmentContract.RESULT_ITEM_PREFIX))
        assertEquals("req-9", EnrollmentContract.requestIdFromItemPath(path))
    }

    @Test
    fun requestIdFromItemPathRejectsOtherPaths() {
        assertNull(EnrollmentContract.requestIdFromItemPath(null))
        assertNull(EnrollmentContract.requestIdFromItemPath(EnrollmentContract.PATH_RESULT))
        assertNull(EnrollmentContract.requestIdFromItemPath(EnrollmentContract.RESULT_ITEM_PREFIX))
        assertNull(EnrollmentContract.requestIdFromItemPath(EnrollmentContract.RESULT_ITEM_PREFIX + "a/b"))
        assertNull(EnrollmentContract.requestIdFromItemPath("/other/req-1"))
    }

    @Test
    fun ackRoundTrips() {
        assertEquals("req-3", EnrollmentContract.parseAck(EnrollmentContract.buildAck("req-3")))
        assertNull(EnrollmentContract.parseAck("not json".toByteArray()))
        assertNull(EnrollmentContract.parseAck("""{"v":1}""".toByteArray()))
    }

    /** The companion's success payload (this exact shape) must parse on the watch. */
    @Test
    fun parsesCompanionSuccessPayloadWithoutSessionTokens() {
        val json = """
            {"v":1,"requestId":"req-5","status":"ok","asWatch":true,"userId":"u",
             "vehicles":[{"vehicleId":"veh","vin":"V","vasVehicleId":"vv","vehiclePublicKey":"04",
                          "vasPhoneId":"vp","identityId":"id"}]}
        """.trimIndent()
        val r = EnrollmentContract.parseResult(json.toByteArray(Charsets.UTF_8))
        assertTrue(r.isOk)
        assertTrue(r.asWatch)
        assertEquals("req-5", r.requestId)
        assertEquals("vp", r.vehicles.single().vasPhoneId)
    }
}
