package org.fivesevenfive.wearvian.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.fivesevenfive.wearvian.util.logi
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
        if (!hasKey()) {
            logi("KeyManager: generating new secp256r1 key (alias=$alias)")
            generate()
        } else {
            logi("KeyManager: reusing existing key (alias=$alias)")
        }
        return publicKeyHex().also { logi("KeyManager: publicKey=${it.take(16)}… len=${it.length}") }
    }

    private fun generate() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)

        fun spec(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Layer 2 anti-theft: the key (and thus the initial ECDH) can only be used
                // while the watch is unlocked. On Wear OS the device locks when removed from
                // the wrist (with a screen lock set), so a stolen watch can't derive the
                // shared secret to start a new session. Only applies to keys generated from
                // now on; existing keys keep working (ensureKey generates only when absent).
                // Runtime protection for an already-running session is Layer 1 (heartbeats
                // gated on isDeviceLocked in VehicleSession / ActiveCommandManager).
                .setUnlockedDeviceRequired(true)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

        try {
            kpg.initialize(spec(strongBox = true))
            kpg.generateKeyPair()
            logi("KeyManager: key generated in StrongBox")
        } catch (_: StrongBoxUnavailableException) {
            logi("KeyManager: StrongBox unavailable; generating in TEE")
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
        logi("KeyManager: ECDH against vehiclePublicKey=${vehiclePublicKeyHex.take(16)}…")
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
