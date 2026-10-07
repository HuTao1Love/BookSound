package com.zyagodin.booksound.watch.surfaces

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchApp
import com.zyagodin.booksound.watch.ui.MainActivity

/** How far into the current book the listener is; a tap opens the player. */
class BookComplicationService : SuspendingComplicationDataSourceService() {
    private val container by lazy { (application as WatchApp).container }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val shown = container.surfaces.current() ?: return NoDataComplicationData()
        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return data(request.complicationType, shown, openPlayer)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, Shown("", getString(R.string.app_name), null, percent = 42, finished = false, loaded = false, playing = false), tap = null)

    private fun data(type: ComplicationType, shown: Shown, tap: PendingIntent?): ComplicationData? {
        val percent = PlainComplicationText.Builder(getString(R.string.tile_percent, shown.percent)).build()
        val status = if (shown.finished) PlainComplicationText.Builder(getString(R.string.book_finished)).build() else percent
        val title = PlainComplicationText.Builder(shown.title).build()
        val description = PlainComplicationText.Builder("${shown.title}, ${getString(R.string.tile_percent, shown.percent)}").build()
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_notification)).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(shown.percent.toFloat(), 0f, 100f, description)
                    .setText(percent)
                    .setMonochromaticImage(image)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(percent, description)
                    .setMonochromaticImage(image)
                    .setTapAction(tap)
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(title, description)
                    .setTitle(status)
                    .setMonochromaticImage(image)
                    .setTapAction(tap)
                    .build()
            else -> null
        }
    }
}
