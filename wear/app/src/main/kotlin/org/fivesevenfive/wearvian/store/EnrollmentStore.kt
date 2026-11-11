package org.fivesevenfive.wearvian.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything (besides the Keystore private key) needed to be a phone key. */
data class Enrollment(
    val userId: String,
    val vehicleId: String,
    val vin: String,
    val vasVehicleId: String,
    val vehiclePublicKey: String,
    val vasPhoneId: String,
    val identityId: String,
    /** Registered with Rivian as a WATCH key (car does no passive lock/unlock) vs a phone key (full
     *  proximity). Defaults false so installs enrolled before this field existed are treated as phone
     *  keys on upgrade. Stored now; the presence behavior that consumes it lands in a later branch. */
    val asWatch: Boolean = false,
    val bonded: Boolean = false,
)

/**
 * Persists enrollment data in [EncryptedSharedPreferences] (AES-256-GCM, key in
 * the Keystore). The phone's private key itself never lives here — it stays in
 * the Keystore via [org.fivesevenfive.wearvian.crypto.KeyManager].
 *
 * Instances are cheap: the encrypted prefs are opened once per process (Keystore IPC + Tink keyset
 * decrypt) and shared. [warmUp] opens them off the main thread at process start so the first
 * main-thread / service-thread use normally finds them ready.
 */
class EnrollmentStore(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs: SharedPreferences = prefs(app)

    fun isEnrolled(): Boolean = prefs.contains(KEY_VAS_PHONE_ID)

    fun save(e: Enrollment) {
        logi("EnrollmentStore: save vin=${e.vin} vehicleId=${e.vehicleId} vasPhoneId=${e.vasPhoneId} asWatch=${e.asWatch} bonded=${e.bonded}")
        prefs.edit()
            .putString(KEY_USER_ID, e.userId)
            .putString(KEY_VEHICLE_ID, e.vehicleId)
            .putString(KEY_VIN, e.vin)
            .putString(KEY_VAS_VEHICLE_ID, e.vasVehicleId)
            .putString(KEY_VEHICLE_PUBKEY, e.vehiclePublicKey)
            .putString(KEY_VAS_PHONE_ID, e.vasPhoneId)
            .putString(KEY_IDENTITY_ID, e.identityId)
            .putBoolean(KEY_AS_WATCH, e.asWatch)
            .putBoolean(KEY_BONDED, e.bonded)
            .apply()
    }

    fun load(): Enrollment? {
        if (!isEnrolled()) return null
        // Values are decrypted in-process with the already-loaded keyset, so a failure here is real
        // corruption, not a Keystore hiccup: set the unreadable file aside and drop the cache (key
        // kept) rather than crash.
        return try {
            read()
        } catch (e: SecurityException) {
            logw("EnrollmentStore: stored enrollment unreadable (${e.javaClass.simpleName}); setting it aside — re-enroll needed, key kept")
            preserveAside(app)
            prefs.edit().clear().commit()
            null
        }
    }

    private fun read(): Enrollment {
        return Enrollment(
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            vehicleId = prefs.getString(KEY_VEHICLE_ID, "").orEmpty(),
            vin = prefs.getString(KEY_VIN, "").orEmpty(),
            vasVehicleId = prefs.getString(KEY_VAS_VEHICLE_ID, "").orEmpty(),
            vehiclePublicKey = prefs.getString(KEY_VEHICLE_PUBKEY, "").orEmpty(),
            vasPhoneId = prefs.getString(KEY_VAS_PHONE_ID, "").orEmpty(),
            identityId = prefs.getString(KEY_IDENTITY_ID, "").orEmpty(),
            asWatch = prefs.getBoolean(KEY_AS_WATCH, false), // pre-existing installs → phone key
            bonded = prefs.getBoolean(KEY_BONDED, false),
        )
    }

    fun setBonded(bonded: Boolean) {
        logi("EnrollmentStore: setBonded=$bonded")
        prefs.edit().putBoolean(KEY_BONDED, bonded).apply()
    }

    fun clear() {
        logi("EnrollmentStore: clear")
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "wearvian_enrollment"

        @Volatile private var cached: SharedPreferences? = null

        /** Open (and cache) the store on the calling thread. Call from a background thread at startup. */
        fun warmUp(context: Context) {
            runCatching { prefs(context.applicationContext) }
                .onFailure { logw("EnrollmentStore: warm-up failed (${it.javaClass.simpleName})") }
        }

        private fun prefs(app: Context): SharedPreferences {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                return openWithRecovery(app).also { cached = it }
            }
        }

        /**
         * Open the encrypted prefs, resetting them only when they are genuinely unreadable.
         *
         * A failed open is retried with a short backoff first: a Keystore hiccup must not cost the
         * enrollment. The error type alone can't tell a hiccup from corruption, because Tink's
         * AndroidKeysetManager swallows Keystore failures and falls back to parsing the encrypted
         * keyset as cleartext, so a Keystore outage surfaces as the same parse/decrypt error real
         * corruption gives. So before resetting we check the master key directly ([keystoreHealthy]);
         * if the Keystore can't do a round trip right now, we throw and leave the file alone (the
         * next use retries, since nothing is cached on failure).
         *
         * Only when the Keystore works and the store still won't open is it treated as corrupt (a
         * known security-crypto failure mode): the file is copied aside ([preserveAside]) and then
         * recreated empty, so the app reads "not enrolled" instead of crashing on every launch. That
         * loses only the enrollment cache — the Keystore phone key is untouched, so re-enrolling reuses
         * the same key and never forces a re-key on the vehicle.
         */
        private fun openWithRecovery(app: Context): SharedPreferences {
            // Master key first, with no recovery: if the Keystore itself fails, deleting our file
            // can't help and would only lose data, so let that surface (and be retried next use).
            val masterKey = runCatching { masterKey(app) }.getOrElse { masterKey(app) }
            var err: Throwable? = null
            for (attempt in 0 until OPEN_ATTEMPTS) {
                if (attempt > 0) Thread.sleep(OPEN_RETRY_BASE_MS * attempt)
                val result = runCatching { open(app, masterKey) }
                result.getOrNull()?.let { return it }
                err = result.exceptionOrNull()
                logw("EnrollmentStore: open failed (${err?.javaClass?.simpleName}), attempt ${attempt + 1}/$OPEN_ATTEMPTS")
            }
            val failure = err!!
            if (!isCorruptionCandidate(failure)) throw failure
            if (!keystoreHealthy()) {
                logw("EnrollmentStore: Keystore not usable right now; leaving the store untouched")
                throw failure
            }
            logw("EnrollmentStore: store unreadable with a working Keystore (${failure.javaClass.simpleName}); setting it aside and resetting — re-enroll needed, key kept")
            preserveAside(app)
            app.deleteSharedPreferences(PREFS_NAME)
            return open(app, masterKey)
        }

        private fun isCorruptionCandidate(t: Throwable): Boolean =
            generateSequence(t) { it.cause }.any { it is GeneralSecurityException || it is IOException }

        /**
         * True if the security-crypto master key exists and can do an AES-GCM round trip right now.
         * That is the key Tink uses to wrap the prefs keyset, so if it works, a failed open is the
         * file's fault, not the Keystore's.
         */
        private fun keystoreHealthy(): Boolean = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
                ?: return@runCatching false
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val ct = enc.doFinal(PROBE)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv)) }
            dec.doFinal(ct).contentEquals(PROBE)
        }.getOrDefault(false)

        /**
         * Copy the store's file to no-backup storage before it is reset, overwriting any earlier copy,
         * so a reset never destroys the only copy. Copied rather than renamed: Android caches the
         * prefs in memory, and only [Context.deleteSharedPreferences] evicts that cache.
         */
        private fun preserveAside(app: Context) {
            runCatching {
                val src = File(app.dataDir, "shared_prefs/$PREFS_NAME.xml")
                if (src.exists()) src.copyTo(File(app.noBackupFilesDir, "$PREFS_NAME.unreadable.xml"), overwrite = true)
            }.onFailure { logw("EnrollmentStore: could not set the unreadable store aside (${it.javaClass.simpleName})") }
        }

        private const val OPEN_ATTEMPTS = 3
        private const val OPEN_RETRY_BASE_MS = 200L
        private val PROBE = byteArrayOf(0x77, 0x76)

        private fun masterKey(app: Context): MasterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        private fun open(app: Context, masterKey: MasterKey): SharedPreferences =
            EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

        private const val KEY_USER_ID = "user_id"
        private const val KEY_VEHICLE_ID = "vehicle_id"
        private const val KEY_VIN = "vin"
        private const val KEY_VAS_VEHICLE_ID = "vas_vehicle_id"
        private const val KEY_VEHICLE_PUBKEY = "vehicle_public_key"
        private const val KEY_VAS_PHONE_ID = "vas_phone_id"
        private const val KEY_IDENTITY_ID = "identity_id"
        private const val KEY_AS_WATCH = "as_watch"
        private const val KEY_BONDED = "bonded"
    }
}
