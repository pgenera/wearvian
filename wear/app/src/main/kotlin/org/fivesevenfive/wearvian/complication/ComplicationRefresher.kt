package org.fivesevenfive.wearvian.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Asks the system to re-query the battery + range complications so their watch-face slots reflect
 * fresh telemetry. The data sources declare UPDATE_PERIOD_SECONDS=0 (no polling), so without this
 * a slot would only refresh when the watch face is re-created. Called (debounced) from
 * PresenceService after the latest SoC/range is written to [org.fivesevenfive.wearvian.store.TelemetryStore].
 *
 * Unlike the tile refresher this is NOT gated on app-foreground: a watch-face complication can be
 * glanced the instant the user leaves the app, so the persisted value must already be pushed.
 */
object ComplicationRefresher {
    fun refresh(context: Context) {
        requestUpdateAll(context, SocComplicationService::class.java)
        requestUpdateAll(context, RangeComplicationService::class.java)
    }

    private fun requestUpdateAll(context: Context, service: Class<*>) {
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context.applicationContext, ComponentName(context.applicationContext, service))
                .requestUpdateAll()
        }.onFailure { DebugLog.add("complication: requestUpdate failed — ${it.message}") }
    }
}
