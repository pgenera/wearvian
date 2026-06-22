package org.fivesevenfive.wearvian.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.store.TelemetryStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.Units

/**
 * Watch-face complication exposing the vehicle's estimated remaining range. Offers SHORT_TEXT
 * ("174 mi") and LONG_TEXT ("174 mi range"), localized to mi/km via [Units]. Reads the last-known
 * value from [TelemetryStore] (see [SocComplicationService] for why not VehicleStatus). Updates are
 * pushed by [ComplicationRefresher]; UPDATE_PERIOD_SECONDS=0 (no polling).
 */
class RangeComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val km = TelemetryStore(this).rangeKm ?: return NO_DATA
        return build(request.complicationType, km)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData = build(type, 280)

    private fun build(type: ComplicationType, km: Int): ComplicationData {
        // The cramped slot shows the localized number only (no "mi"/"km") — the unit is implied by the
        // watch's region. The unit is kept in the accessibility description and the roomy LONG_TEXT slot.
        val value = Units.rangeValue(km).toString() // localized number, e.g. "174" or "280"
        val withUnit = Units.range(km)              // e.g. "174 mi" or "280 km"
        val description = PlainComplicationText.Builder("$withUnit range").build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_complication_range),
        ).build()
        return when (type) {
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(PlainComplicationText.Builder("$withUnit range").build(), description)
                    .setMonochromaticImage(icon)
                    .setTapAction(launchApp())
                    .build()
            else ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(value).build(), description)
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
