package org.fivesevenfive.wearvian.store

import android.content.Context
import org.fivesevenfive.wearvian.util.DebugLog

/** Process-lifetime debug overrides — NEVER persisted (gone on process death / app restart). */
object DebugOverrides {
    /**
     * Force the app to behave as a WATCH-registered key (no passive lock/unlock; the burst-presence
     * lifecycle) regardless of the stored enrollment's `asWatch`. Transient: toggled only from the debug
     * Settings page ("Force WaaK"), never written to disk — so a restart returns to the real enrollment.
     */
    @Volatile var forceWatch = false
}

/**
 * Dump EVERYTHING in persistent storage (plus the transient debug switch) to the debug log — called on
 * startup so the on-device log shows exactly what state the app booted with. The active *private* key
 * is never logged: for an escrowed key it lives only in the Keystore, and for an imported key only the
 * public key (not the private bytes) is surfaced here.
 */
fun logPersistentState(context: Context) {
    val settings = SettingsStore(context)
    val enrollment = EnrollmentStore(context).load()
    val addrs = VehicleAddressStore(context).load()
    val imported = ImportedKeyStore(context)
    DebugLog.add("state: switch forceWatch=${DebugOverrides.forceWatch} (transient, NOT persisted)")
    DebugLog.add("state: settings keyArmed=${settings.keyArmed} forceR1t=${settings.forceR1t}")
    DebugLog.add("state: keyMode=${if (imported.hasKey()) "IMPORTED (software ECDH)" else "ESCROWED (Keystore)"}")
    DebugLog.add("state: enrollment=${enrollment ?: "NONE"}")
    DebugLog.add("state: vehicleAddrs=${if (addrs.isEmpty()) "NONE" else addrs.joinToString()}")
}
