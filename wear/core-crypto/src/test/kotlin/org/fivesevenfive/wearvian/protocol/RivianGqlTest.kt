package org.fivesevenfive.wearvian.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RivianGqlTest {

    private val ourPubKey =
        "04979e5a40875e12bdc163c99c195d9c9a5e277e429dbabf6a6fc4d4a261731f6a3eebeecd3bc3e03f57e5520d63a12d33b94a9693a6f6f783a4ddccef1cf76b2e"

    @Test
    fun getUserInfoBodyIsWellFormed() {
        val body = RivianGql.getUserInfoBody()
        assertTrue(body.contains("\"operationName\":\"getUserInfo\""))
        assertTrue(body.contains("currentUser"))
        assertTrue(body.contains("enrolledPhones"))
    }

    @Test
    fun enrollPhoneBodyCarriesAttrs() {
        val body = RivianGql.enrollPhoneBody(
            userId = "user-1",
            vehicleId = "veh-1",
            publicKeyHex = ourPubKey,
            deviceType = "watch",
            deviceName = "wearvian",
        )
        assertTrue(body.contains("\"operationName\":\"EnrollPhone\""))
        assertTrue(body.contains("\"userId\":\"user-1\""))
        assertTrue(body.contains("\"vehicleId\":\"veh-1\""))
        assertTrue(body.contains(ourPubKey))
        assertTrue(body.contains("\"type\":\"watch\""))
    }

    @Test
    fun parseUserInfoExtractsVehicleVas() {
        val json = """
        {"data":{"currentUser":{"id":"user-1","vehicles":[
          {"id":"veh-1","vin":"7FCT...","name":"R1S",
           "vas":{"vasVehicleId":"vas-veh-1","vehiclePublicKey":"04aabb"}}
        ]}}}
        """.trimIndent()
        val info = RivianGql.parseUserInfo(json)
        assertEquals("user-1", info.userId)
        assertEquals(1, info.vehicles.size)
        assertEquals("veh-1", info.vehicles[0].vehicleId)
        assertEquals("vas-veh-1", info.vehicles[0].vasVehicleId)
        assertEquals("04aabb", info.vehicles[0].vehiclePublicKey)
    }

    @Test
    fun parseEnrollSuccess() {
        assertTrue(RivianGql.parseEnrollSuccess("""{"data":{"enrollPhone":{"success":true}}}"""))
        assertFalse(RivianGql.parseEnrollSuccess("""{"data":{"enrollPhone":{"success":false}}}"""))
        assertFalse(RivianGql.parseEnrollSuccess("""{"data":{}}"""))
    }

    @Test
    fun findEnrolledPhoneMatchesByPublicKeyInArrayShape() {
        val json = """
        {"data":{"currentUser":{"id":"user-1","enrolledPhones":[
          {"vas":{"vasPhoneId":"phone-A","publicKey":"04deadbeef"},
           "enrolled":{"identityId":"id-A","deviceName":"other"}},
          {"vas":{"vasPhoneId":"phone-B","publicKey":"$ourPubKey"},
           "enrolled":{"identityId":"id-B","deviceName":"wearvian"}}
        ]}}}
        """.trimIndent()
        val match = RivianGql.findEnrolledPhone(json, ourPubKey)
        assertEquals("phone-B", match?.vasPhoneId)
        assertEquals("id-B", match?.identityId)
    }

    @Test
    fun findEnrolledPhoneMatchesByPublicKeyInParallelArrayShape() {
        val json = """
        {"data":{"currentUser":{"id":"user-1","enrolledPhones":{
          "vas":[{"vasPhoneId":"phone-B","publicKey":"$ourPubKey"}],
          "enrolled":[{"identityId":"id-B","deviceName":"wearvian"}]
        }}}}
        """.trimIndent()
        val match = RivianGql.findEnrolledPhone(json, ourPubKey)
        assertEquals("phone-B", match?.vasPhoneId)
        assertEquals("id-B", match?.identityId)
    }

    @Test
    fun findEnrolledPhoneReturnsNullWhenAbsent() {
        val json = """
        {"data":{"currentUser":{"id":"user-1","enrolledPhones":[
          {"vas":{"vasPhoneId":"phone-A","publicKey":"04deadbeef"},
           "enrolled":{"identityId":"id-A"}}
        ]}}}
        """.trimIndent()
        assertNull(RivianGql.findEnrolledPhone(json, ourPubKey))
    }
}
