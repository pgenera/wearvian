package org.fivesevenfive.wearvian.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import org.fivesevenfive.wearvian.ble.CommandFailure
import org.fivesevenfive.wearvian.util.logi

/**
 * Tells the user a tapped command did NOT reach the vehicle: a distinct double buzz plus a short
 * toast. Used by both the app and the tile so a failed tap is never silent. Deliberately does not
 * touch button fill — fill shows vehicle state (AGENTS.md UI fill convention), not command results.
 */
object CommandFeedback {

    /** Two short pulses — distinguishable from a single notification buzz. */
    private val FAILURE_PATTERN = longArrayOf(0, 60, 90, 60)

    fun failed(context: Context, label: String, failure: CommandFailure) {
        val app = context.applicationContext
        logi("command $label failed: ${failure.name}")
        runCatching {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createWaveform(FAILURE_PATTERN, -1))
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(app, "${displayName(label)} not sent — ${failure.userText}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** "OPEN_FRUNK" → "Open frunk". */
    internal fun displayName(label: String): String =
        label.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
