package org.fivesevenfive.wearvian.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.store.TelemetryStore
import org.fivesevenfive.wearvian.ui.MainActivity

/**
 * Watch-face complication exposing the vehicle's battery state-of-charge. Offers a RANGED_VALUE
 * (a 0–100 arc with "84%") and a SHORT_TEXT ("84%"). Reads the last-known value from
 * [TelemetryStore] — never [org.fivesevenfive.wearvian.service.VehicleStatus], which is in-memory
 * and gone whenever this service is bound cold by the system. Updates are pushed by
 * [ComplicationRefresher] when fresh telemetry arrives; UPDATE_PERIOD_SECONDS=0 (no polling).
 */
class SocComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val soc = TelemetryStore(this).socPercent ?: return NO_DATA
        return build(request.complicationType, soc)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData = build(type, 84)

    private fun build(type: ComplicationType, soc: Int): ComplicationData {
        val text = "$soc%"
        val description = PlainComplicationText.Builder("Battery $text").build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_complication_battery),
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), description)
                    .setMonochromaticImage(icon)
                    .setTapAction(launchApp())
                    .build()
            // Default to the richer arc for RANGED_VALUE and any other slot we declared.
            else ->
                RangedValueComplicationData.Builder(
                    value = soc.toFloat(), min = 0f, max = 100f, contentDescription = description,
                )
                    .setText(PlainComplicationText.Builder(text).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(launchApp())
                    .build()
        }
    }

    private fun launchApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        val NO_DATA = androidx.wear.watchface.complications.data.NoDataComplicationData()
    }
}
