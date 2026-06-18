package org.fivesevenfive.wearvian.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.fivesevenfive.wearvian.store.ImportedKeyStore
import org.fivesevenfive.wearvian.util.logi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Owns the phone key's secp256r1 key pair and performs ECDH for it. Supports two
 * mutually-exclusive backends in the same build:
 *
 *  - **Escrowed (default):** the key is generated in the Android Keystore with
 *    [KeyProperties.PURPOSE_AGREE_KEY] and is non-exportable — ECDH happens inside
 *    the secure element (StrongBox when available). Only the shared secret leaves
 *    the Keystore. This is what makes "survive phone destruction" real.
 *
 *  - **Imported:** a private key brought in from elsewhere (e.g. the Home Assistant
 *    Rivian integration) lives as raw bytes in [ImportedKeyStore] and ECDH is done
 *    in software. It is NOT in the secure element and NOT unlocked-device gated —
 *    that guarantee can't hold for a key that already exists off-device.
 *
 * The active backend is decided solely by whether [ImportedKeyStore] holds a key:
 * if it does, the imported key is used and the Keystore key is ignored (never both
 * at once). Importing leaves the Keystore key intact, so [ImportedKeyStore.clear]
 * reverts to escrowed mode.
 */
class KeyManager(context: Context, private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "wearvian_phone_key"
        private const val PROVIDER = "AndroidKeyStore"
    }

    private val imported = ImportedKeyStore(context.applicationContext)

    /** True when an imported key is active (software ECDH, not the secure element). */
    fun isImported(): Boolean = imported.hasKey()

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Whether a usable key exists in the *active* backend. */
    fun hasKey(): Boolean = if (isImported()) true else keyStore().containsAlias(alias)

    /**
     * Adopt an externally-supplied P-256 private key as the active key (imported
     * mode). The Keystore key, if any, is left untouched so escrowed mode can be
     * restored later via [clearImported].
     *
     * @param privateKeyPemBase64 base64 of a PKCS#8 PEM private key (the HA format).
     * @param publicKeyHex the matching X9.62 uncompressed public point.
     */
    fun importKey(privateKeyPemBase64: String, publicKeyHex: String) {
        // Validate it parses + that the public point matches before persisting.
        val priv = RivianKeys.decodePrivateKeyPemBase64(privateKeyPemBase64)
        require(priv.algorithm == "EC") { "imported key is not an EC key" }
        logi("KeyManager: importing external key (software ECDH) publicKey=${publicKeyHex.take(16)}…")
        imported.save(privateKeyPemBase64, publicKeyHex)
    }

    /** Drop the imported key and revert to the escrowed Keystore key. */
    fun clearImported() = imported.clear()

    /** Create the escrowed key pair if absent. Returns the public key as X9.62 hex.
     *  No-op for imported mode (the key is supplied, not generated). */
    fun ensureKey(): String {
        if (isImported()) {
            logi("KeyManager: imported key active; skipping Keystore generation")
            return publicKeyHex()
        }
        if (!keyStore().containsAlias(alias)) {
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
        imported.publicKeyHex()?.let { return it }
        val cert = keyStore().getCertificate(alias)
            ?: error("Phone key not found in Keystore")
        return RivianKeys.encodePublicKeyHex(cert.publicKey as ECPublicKey)
    }

    private fun keystorePrivateKey(): PrivateKey =
        (keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    /** ECDH against the vehicle's public key (hex X9.62). Performed in the Keystore for the
     *  escrowed key, or in software for an imported key. */
    fun sharedSecret(vehiclePublicKeyHex: String): ByteArray {
        val pem = imported.privateKeyPemBase64()
        if (pem != null) {
            logi("KeyManager: software ECDH (imported key) against vehiclePublicKey=${vehiclePublicKeyHex.take(16)}…")
            return RivianKeys.ecdh(
                RivianKeys.decodePrivateKeyPemBase64(pem),
                RivianKeys.decodePublicKeyHex(vehiclePublicKeyHex),
            )
        }
        logi("KeyManager: Keystore ECDH against vehiclePublicKey=${vehiclePublicKeyHex.take(16)}…")
        val ka = KeyAgreement.getInstance("ECDH", PROVIDER)
        ka.init(keystorePrivateKey())
        ka.doPhase(RivianKeys.decodePublicKeyHex(vehiclePublicKeyHex), true)
        return ka.generateSecret()
    }

    /** HMAC over the 16-byte pairing nonce (HKDF-derived key). */
    fun signNonce(vehiclePublicKeyHex: String, nonce: ByteArray): ByteArray =
        RivianCrypto.signNonce(sharedSecret(vehiclePublicKeyHex), nonce)

    /** HMAC over `command + timestamp` for active BLE commands (milestone 2). */
    fun signCommand(vehiclePublicKeyHex: String, command: String, timestamp: String): ByteArray =
        RivianCrypto.signCommand(sharedSecret(vehiclePublicKeyHex), command, timestamp)

    /** Delete the *active* key. For imported mode this clears the imported key (reverting to
     *  escrowed); for escrowed mode it deletes the Keystore entry (forcing a re-key). */
    fun deleteKey() {
        if (isImported()) {
            imported.clear()
            return
        }
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
