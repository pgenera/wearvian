package org.fivesevenfive.wearvian.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.fivesevenfive.wearvian.util.logi

/**
 * Holds an *imported* phone-key private key (e.g. negotiated by the unofficial
 * Home Assistant Rivian integration and brought over via the companion app).
 *
 * Unlike the escrowed key in [org.fivesevenfive.wearvian.crypto.KeyManager], this
 * private key is stored as raw bytes (base64 PKCS#8 PEM) and ECDH is done in
 * software — it deliberately does NOT live in the secure element and is not gated
 * on the device being unlocked. That trade-off is the whole point of import: the
 * key already exists elsewhere, so the "survives phone destruction / can't be
 * exfiltrated" guarantee doesn't apply. The bytes are still kept in
 * [EncryptedSharedPreferences] (AES-256-GCM) rather than plaintext.
 *
 * Presence of a key here is the single source of truth for "imported mode": when
 * set, [KeyManager] uses this key and ignores any Keystore key. Only one key is
 * ever active at a time — importing does not delete the escrowed Keystore key, so
 * [clear] reverts to it.
 */
class ImportedKeyStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wearvian_imported_key",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasKey(): Boolean = prefs.contains(KEY_PRIVATE_PEM)

    /** @param privateKeyPemBase64 base64 of a PKCS#8 PEM P-256 private key (the HA storage format). */
    fun save(privateKeyPemBase64: String, publicKeyHex: String) {
        logi("ImportedKeyStore: save imported key publicKey=${publicKeyHex.take(16)}… len=${publicKeyHex.length}")
        prefs.edit()
            .putString(KEY_PRIVATE_PEM, privateKeyPemBase64)
            .putString(KEY_PUBLIC_HEX, publicKeyHex)
            .apply()
    }

    fun privateKeyPemBase64(): String? = prefs.getString(KEY_PRIVATE_PEM, null)

    fun publicKeyHex(): String? = prefs.getString(KEY_PUBLIC_HEX, null)

    fun clear() {
        logi("ImportedKeyStore: clear (revert to escrowed Keystore key)")
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PRIVATE_PEM = "private_key_pem"
        const val KEY_PUBLIC_HEX = "public_key_hex"
    }
}
