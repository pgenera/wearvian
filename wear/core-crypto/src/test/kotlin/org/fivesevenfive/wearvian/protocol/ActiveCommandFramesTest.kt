package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structural + self-consistency tests for the reverse-engineered active-command and
 * heartbeat framing. The strongest check is [authNonceMatchesProvenPairingFrame]:
 * the AUTH_PNONCE serialization must equal the pairing-nonce frame our handshake
 * already sends and the vehicle already accepted — anchoring the whole derivation.
 */
class ActiveCommandFramesTest {

    private val sharedSecret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val pNonce = ByteArray(16) { it.toByte() }
    private val vNonce = ByteArray(16) { (it + 0x40).toByte() }

    @Test
    fun authNonceMatchesProvenPairingFrame() {
        val hmac = RivianCrypto.signNonce(sharedSecret, pNonce) // HMAC(deriveSecretKey(secret), pNonce)
        assertContentEquals(
            PairingFrames.pairingWrite(pNonce, hmac),
            ActiveCommandFrames.authNonce(sharedSecret, pNonce),
        )
    }

    @Test
    fun aesKeyIs16Bytes() {
        assertEquals(16, ActiveCommandFrames.aesKey(sharedSecret).size)
    }

    @Test
    fun aadIsXorOfNonces() {
        val aad = ActiveCommandFrames.aad(pNonce, vNonce)
        assertContentEquals(ByteArray(16) { (pNonce[it].toInt() xor vNonce[it].toInt()).toByte() }, aad)
    }

    @Test
    fun activeCommandFrameIs64BytesAndDecryptsToPlaintext() {
        val iv = ByteArray(12) { (it + 3).toByte() }
        val frame = ActiveCommandFrames.activeCommandFrame(
            sharedSecret, pNonce, vNonce, counter = 5, commandCode = ActiveCommandFrames.Cmd.UNLOCK_ALL, iv = iv,
        )
        assertEquals(64, frame.size)
        assertEquals(ActiveCommandFrames.TYPE_ACTIVE_CMD_REQUEST, frame[0])
        assertEquals(ActiveCommandFrames.VERSION_1, frame[1])
        assertContentEquals(iv, frame.copyOfRange(2, 14))

        val ct = frame.copyOfRange(14, frame.size)
        val plaintext = RivianCrypto.aesGcmDecrypt(
            ActiveCommandFrames.aesKey(sharedSecret), iv, ActiveCommandFrames.aad(pNonce, vNonce), ct,
        )
        assertContentEquals(
            ActiveCommandFrames.activeCommandPlaintext(sharedSecret, pNonce, vNonce, 5, ActiveCommandFrames.Cmd.UNLOCK_ALL),
            plaintext,
        )
        // plaintext starts with the 2-byte little-endian command code
        assertContentEquals(ActiveCommandFrames.le16(ActiveCommandFrames.Cmd.UNLOCK_ALL), plaintext.copyOf(2))
    }

    @Test
    fun heartbeatFrameIs37Bytes() {
        val hb = ActiveCommandFrames.heartbeatFrame(sharedSecret, pNonce, vNonce, counter = 0, flag = 0x80.toByte())
        assertEquals(37, hb.size)
        assertContentEquals(ActiveCommandFrames.le32(0), hb.copyOf(4))
        assertEquals(0x80.toByte(), hb[4])
    }

    /**
     * Wire-byte pin for the heartbeat. Expected values were computed independently (Python
     * hmac/hashlib: HKDF-SHA256 with zero salt and empty info, then HMAC over
     * pNonce‖vNonce‖counter‖flag), so this catches any change to the derivation or layout.
     */
    private val heartbeatVectors = listOf(
        Triple(0, 0x80.toByte(), "00000000803917c557b08e38ec504c2e2ab624a940e202ad22ac6504aae8aa613f17c354e4"),
        Triple(7, 0xc5.toByte(), "07000000c50039a9009604c3baefa8a616e51cfa15948ffe8685a4355e9642870e36effc3a"),
        Triple(123456, 0x10.toByte(), "40e20100100ce600e47773fa944f48a22eaf67dd6c0ebd4dcad4f6fb8275a980e70c2ff89d"),
    )

    @Test
    fun heartbeatFrameMatchesKnownAnswer() {
        for ((ctr, flag, hex) in heartbeatVectors) {
            assertEquals(hex, RivianCrypto.toHex(ActiveCommandFrames.heartbeatFrame(sharedSecret, pNonce, vNonce, ctr, flag)))
        }
    }

    @Test
    fun cachedSessionKeysHeartbeatIsByteIdenticalToPerBeatDerivation() {
        val keys = ActiveCommandFrames.SessionKeys(sharedSecret)
        for ((ctr, flag, hex) in heartbeatVectors) {
            assertEquals(hex, RivianCrypto.toHex(ActiveCommandFrames.heartbeatFrame(keys, pNonce, vNonce, ctr, flag)))
        }
        // One SessionKeys reused across many beats (as a live session does) never drifts.
        for (ctr in 0 until 200) {
            val flag = (ctr * 13).toByte()
            assertContentEquals(
                ActiveCommandFrames.heartbeatFrame(sharedSecret, pNonce, vNonce, ctr, flag),
                ActiveCommandFrames.heartbeatFrame(keys, pNonce, vNonce, ctr, flag),
            )
        }
    }

    @Test
    fun aesKeyMatchesKnownAnswer() {
        assertEquals("c04243f7695b9313744e9497dc1a7c3f", RivianCrypto.toHex(ActiveCommandFrames.aesKey(sharedSecret)))
    }

    @Test
    fun decryptInboundRecoversStatusPayload() {
        // Build an inbound STATUS-style frame the way the vehicle would (same key/AAD
        // as a command, an arbitrary status payload) and confirm decryptInbound recovers it.
        val payload = ByteArray(40) { (it * 3 + 11).toByte() }
        val iv = ByteArray(ActiveCommandFrames.IV_LEN) { (it + 0x90).toByte() }
        val ct = RivianCrypto.aesGcmEncrypt(
            ActiveCommandFrames.aesKey(sharedSecret), iv, ActiveCommandFrames.aad(pNonce, vNonce), payload,
        )
        val frame = byteArrayOf(ActiveCommandFrames.TYPE_VEHICLE_STATUS, ActiveCommandFrames.VERSION_1) + iv + ct
        assertContentEquals(payload, ActiveCommandFrames.decryptInbound(sharedSecret, pNonce, vNonce, frame))
    }

    @Test
    fun probeInboundFindsImplicitIvFrame() {
        // Synthesize a compact inbound frame the way we suspect the vehicle does: implicit IV
        // = le32(csn) ‖ zeros(8), AAD = pNonce⊕vNonce, 2-byte plaintext code, 16-byte tag.
        val csn = 3
        val code = byteArrayOf(0x68, 0x01) // 104,1 = charge-port-open OK in the decompile table
        val iv = ActiveCommandFrames.le32(csn) + ByteArray(8)
        val ct = RivianCrypto.aesGcmEncrypt(
            ActiveCommandFrames.aesKey(sharedSecret), iv, ActiveCommandFrames.aad(pNonce, vNonce), code,
        )
        val frame = byteArrayOf(ActiveCommandFrames.TYPE_ACTIVE_CMD_RESPONSE, ActiveCommandFrames.VERSION_1) + ct
        val hit = ActiveCommandFrames.probeInbound(sharedSecret, pNonce, vNonce, csn, frame)
        assertNotNull(hit)
        assertTrue(hit.contains("iv=csn_le"), "expected csn_le IV match, got: $hit")
        assertTrue(hit.contains("pt=6801"), "expected decoded code 6801, got: $hit")
    }

    @Test
    fun probeInboundReturnsNullWhenNothingMatches() {
        // A frame that isn't actually GCM under any candidate IV/AAD → no match.
        assertNull(ActiveCommandFrames.probeInbound(sharedSecret, pNonce, vNonce, 0, ByteArray(20) { it.toByte() }))
    }

    @Test
    fun decryptInboundReturnsNullOnShortFrameOrBadKey() {
        assertNull(ActiveCommandFrames.decryptInbound(sharedSecret, pNonce, vNonce, ByteArray(5)))
        val frame = ActiveCommandFrames.activeCommandFrame(sharedSecret, pNonce, vNonce, 0, ActiveCommandFrames.Cmd.LOCK_ALL)
        val wrongSecret = ByteArray(32) { (it + 1).toByte() }
        assertNull(ActiveCommandFrames.decryptInbound(wrongSecret, pNonce, vNonce, frame))
    }
}
