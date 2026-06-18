package org.fivesevenfive.wearvian.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.StrongBoxUnavailableException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.fivesevenfive.wearvian.util.logi
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.KeyAgreement

/**
 * Owns the phone key's secp256r1 key pair and performs ECDH for it. Two
 * mutually-exclusive keys can exist in the same build, both held by the Android
 * Keystore (non-exportable — ECDH runs inside the keystore daemon, only the shared
 * secret leaves it):
 *
 *  - **Escrowed (default):** generated in-place under [DEFAULT_ALIAS] with
 *    [KeyProperties.PURPOSE_AGREE_KEY], StrongBox-backed when available. This is
 *    what makes "survive phone destruction" real — the key never existed off-device.
 *
 *  - **Imported:** a private key brought in from elsewhere (e.g. the Home Assistant
 *    Rivian integration) is *imported* into the Keystore under [IMPORTED_ALIAS] via
 *    [importKey], then the raw bytes are discarded. Imported keys can't go in
 *    StrongBox (hardware won't accept arbitrary keys) but are still non-extractable
 *    once imported — our own code can no longer read them back. The unavoidable
 *    caveat is that the plaintext existed off-device before import.
 *
 * The active key is whichever alias is present, imported winning (never both at
 * once). Importing leaves the escrowed key intact, so [clearImported] reverts to it.
 */
class KeyManager(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "wearvian_phone_key"
        const val IMPORTED_ALIAS = "wearvian_imported_key"
        private const val PROVIDER = "AndroidKeyStore"
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** True when an imported key is active (not the escrowed Keystore key). */
    fun isImported(): Boolean = keyStore().containsAlias(IMPORTED_ALIAS)

    /** The alias in use: an imported key takes precedence over the escrowed one. */
    private fun activeAlias(): String = if (isImported()) IMPORTED_ALIAS else alias

    fun hasKey(): Boolean = keyStore().containsAlias(activeAlias())

    /** Create the escrowed key pair if absent. Returns the active public key as X9.62 hex.
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
        return publicKeyHex().also { logi("KeyManager: publicKey len=${it.length}") }
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

    /**
     * Adopt an externally-supplied P-256 private key as the active key by importing it into the
     * Keystore (non-extractable) under [IMPORTED_ALIAS], gated on the device being unlocked like the
     * escrowed key. The raw key is copied into the keystore daemon; afterwards ECDH runs there and the
     * bytes never re-enter the app. The escrowed key, if any, is left untouched for [clearImported].
     *
     * @param privateKeyPemBase64 base64 of a PKCS#8 PEM private key (the HA format).
     * @param publicKeyHex the matching X9.62 uncompressed public point (carrier for the cert chain).
     */
    fun importKey(privateKeyPemBase64: String, publicKeyHex: String) {
        val priv = RivianKeys.decodePrivateKeyPemBase64(privateKeyPemBase64)
        require(priv.algorithm == "EC") { "imported key is not an EC key" }
        val pub = RivianKeys.decodePublicKeyHex(publicKeyHex)
        keyStore().setEntry(
            IMPORTED_ALIAS,
            KeyStore.PrivateKeyEntry(priv, arrayOf(selfSignedCert(priv, pub))),
            KeyProtection.Builder(KeyProperties.PURPOSE_AGREE_KEY)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        logi("KeyManager: imported external key into Keystore (non-extractable, software/TEE) len=${publicKeyHex.length}")
    }

    /** Drop the imported key and revert to the escrowed Keystore key. */
    fun clearImported() {
        val ks = keyStore()
        if (ks.containsAlias(IMPORTED_ALIAS)) ks.deleteEntry(IMPORTED_ALIAS)
    }

    fun publicKeyHex(): String {
        val cert = keyStore().getCertificate(activeAlias())
            ?: error("Phone key not found in Keystore")
        return RivianKeys.encodePublicKeyHex(cert.publicKey as ECPublicKey)
    }

    private fun privateKey(): PrivateKey =
        (keyStore().getEntry(activeAlias(), null) as KeyStore.PrivateKeyEntry).privateKey

    /** ECDH against the vehicle's public key (hex X9.62), performed in the Keystore (escrowed or
     *  imported — both are Keystore-resident, so the private key never enters app memory). */
    fun sharedSecret(vehiclePublicKeyHex: String): ByteArray {
        logi("KeyManager: ${if (isImported()) "imported" else "escrowed"} ECDH against vehiclePublicKey len=${vehiclePublicKeyHex.length}")
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

    /** Delete the *active* key. Imported mode deletes the imported key (reverting to escrowed);
     *  escrowed mode deletes the escrowed entry (forcing a re-key). */
    fun deleteKey() {
        val ks = keyStore()
        val a = activeAlias()
        if (ks.containsAlias(a)) ks.deleteEntry(a)
    }

    /** Minimal throwaway self-signed cert carrying [pub] — the chain KeyStore.setEntry requires for
     *  a PrivateKeyEntry. Identity/validity are irrelevant; nothing ever verifies it. */
    private fun selfSignedCert(priv: PrivateKey, pub: PublicKey): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=wearvian-imported")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 3650L * 24 * 60 * 60 * 1000), // ~10 years
            name,
            pub,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(priv)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
