package org.fivesevenfive.wearvian.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.fivesevenfive.wearvian.util.logi

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
 */
class EnrollmentStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "wearvian_enrollment",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

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

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_VEHICLE_ID = "vehicle_id"
        const val KEY_VIN = "vin"
        const val KEY_VAS_VEHICLE_ID = "vas_vehicle_id"
        const val KEY_VEHICLE_PUBKEY = "vehicle_public_key"
        const val KEY_VAS_PHONE_ID = "vas_phone_id"
        const val KEY_IDENTITY_ID = "identity_id"
        const val KEY_AS_WATCH = "as_watch"
        const val KEY_BONDED = "bonded"
    }
}
