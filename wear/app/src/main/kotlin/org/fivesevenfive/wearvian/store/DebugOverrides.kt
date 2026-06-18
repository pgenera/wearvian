package org.fivesevenfive.wearvian.store

import android.content.Context
import org.fivesevenfive.wearvian.crypto.KeyManager
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
 * startup so the on-device log shows exactly what state the app booted with. The private key is never
 * logged — escrowed or imported, it lives only in the Keystore, never in these stores.
 */
fun logPersistentState(context: Context) {
    val settings = SettingsStore(context)
    val enrollment = EnrollmentStore(context).load()
    val addrs = VehicleAddressStore(context).load()
    DebugLog.add("state: switch forceWatch=${DebugOverrides.forceWatch} (transient, NOT persisted)")
    DebugLog.add("state: settings keyArmed=${settings.keyArmed} forceR1t=${settings.forceR1t}")
    DebugLog.add("state: keyMode=${if (KeyManager().isImported()) "IMPORTED" else "ESCROWED"}")
    DebugLog.add("state: enrollment=${enrollment ?: "NONE"}")
    DebugLog.add("state: vehicleAddrs=${if (addrs.isEmpty()) "NONE" else addrs.joinToString()}")
}
