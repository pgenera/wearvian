package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import java.security.SecureRandom

/**
 * Active-entry command + presence-heartbeat framing, reverse-engineered from the
 * official Rivian Android app (`com.rivian.android.consumer`; full notes in
 * `docs/passive-entry-protocol.md`). Pure-JVM so it is unit-testable.
 *
 * All HMACs key on `sessionSecret = HKDF-SHA256(ECDH(phonePriv, vehiclePub))` =
 * [RivianCrypto.deriveSecretKey] (the same key our proven pairing handshake uses).
 *
 *   messageKey = first16(HMAC-SHA256(sessionSecret, CONSTANT_B))   // AES-128
 *   AAD        = pNonce XOR vNonce
 *
 * - Active command (char 0x20, encrypted, 64 B):
 *     0x16 ‖ 0x01 ‖ IV(12) ‖ AES-128-GCM(plaintext, AAD)
 *     plaintext = cmd(2,LE) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter(4,LE) ‖ cmd)
 * - Drive heartbeat (char 0x1b, plaintext, 37 B):
 *     counter(4,LE) ‖ flag(1) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter(4,LE) ‖ flag)
 * - Auth nonce (char 0x15, 48 B):  pNonce(16) ‖ HMAC-SHA256(sessionSecret, pNonce)
 *     — byte-identical to [PairingFrames.pairingWrite]; cross-checked in tests.
 */
object ActiveCommandFrames {

    const val TYPE_ACTIVE_CMD_REQUEST: Byte = 0x16
    const val VERSION_1: Byte = 0x01
    const val IV_LEN = 12

    /** `q60/a` CONSTANT_B — the fixed input to the AES-key HMAC. */
    val CONSTANT_B: ByteArray =
        RivianCrypto.fromHex("07862b2ed8328106a7cdff7b5c1b23bedffddb33a6aa3c82b0fedc784485df77")

    /** 2-byte little-endian active-command codes (`em.k2` → `attrValue`). */
    object Cmd {
        const val UNLOCK_ALL = 0x0003
        const val LOCK_ALL = 0x0006
        const val PANIC_ON = 0x0007
        const val OPEN_ALL_WINDOWS = 0x0015
        const val CLOSE_ALL_WINDOWS = 0x0016
        const val ENABLE_GEAR_GUARD = 0x0017
        const val DISABLE_GEAR_GUARD = 0x0018
        const val OPEN_FRUNK = 0x0026
        const val CLOSE_FRUNK = 0x0027
        const val CLOSE_LIFTGATE = 0x002b
        const val PANIC_OFF = 0x0034
        const val OPEN_CHARGE_PORT = 0x0036
        const val CLOSE_CHARGE_PORT = 0x0037
        const val FLASH_LIGHTS = 0x006e
        const val ACTIVATE_SOUND = 0x006f
    }

    private fun sessionKey(sharedSecret: ByteArray) = RivianCrypto.deriveSecretKey(sharedSecret)

    /** AES-128 message key = first 16 bytes of HMAC-SHA256(sessionSecret, CONSTANT_B). */
    fun aesKey(sharedSecret: ByteArray): ByteArray =
        RivianCrypto.hmacSha256(sessionKey(sharedSecret), CONSTANT_B).copyOf(16)

    /** AAD = pNonce XOR vNonce (truncated to the shorter length). */
    fun aad(pNonce: ByteArray, vNonce: ByteArray): ByteArray {
        val n = minOf(pNonce.size, vNonce.size)
        return ByteArray(n) { (pNonce[it].toInt() xor vNonce[it].toInt()).toByte() }
    }

    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    fun le32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** AUTH_PNONCE frame (== [PairingFrames.pairingWrite]); the value written to char 0x15. */
    fun authNonce(sharedSecret: ByteArray, pNonce: ByteArray): ByteArray =
        pNonce + RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce)

    /** ACTIVE_COMMAND plaintext (before encryption). */
    fun activeCommandPlaintext(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, counter: Int, commandCode: Int,
    ): ByteArray {
        val cmd = le16(commandCode)
        val mac = RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce + vNonce + le32(counter) + cmd)
        return cmd + mac
    }

    /** Full encrypted active-command frame written to char 0x20 (64 bytes for a 2-byte code). */
    fun activeCommandFrame(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray,
        counter: Int, commandCode: Int, iv: ByteArray = randomIv(),
    ): ByteArray {
        require(iv.size == IV_LEN) { "iv must be $IV_LEN bytes" }
        val pt = activeCommandPlaintext(sharedSecret, pNonce, vNonce, counter, commandCode)
        val ct = RivianCrypto.aesGcmEncrypt(aesKey(sharedSecret), iv, aad(pNonce, vNonce), pt)
        return byteArrayOf(TYPE_ACTIVE_CMD_REQUEST, VERSION_1) + iv + ct
    }

    /** PASSIVE_ENTRY heartbeat frame written (unencrypted) to char 0x1b (37 bytes). */
    fun heartbeatFrame(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, counter: Int, flag: Byte,
    ): ByteArray {
        val flagArr = byteArrayOf(flag)
        val mac = RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce + vNonce + le32(counter) + flagArr)
        return le32(counter) + flagArr + mac
    }

    fun randomIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
}
