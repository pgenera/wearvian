package org.fivesevenfive.wearvian.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Rivian phone-key signing primitives.
 *
 * These are a 1:1 port of the community-tested reference implementation
 * (`bretterer/rivian-python-client`, `src/rivian/utils.py`):
 *
 *   secret_key = HKDF-SHA256(ECDH(phonePriv, vehiclePub), len=32, salt=None, info="")
 *   signature  = HMAC-SHA256(secret_key, message)
 *
 * For BLE pairing the message is the raw 16-byte phone nonce; for active vehicle
 * commands it is `command + timestamp` (ASCII).
 *
 * This class is pure JVM (no Android dependency) so it can be unit-tested against
 * a fixed parity vector. The ECDH step lives in [RivianKeys]; on Android the
 * shared secret is produced by the Keystore-backed key in `KeyManager` and fed to
 * [deriveSecretKey] / [signNonce] / [signCommand] here.
 */
object RivianCrypto {

    /** HMAC-SHA256(key, message). */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * AES-GCM with a 128-bit (16-byte) tag, matching the Rivian app's
     * `AES_128/GCM/NoPadding` (key length picks AES-128). [iv] is the 12-byte
     * nonce; [aad] is the additional authenticated data. Returns ciphertext‖tag.
     */
    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        aesGcm(Cipher.ENCRYPT_MODE, key, iv, aad, plaintext)

    /** Inverse of [aesGcmEncrypt]; verifies the tag and returns the plaintext. */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        aesGcm(Cipher.DECRYPT_MODE, key, iv, aad, ciphertext)

    private fun aesGcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    /**
     * HKDF-SHA256 (RFC 5869) with `salt = null` and empty `info`, matching the
     * reference's `HKDF(algorithm=SHA256, length=32, salt=None, info=b"")`.
     * A null salt is defined as a string of HashLen (32) zero bytes.
     */
    fun hkdfSha256(ikm: ByteArray, length: Int = 32): ByteArray {
        val hashLen = 32
        val salt = ByteArray(hashLen) // salt=None -> zeros
        val prk = hmacSha256(salt, ikm) // extract
        // expand
        val out = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            // info is empty
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - generated)
            System.arraycopy(t, 0, out, generated, n)
            generated += n
            counter++
        }
        return out
    }

    /** Derive the 32-byte HMAC key from a raw ECDH shared secret. */
    fun deriveSecretKey(sharedSecret: ByteArray): ByteArray = hkdfSha256(sharedSecret, 32)

    /** Pairing handshake signature over the 16-byte phone nonce. */
    fun signNonce(sharedSecret: ByteArray, phoneNonce: ByteArray): ByteArray =
        hmacSha256(deriveSecretKey(sharedSecret), phoneNonce)

    /** Active-command signature over `command + timestamp` (ASCII). */
    fun signCommand(sharedSecret: ByteArray, command: String, timestamp: String): ByteArray =
        hmacSha256(deriveSecretKey(sharedSecret), (command + timestamp).toByteArray(Charsets.UTF_8))

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        require(clean.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
    }
}
