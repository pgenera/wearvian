package org.fivesevenfive.wearvian.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.fivesevenfive.wearvian.util.logi
import org.fivesevenfive.wearvian.util.logw
import java.io.IOException
import java.security.GeneralSecurityException

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

    private val prefs: SharedPreferences = prefs(context.applicationContext)

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
        // corruption, not a Keystore hiccup: drop the unreadable cache (key kept) rather than crash.
        return try {
            read()
        } catch (e: SecurityException) {
            logw("EnrollmentStore: stored enrollment unreadable (${e.javaClass.simpleName}); clearing — re-enroll needed, key kept")
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
         * Open the encrypted prefs. A first failure is retried once as-is (a transient Keystore
         * hiccup must not cost the enrollment). If it fails again with a crypto/IO error, the
         * stored keyset or data is treated as corrupt (a known security-crypto failure mode): the
         * file is deleted and recreated empty, so the app reads "not enrolled" instead of crashing on
         * every launch. That only loses the enrollment cache — the Keystore phone key is untouched,
         * so re-enrolling reuses the same key and never forces a re-key on the vehicle.
         */
        private fun openWithRecovery(app: Context): SharedPreferences {
            // Master key first, with no recovery: if the Keystore itself fails, deleting our file
            // can't help and would only lose data, so let that surface (and be retried next use).
            val masterKey = runCatching { masterKey(app) }.getOrElse { masterKey(app) }
            val first = runCatching { open(app, masterKey) }
            first.getOrNull()?.let { return it }
            logw("EnrollmentStore: open failed (${first.exceptionOrNull()?.javaClass?.simpleName}); retrying")
            val second = runCatching { open(app, masterKey) }
            second.getOrNull()?.let { return it }
            val err = second.exceptionOrNull()!!
            if (!isCorruption(err)) throw err
            logw("EnrollmentStore: store unreadable (${err.javaClass.simpleName}); resetting it — re-enroll needed, key kept")
            app.deleteSharedPreferences(PREFS_NAME)
            return open(app, masterKey)
        }

        private fun isCorruption(t: Throwable): Boolean =
            generateSequence(t) { it.cause }.any { it is GeneralSecurityException || it is IOException }

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
