package com.daycalculator.dynamic.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class WidgetTracker(val id: Long, val name: String, val summary: String, val details: String, val payload: String)

internal object TrackerWidgetData {
    private const val STORE = "daycalcy_tracker_widget_instances"
    fun trackers(context: Context): List<WidgetTracker> = runCatching {
        val raw = context.getSharedPreferences("daycalcy_saved_trackers", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList { for (i in 0 until array.length()) { val o = array.optJSONObject(i) ?: continue; add(WidgetTracker(o.optLong("id"), o.optString("name"), o.optString("summary"), o.optString("details"), o.optString("payload"))) } }
    }.getOrDefault(emptyList())
    fun selected(context: Context, widgetId: Int): Long = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getLong("tracker_$widgetId", -1L)
    fun select(context: Context, widgetId: Int, trackerId: Long) { context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putLong("tracker_$widgetId", trackerId).apply() }
    fun clear(context: Context, widgetId: Int) { context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove("tracker_$widgetId").apply() }
}

/** Shared provider logic for the two existing DayCalcy widget footprints. */
open class TrackerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { appWidgetIds.forEach { TrackerWidgetData.clear(context, it) }; super.onDeleted(context, appWidgetIds) }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH_TRACKER) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val manager = AppWidgetManager.getInstance(context)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) updateForExistingWidget(context, manager, widgetId) else updateAll(context)
            return
        }
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) updateAll(context)
    }
    companion object {
        private const val ACTION_REFRESH_TRACKER = "com.daycalculator.dynamic.app.TRACKER_WIDGET_REFRESH"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            listOf(SmallDateReminderWidget::class.java, WideDateReminderWidget::class.java).forEach { c ->
                manager.getAppWidgetIds(ComponentName(context, c)).filter { DateReminderWidget.selectedContentType(context, it) == "TRACKER" }.forEach { updateForExistingWidget(context, manager, it) }
            }
        }
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) = updateForExistingWidget(context, manager, widgetId)

        fun updateForExistingWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val providerName = manager.getAppWidgetInfo(widgetId)?.provider?.className
            val large = providerName == WideDateReminderWidget::class.java.name
            val layout = if (large) R.layout.widget_tracker_large else R.layout.widget_tracker_small
            val views = RemoteViews(context.packageName, layout)
            val dark = isDark(context)
            val bg = if (dark) AndroidColor.BLACK else AndroidColor.rgb(245,245,245)
            val fg = if (dark) AndroidColor.WHITE else AndroidColor.rgb(17,17,17)
            val secondary = if (dark) AndroidColor.LTGRAY else AndroidColor.DKGRAY
            views.setInt(R.id.tracker_widget_root, "setBackgroundColor", bg)
            listOf(R.id.tracker_widget_name, R.id.tracker_widget_value, R.id.tracker_widget_subtitle, R.id.tracker_widget_extra).forEach { views.setTextColor(it, fg) }
            views.setTextColor(R.id.tracker_widget_subtitle, secondary)
            views.setTextColor(R.id.tracker_widget_extra, secondary)
            // Keep secondary tracker labels subdued while keeping key values readable.
            listOf(
                R.id.tracker_widget_target,
                R.id.tracker_widget_remaining,
                R.id.tracker_widget_time_progress_label,
                R.id.tracker_widget_dates
            ).forEach { views.setTextColor(it, secondary) }
            listOf(
                R.id.tracker_widget_value_progress_label,
                R.id.tracker_widget_time_progress_percent
            ).forEach { views.setTextColor(it, fg) }
            val selected = TrackerWidgetData.selected(context, widgetId)
            val tracker = TrackerWidgetData.trackers(context).firstOrNull { it.id == selected }
            if (tracker == null) {
                views.setTextViewText(R.id.tracker_widget_name, "DayCalcy Tracker")
                views.setTextViewText(R.id.tracker_widget_value, "Choose a tracker")
                views.setTextViewText(R.id.tracker_widget_subtitle, "Tap to configure")
                views.setTextViewText(R.id.tracker_widget_extra, "")
                views.setViewVisibility(R.id.tracker_widget_value_progress_group, android.view.View.GONE)
                views.setViewVisibility(R.id.tracker_widget_time_progress_group, android.view.View.GONE)
                views.setViewVisibility(R.id.tracker_widget_ring, android.view.View.GONE)
            } else {
                val snapshot = calculateTrackerSnapshot(tracker.payload)
                val currentText = snapshot.formattedValue()
                val targetLabel = snapshot.targetLabel() ?: "No value target"
                views.setTextViewText(R.id.tracker_widget_name, tracker.name)
                views.setTextViewText(R.id.tracker_widget_value, currentText)
                val widgetOptions = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                val minWidthDp = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val maxWidthDp = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
                val availableWidthDp = when {
                    minWidthDp > 0 && maxWidthDp > 0 -> minOf(minWidthDp, maxWidthDp)
                    minWidthDp > 0 -> minWidthDp
                    else -> maxWidthDp
                } - 24 // small widget horizontal padding
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 11f * context.resources.displayMetrics.scaledDensity
                }
                val fullLabel = "Projected value today"
                val shortLabel = "Projected value"
                val labelWidthDp = labelPaint.measureText(fullLabel) /
                    context.resources.displayMetrics.density
                val subtitle = if (availableWidthDp > 0 && labelWidthDp > availableWidthDp) {
                    shortLabel
                } else {
                    fullLabel
                }
                views.setTextViewText(R.id.tracker_widget_subtitle, subtitle)
                views.setTextViewText(R.id.tracker_widget_target, targetLabel)

                val valueProgress = snapshot.valueProgress
                val valueGroupVisibility = if (valueProgress != null) android.view.View.VISIBLE else android.view.View.GONE
                views.setViewVisibility(R.id.tracker_widget_value_progress_group, valueGroupVisibility)
                if (valueProgress != null) {
                    val percent = (valueProgress * 100).roundToInt()
                    views.setTextViewText(R.id.tracker_widget_value_progress_label, "$percent%")
                    views.setProgressBar(R.id.tracker_widget_value_progress, 100, percent, false)
                    val remaining = if (snapshot.valueTargetReached) "Value target reached" else
                        "${formatTrackerNumber(kotlin.math.abs(snapshot.targetValue!! - snapshot.currentValue))}${if (snapshot.unit.isBlank()) "" else " ${snapshot.unit}"} remaining"
                    views.setTextViewText(R.id.tracker_widget_remaining, remaining)
                }

                val timeProgress = snapshot.timeProgress
                val timeVisibility = if (timeProgress != null) android.view.View.VISIBLE else android.view.View.GONE
                views.setViewVisibility(R.id.tracker_widget_time_progress_group, timeVisibility)
                if (timeProgress != null) {
                    val percent = (timeProgress * 100).roundToInt()
                    if (large) {
                        views.setTextViewText(R.id.tracker_widget_time_progress_label, "Time progress · $percent%")
                    } else {
                        views.setTextViewText(R.id.tracker_widget_time_progress_label, "Time progress")
                        views.setTextViewText(R.id.tracker_widget_time_progress_percent, "$percent%")
                    }
                    views.setProgressBar(R.id.tracker_widget_time_progress, 100, percent, false)
                    val dates = if (snapshot.startDate != null && snapshot.targetDate != null) "${snapshot.startDate} – ${snapshot.targetDate}" else ""
                    views.setTextViewText(R.id.tracker_widget_dates, if (snapshot.dateTargetReached) "Target date reached" else dates)
                }
                views.setViewVisibility(R.id.tracker_widget_ring, android.view.View.GONE)
                views.setTextViewText(R.id.tracker_widget_extra, "")
            }
            val rootIntent = if (tracker == null) {
                Intent(context, MainActivity::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra("daycalcy_configure_tracker_widget", true)
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    action = "com.daycalculator.dynamic.app.OPEN_TRACKERS_FROM_WIDGET"
                    putExtra(MainActivity.EXTRA_OPEN_TRACKERS, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
            rootIntent.data = android.net.Uri.parse("daycalcy-tracker-root://widget/$widgetId/${if (tracker == null) "configure" else "open"}")
            val pi = PendingIntent.getActivity(context, widgetId + 9000, rootIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.tracker_widget_root, pi)

            manager.updateAppWidget(widgetId, views)
        }
        private fun isDark(context: Context): Boolean = when (context.getSharedPreferences("day_calculator_settings", Context.MODE_PRIVATE).getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK", "AMOLED" -> true
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        private fun ringBitmap(context: Context, progress: Float, dark: Boolean): Bitmap {
            val size = (48 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            val stroke = 4.5f * context.resources.displayMetrics.density
            val inset = stroke
            val rect = RectF(inset, inset, size - inset, size - inset)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF555555.toInt() else 0xFFD0D0D0.toInt(); style = Paint.Style.STROKE; strokeWidth = stroke }
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF69D38A.toInt(); style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            canvas.drawArc(rect, 0f, 360f, false, track); if (progress > 0f) canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f,1f), false, arc)
            return bitmap
        }
    }
}
class TrackerWidgetSmall : TrackerWidgetProvider()
class TrackerWidgetLarge : TrackerWidgetProvider()

