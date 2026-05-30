package org.fivesevenfive.wearvian.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Owns the phone key's secp256r1 key pair inside the Android Keystore.
 *
 * The private key is generated with [KeyProperties.PURPOSE_AGREE_KEY] and is
 * non-exportable: ECDH is performed *inside* the secure element (StrongBox when
 * available, e.g. on Pixel Watch 4). Only the resulting shared secret leaves the
 * Keystore, and the HKDF/HMAC steps are the JVM-tested ones in [RivianCrypto].
 *
 * This is what makes "survive phone destruction" real: the secret material lives
 * only in the watch's secure hardware.
 */
class KeyManager(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "wearvian_phone_key"
        private const val PROVIDER = "AndroidKeyStore"
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasKey(): Boolean = keyStore().containsAlias(alias)

    /** Create the key pair if absent. Returns the public key as X9.62 hex. */
    fun ensureKey(): String {
        if (!hasKey()) generate()
        return publicKeyHex()
    }

    private fun generate() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)

        fun spec(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

        try {
            kpg.initialize(spec(strongBox = true))
            kpg.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            kpg.initialize(spec(strongBox = false))
            kpg.generateKeyPair()
        }
    }

    fun publicKeyHex(): String {
        val cert = keyStore().getCertificate(alias)
            ?: error("Phone key not found in Keystore")
        return RivianKeys.encodePublicKeyHex(cert.publicKey as ECPublicKey)
    }

    private fun privateKey(): PrivateKey =
        (keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    /** ECDH against the vehicle's public key (hex X9.62), performed in the Keystore. */
    fun sharedSecret(vehiclePublicKeyHex: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH", PROVIDER)
        ka.init(privateKey())
        ka.doPhase(RivianKeys.decodePublicKeyHex(vehiclePublicKeyHex), true)
        return ka.generateSecret()
    }

    /** HMAC over the 16-byte pairing nonce (HKDF-derived key). */
    fun signNonce(vehiclePublicKeyHex: String, nonce: ByteArray): ByteArray =
        RivianCrypto.signNonce(sharedSecret(vehiclePublicKeyHex), nonce)

    /** HMAC over `command + timestamp` for active BLE commands (milestone 2). */
    fun signCommand(vehiclePublicKeyHex: String, command: String, timestamp: String): ByteArray =
        RivianCrypto.signCommand(sharedSecret(vehiclePublicKeyHex), command, timestamp)

    fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
