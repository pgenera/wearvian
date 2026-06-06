package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun decryptInboundReturnsNullOnShortFrameOrBadKey() {
        assertNull(ActiveCommandFrames.decryptInbound(sharedSecret, pNonce, vNonce, ByteArray(5)))
        val frame = ActiveCommandFrames.activeCommandFrame(sharedSecret, pNonce, vNonce, 0, ActiveCommandFrames.Cmd.LOCK_ALL)
        val wrongSecret = ByteArray(32) { (it + 1).toByte() }
        assertNull(ActiveCommandFrames.decryptInbound(wrongSecret, pNonce, vNonce, frame))
    }
}
