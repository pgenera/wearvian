package org.fivesevenfive.wearvian

import android.app.Application
import org.fivesevenfive.wearvian.util.CrashLog
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Process entry point. Installs the persistent crash recorder before any other component
 * runs so a background crash in a service/BLE/tile callback leaves a durable trace on disk
 * (debugging builds only — see [CrashLog]).
 */
class WearvianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Surface a previous crash so it's visible in the log/debug screen the next time the app
        // runs — not just silently on disk. (No-op on production, where nothing is recorded.)
        CrashLog.reports(this).firstOrNull()?.let {
            DebugLog.add("⚠ prior crash recorded: ${it.name} (pull ${it.path})")
        }
    }
}
