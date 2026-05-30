package org.fivesevenfive.wearvian.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * secp256r1 (NIST P-256) key encoding + ECDH, matching the reference's
 * `utils.py` key handling.
 *
 * On Android the phone's private key lives non-exportably in the Keystore and
 * ECDH is performed there; this object provides the encoding helpers and a
 * software ECDH path used by tests and by any non-Keystore fallback.
 */
object RivianKeys {

    private const val CURVE = "secp256r1"

    private fun p256Params(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec(CURVE))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    /** Generate a software P-256 key pair (fallback when Keystore is unavailable). */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE))
        return kpg.generateKeyPair()
    }

    /**
     * Decode a public key from a hex-encoded X9.62 uncompressed point
     * (`04 || X(32) || Y(32)`) — the format Rivian returns as `vehiclePublicKey`
     * and expects for `EnrollPhone.publicKey`.
     */
    fun decodePublicKeyHex(uncompressedHex: String): ECPublicKey {
        val pt = RivianCrypto.fromHex(uncompressedHex)
        require(pt.size == 65 && pt[0].toInt() == 0x04) {
            "expected 65-byte uncompressed EC point"
        }
        val x = BigInteger(1, pt.copyOfRange(1, 33))
        val y = BigInteger(1, pt.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params())
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** Encode a public key as a hex X9.62 uncompressed point. */
    fun encodePublicKeyHex(key: ECPublicKey): String {
        val fieldBytes = (key.params.curve.field.fieldSize + 7) / 8
        val x = toFixed(key.w.affineX, fieldBytes)
        val y = toFixed(key.w.affineY, fieldBytes)
        return "04" + RivianCrypto.toHex(x) + RivianCrypto.toHex(y)
    }

    private fun toFixed(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray()
        // strip leading sign byte / left-pad to `size`
        val trimmed = if (raw.size > size && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        if (trimmed.size == size) return trimmed
        val out = ByteArray(size)
        System.arraycopy(trimmed, 0, out, size - trimmed.size, trimmed.size)
        return out
    }

    /** Load a private key from base64(PEM PKCS#8) — the format the reference emits. */
    fun decodePrivateKeyPemBase64(pemBase64: String): PrivateKey {
        val pem = String(Base64.getDecoder().decode(pemBase64))
        val der = pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        val pkcs8 = Base64.getDecoder().decode(der)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    /** ECDH shared secret between a private key and a peer public key. */
    fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }
}
