package com.daycalculator.dynamic.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Period
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home-screen widget for one selected Date Reminder.
 * Each widget instance stores its own reminder ID, so multiple widgets can show different reminders.
 */
open class DateReminderWidget : AppWidgetProvider() {

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        ensureRefreshSchedule(context)
        appWidgetIds.forEach { updateAppWidget(context, manager, it) }
    }

    override fun onEnabled(context: Context) {
        ensureRefreshSchedule(context)
        super.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        // Android calls onDisabled() per provider class. The app has two providers
        // (2×2 and 4×2), so only cancel the shared minute schedule when neither
        // provider has any live widget instances left.
        if (!hasAnyWidgets(context)) {
            cancelRefreshSchedule(context)
        }
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                updateAppWidget(context, manager, widgetId)
            } else {
                updateAll(context)
            }
            // Keep the minute tick scheduled even if the launcher/process recreated us.
            ensureRefreshSchedule(context)
            return
        }
        if (intent.action == ACTION_MINUTE_REFRESH) {
            updateAll(context)
            scheduleNextMinuteRefresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { clearSelectedReminder(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private val WIDGET_PROVIDERS = listOf(
            SmallDateReminderWidget::class.java,
            WideDateReminderWidget::class.java
        )

        private const val PREFS = "date_reminder_widgets"
        private const val KEY_PREFIX = "reminder_"
        private const val KEY_START_PREFIX = "start_"
        const val YEAR_PROGRESS_ID = "__YEAR_PROGRESS__"
        private const val YEAR_PROGRESS_PREFS = "date_reminder_settings"
        private const val YEAR_PROGRESS_ENABLED_KEY = "year_progress_enabled"
        private const val ACTION_REFRESH = "com.daycalculator.dynamic.app.WIDGET_REFRESH"
        private const val ACTION_MINUTE_REFRESH = "com.daycalculator.dynamic.app.WIDGET_MINUTE_REFRESH"
        private const val REFRESH_REQUEST_CODE = 73117
        private const val MINUTE_REFRESH_REQUEST_CODE = 73118
        private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

        fun isYearProgressId(id: String?): Boolean = id == YEAR_PROGRESS_ID

        fun isYearProgressEnabled(context: Context): Boolean =
            context.getSharedPreferences(YEAR_PROGRESS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(YEAR_PROGRESS_ENABLED_KEY, false)

        fun setYearProgressEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(YEAR_PROGRESS_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(YEAR_PROGRESS_ENABLED_KEY, enabled).apply()

            // A Year Progress selection is only valid while the feature is enabled.
            // If the user disables it, clear only widget instances that were using
            // Year Progress so they return to the normal chooser state. Normal
            // reminder assignments are left completely untouched.
            if (!enabled) {
                val manager = AppWidgetManager.getInstance(context)
                WIDGET_PROVIDERS.forEach { provider ->
                    manager.getAppWidgetIds(ComponentName(context, provider)).forEach { appWidgetId ->
                        if (selectedReminderId(context, appWidgetId) == YEAR_PROGRESS_ID) {
                            clearSelectedReminder(context, appWidgetId)
                        }
                    }
                }
            }

            updateAll(context)
        }

        fun selectedReminderId(context: Context, appWidgetId: Int): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + appWidgetId, null)

        fun setSelectedReminder(context: Context, appWidgetId: Int, reminderId: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (reminderId.isNullOrBlank()) {
                    remove(KEY_PREFIX + appWidgetId)
                    remove(KEY_START_PREFIX + appWidgetId)
                } else {
                    putString(KEY_PREFIX + appWidgetId, reminderId)
                    // The ring starts full when this widget is assigned to the reminder.
                    // It then shrinks as the actual time remaining decreases.
                    putLong(KEY_START_PREFIX + appWidgetId, System.currentTimeMillis())
                }
            }.apply()
        }

        fun clearSelectedReminder(context: Context, appWidgetId: Int) {
            setSelectedReminder(context, appWidgetId, null)
        }

        private fun assignmentStartMillis(context: Context, appWidgetId: Int): Long {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getLong(KEY_START_PREFIX + appWidgetId, 0L)
            if (existing > 0L) return existing
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_START_PREFIX + appWidgetId, now).apply()
            return now
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            WIDGET_PROVIDERS.forEach { provider ->
                val component = ComponentName(context, provider)
                manager.getAppWidgetIds(component).forEach { id ->
                    updateAppWidget(context, manager, id)
                }
            }
        }

        private fun hasAnyWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return WIDGET_PROVIDERS.any { provider ->
                manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
            }
        }

        fun ensureRefreshSchedule(context: Context) {
            if (!hasAnyWidgets(context)) return
            scheduleNextMinuteRefresh(context)
        }

        private fun scheduleNextMinuteRefresh(context: Context) {
            if (!hasAnyWidgets(context)) return

            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SmallDateReminderWidget::class.java).apply {
                action = ACTION_MINUTE_REFRESH
            }
            val pending = PendingIntent.getBroadcast(
                context,
                MINUTE_REFRESH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule the next tick on the next minute boundary. Exact alarms are
            // used when the app has the user's exact-alarm access, which keeps the
            // countdown responsive. Without that access, fall back to an idle-safe
            // inexact alarm rather than failing silently. The alarm is one-shot so
            // every tick can be aligned again after Android delays the process.
            val now = System.currentTimeMillis()
            val nextMinute = ((now / 60_000L) + 1L) * 60_000L
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                alarm.canScheduleExactAlarms()
            ) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinute, pending)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinute, pending)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, nextMinute, pending)
            }
        }

        private fun cancelRefreshSchedule(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SmallDateReminderWidget::class.java).apply {
                action = ACTION_MINUTE_REFRESH
            }
            val pending = PendingIntent.getBroadcast(
                context,
                MINUTE_REFRESH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
            pending.cancel()
        }

        private fun totalDaysText(from: LocalDate, to: LocalDate): String {
            val totalDays = ReminderDateMath.totalDays(from, to)
            return if (totalDays < 0) "Date passed" else "Total: $totalDays ${if (totalDays == 1L) "day" else "days"}"
        }

        private fun weekdayText(date: LocalDate): String =
            date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())


        private fun widgetCountdownText(
            now: LocalDateTime,
            target: LocalDateTime,
            small: Boolean,
            wide: Boolean
        ): String {
            if (!target.isAfter(now)) return "Due now"

            val zone = ZoneId.systemDefault()
            val zonedNow = now.atZone(zone)
            val zonedTarget = target.atZone(zone)
            val exact = ReminderDateMath.preciseCountdown(zonedNow, zonedTarget)
            if (small) {
                // Keep the compact 2×2 glanceable. Short reminders use exact
                // time; longer reminders keep the calendar hierarchy.
                val totalMinutes = java.time.Duration.between(zonedNow, zonedTarget).toMinutes()
                if (totalMinutes < 24L * 60L) return exact.replace(" remaining", "")
                val calendar = ReminderDateMath.calendarCountdown(now.toLocalDate(), target.toLocalDate())
                return calendar.lines().take(2).joinToString("\n")
            }

            val parts = ReminderDateMath.preciseCountdownParts(zonedNow, zonedTarget)
            if (wide) {
                // The 4×2 widget is deliberately wide: keep the complete countdown
                // but split it into two balanced lines so it remains readable in a
                // single-row widget.
                return if (parts.size > 3) {
                    parts.take(3).joinToString(" · ") + "\n" + parts.drop(3).joinToString(" · ")
                } else {
                    parts.joinToString(" · ")
                }
            }
            // Fallback for the old tall provider during an update. The current picker
            // no longer exposes a tall widget.
            return parts.joinToString("\n")
        }

        private fun currentDateText(now: LocalDateTime): String =
            now.toLocalDate().format(DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()))

        private fun currentDateTimeText(now: LocalDateTime): String {
            val datePart = now.toLocalDate().format(dateFormatter)
            val timePart = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
            return "$datePart · $timePart"
        }

        private fun widgetDateText(
            now: LocalDateTime,
            reminder: DateReminder,
            wide: Boolean
        ): String {
            val target = reminderTarget(reminder, now)
            val diffMinutes = ChronoUnit.MINUTES.between(now, target)
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            return when {
                diffMinutes >= 0L && diffMinutes < 24L * 60L && target.toLocalDate().isEqual(now.toLocalDate()) ->
                    "Today · ${reminder.time.format(timeFormatter)}"
                diffMinutes >= 24L * 60L && diffMinutes < 48L * 60L && target.toLocalDate().isEqual(now.toLocalDate().plusDays(1)) ->
                    "Tomorrow · ${reminder.time.format(timeFormatter)}"
                else -> {
                    if (wide) {
                        "${target.toLocalDate().format(dateFormatter)} · ${reminder.time.format(timeFormatter)}"
                    } else {
                        target.toLocalDate().format(dateFormatter)
                    }
                }
            }
        }

        private fun isWidgetDark(context: Context): Boolean {
            val prefs = context.getSharedPreferences("day_calculator_settings", Context.MODE_PRIVATE)
            return when (prefs.getString("theme_mode", "SYSTEM")) {
                "DARK" -> true
                "LIGHT" -> false
                else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

        private fun countdownRingBitmap(
            context: Context,
            appWidgetId: Int,
            reminder: DateReminder,
            sizeDp: Int,
            dark: Boolean
        ): Bitmap {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Use the same 7dp stroke on every widget size, matching the thick
            // Small ring without changing each ring's diameter.
            val strokeDp = when { sizeDp <= 30 -> 4f; sizeDp <= 44 -> 5f; else -> 5f }
            val stroke = strokeDp * density
            val inset = stroke / 2f + 1f
            val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)

            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                // Deliberately opposite to the widget surface: black track on light,
                // white track on dark. No alpha tinting or glow.
                color = if (dark) Color.rgb(68, 68, 68) else Color.rgb(210, 210, 210)
            }
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                // The agreed light-green accent is the only widget color.
                color = Color.rgb(105, 211, 138)
            }

            canvas.drawArc(bounds, -90f, 360f, false, track)

            val nowDateTime = LocalDateTime.now(ZoneId.systemDefault())
            val targetDateTime = reminderTarget(reminder, nowDateTime)
            val targetMillis = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val startMillis = if (reminder.repeatsYearly) {
                val previousTarget = targetDateTime.minusYears(1)
                val previousMillis = previousTarget.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                previousMillis
            } else {
                assignmentStartMillis(context, appWidgetId)
            }
            val nowMillis = System.currentTimeMillis()
            // The ring represents real elapsed progress from the moment this
            // widget was assigned to the reminder. It fills clockwise over time
            // and is completely green when the reminder is reached.
            val fraction = when {
                targetMillis <= startMillis -> 1f
                nowMillis <= startMillis -> 0f
                nowMillis >= targetMillis -> 1f
                else -> (nowMillis - startMillis).toFloat() /
                    (targetMillis - startMillis).toFloat()
            }.coerceIn(0f, 1f)

            if (fraction > 0f) {
                canvas.drawArc(bounds, -90f, 360f * fraction, false, progressPaint)
            }
            return bitmap
        }

        private data class YearProgressData(
            val year: Int,
            val totalDays: Long,
            val daysPassed: Long,
            val daysLeft: Long,
            val percent: Double,
            val remainingText: String,
            val shortRemainingText: String
        )

        private fun yearProgress(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): YearProgressData {
            val date = now.toLocalDate()
            val start = LocalDate.of(date.year, 1, 1)
            val next = start.plusYears(1)
            val total = ChronoUnit.DAYS.between(start, next)
            val passed = ChronoUnit.DAYS.between(start, date).coerceIn(0L, total)
            val left = total - passed

            // Keep the day-count semantics above for the text, but make the ring
            // represent the actual elapsed fraction of the year. This lets the
            // ring progress throughout each day instead of staying frozen until
            // midnight. Use the device timezone so the visual matches the local
            // calendar year; Duration also handles any timezone offset changes.
            val zone = ZoneId.systemDefault()
            val yearStart = start.atStartOfDay(zone)
            val yearEnd = next.atStartOfDay(zone)
            val totalYearMillis = java.time.Duration.between(yearStart, yearEnd).toMillis()
            val elapsedYearMillis = java.time.Duration.between(yearStart, now.atZone(zone))
                .toMillis()
                .coerceIn(0L, totalYearMillis)
            val percent = if (totalYearMillis <= 0L) {
                0.0
            } else {
                elapsedYearMillis * 100.0 / totalYearMillis
            }
            val period = Period.between(date, next.minusDays(1))
            val zonedNow = now.atZone(zone)
            val anchor = zonedNow.plus(period)
            val remaining = java.time.Duration.between(anchor, next.atStartOfDay(zone))
            val remainingMinutes = remaining.toMinutes().coerceAtLeast(0L)
            val hours = remainingMinutes / 60L
            val minutes = remainingMinutes % 60L

            val parts = mutableListOf<String>()
            if (period.months != 0) parts += "${period.months} ${if (period.months == 1) "month" else "months"}"
            if (period.days != 0) parts += "${period.days} ${if (period.days == 1) "day" else "days"}"
            if (hours != 0L) parts += "$hours ${if (hours == 1L) "hour" else "hours"}"
            if (minutes != 0L || parts.isEmpty()) parts += "$minutes min"

            val shortParts = mutableListOf<String>()
            if (period.months != 0) shortParts += "${period.months} ${if (period.months == 1) "month" else "months"}"
            if (period.days != 0) shortParts += "${period.days} ${if (period.days == 1) "day" else "days"}"
            if (shortParts.isEmpty()) shortParts += "$left ${if (left == 1L) "day" else "days"}"

            return YearProgressData(
                date.year, total, passed, left, percent,
                parts.joinToString(" · ") + " left",
                shortParts.joinToString(" ") + " left"
            )
        }

        private fun yearProgressRingBitmap(
            context: Context,
            sizeDp: Int,
            dark: Boolean
        ): Bitmap {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val strokeDp = when { sizeDp <= 30 -> 4f; sizeDp <= 44 -> 5f; else -> 5f }
            val stroke = strokeDp * density
            val inset = stroke / 2f + 1f
            val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = if (dark) Color.rgb(68, 68, 68) else Color.rgb(210, 210, 210)
            }
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = Color.rgb(105, 211, 138)
            }
            canvas.drawArc(bounds, -90f, 360f, false, track)
            val progress = yearProgress().percent.toFloat().coerceIn(0f, 100f) / 100f
            if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, progressPaint)
            return bitmap
        }

        private fun applyWidgetPalette(context: Context, views: RemoteViews, reminder: DateReminder?) {
            val dark = isWidgetDark(context)
            // Match the app's Material 3 card surfaces: #F5F5F5 in Light and
            // #151515 in Dark, with near-black/near-white primary text.
            val background = if (dark) Color.rgb(21, 21, 21) else Color.rgb(245, 245, 245)
            val foreground = if (dark) Color.rgb(245, 245, 245) else Color.rgb(17, 17, 17)
            // Keep the rounded widget drawable; applying a flat color would remove its corners.
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (dark) R.drawable.widget_background_dark else R.drawable.widget_background_light
            )
            views.setTextColor(R.id.widget_label, foreground)
            views.setTextColor(R.id.widget_title, foreground)
            views.setTextColor(R.id.widget_date, if (dark) Color.rgb(189, 189, 189) else Color.rgb(90, 90, 90))
            views.setTextColor(R.id.widget_countdown, foreground)
            views.setTextColor(R.id.widget_total_days, if (dark) Color.rgb(189, 189, 189) else Color.rgb(90, 90, 90))
            views.setTextColor(R.id.widget_weekday, if (dark) Color.rgb(189, 189, 189) else Color.rgb(90, 90, 90))
        }

        private fun layoutForSize(context: Context, appWidgetId: Int): Int {
            // The widget picker exposes exactly two fixed providers. Each provider
            // has resizeMode="none", so this mapping is based on the provider class
            // rather than guessing from launcher-reported dimensions. This prevents
            // intermediate launcher sizes from silently switching to another layout.
            val providerName = AppWidgetManager.getInstance(context)
                .getAppWidgetInfo(appWidgetId)?.provider?.className

            return when (providerName) {
                SmallDateReminderWidget::class.java.name -> R.layout.widget_date_reminder_small
                WideDateReminderWidget::class.java.name -> R.layout.widget_date_reminder_wide
                // Compatibility fallback for an old widget ID during an upgrade.
                else -> R.layout.widget_date_reminder_small
            }
        }

        fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val layoutId = layoutForSize(context, appWidgetId)
            val views = RemoteViews(context.packageName, layoutId)
            var selectedId = selectedReminderId(context, appWidgetId)
            val reminders = ReminderStore.load(context)
            val isYearProgress = isYearProgressId(selectedId) && isYearProgressEnabled(context)
            // If a normal reminder assigned to this widget was deleted, clear the stale
            // assignment. A Year Progress assignment is dynamic and has no saved reminder.
            val reminder = if (!isYearProgressId(selectedId)) {
                selectedId?.let { id -> reminders.firstOrNull { it.id == id } }
            } else null
            if (selectedId != null && !isYearProgress && reminder == null) {
                clearSelectedReminder(context, appWidgetId)
                selectedId = null
            }

            val isSmall = layoutId == R.layout.widget_date_reminder_small
            val isWide = layoutId == R.layout.widget_date_reminder_wide
            val hasSelection = reminder != null || isYearProgress

            applyWidgetPalette(context, views, reminder)
            views.setViewVisibility(R.id.widget_ring, if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE)
            if (hasSelection) {
                val ringSizeDp = when {
                    isSmall -> 30
                    else -> 34
                }
                views.setImageViewBitmap(
                    R.id.widget_ring,
                    if (isYearProgress) yearProgressRingBitmap(context, ringSizeDp, isWidgetDark(context))
                    else countdownRingBitmap(context, appWidgetId, reminder!!, ringSizeDp, isWidgetDark(context))
                )
            }

            // Keep the compact 2×2 glanceable while the 4×2 wide widget shows the
            // complete reference-style countdown and weekday.
            views.setViewVisibility(R.id.widget_countdown, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_total_days, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_weekday, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_small_days, android.view.View.GONE)
            views.setTextViewText(R.id.widget_small_days, "")

            when {
                isYearProgress -> {
                    val progress = yearProgress()
                    views.setViewVisibility(R.id.widget_label, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_label, "")
                    if (isSmall) {
                        val now = LocalDateTime.now(ZoneId.systemDefault())
                        views.setTextViewText(R.id.widget_title, progress.year.toString())
                        views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                        views.setTextViewText(R.id.widget_date, currentDateText(now))
                        views.setTextViewText(R.id.widget_countdown, progress.shortRemainingText)
                        views.setTextViewText(R.id.widget_total_days, "${progress.daysPassed} days passed")
                        views.setViewVisibility(R.id.widget_total_days, android.view.View.VISIBLE)
                        views.setTextViewText(R.id.widget_weekday, "")
                        views.setViewVisibility(R.id.widget_weekday, android.view.View.GONE)
                    } else if (isWide) {
                        views.setTextViewText(R.id.widget_title, "Year Progress")
                        views.setTextViewText(R.id.widget_date, currentDateTimeText(LocalDateTime.now(ZoneId.systemDefault())))
                        views.setTextViewText(R.id.widget_countdown, progress.remainingText)
                        views.setTextViewText(R.id.widget_total_days, "${progress.daysPassed} days passed")
                        views.setViewVisibility(R.id.widget_total_days, android.view.View.VISIBLE)
                        views.setTextViewText(R.id.widget_weekday, weekdayText(LocalDate.now(ZoneId.systemDefault())))
                        views.setViewVisibility(R.id.widget_weekday, android.view.View.VISIBLE)
                    }
                }
                reminder == null && selectedId == null -> {
                    views.setViewVisibility(R.id.widget_label, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_label, "")
                    views.setTextViewText(R.id.widget_title, "Choose a reminder")
                    views.setTextViewText(R.id.widget_date, "Tap this widget to select one")
                    views.setTextViewText(R.id.widget_countdown, "Select reminder")
                    views.setTextViewText(R.id.widget_total_days, "")
                    views.setViewVisibility(R.id.widget_small_days, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_weekday, "")
                }
                reminder == null -> {
                    views.setViewVisibility(R.id.widget_label, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_label, "")
                    views.setTextViewText(R.id.widget_title, "Reminder not found")
                    views.setTextViewText(R.id.widget_date, "It may have been deleted")
                    views.setTextViewText(R.id.widget_countdown, "Choose another reminder")
                    views.setTextViewText(R.id.widget_total_days, "")
                    views.setViewVisibility(R.id.widget_small_days, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_weekday, "")
                }
                else -> {
                    // Configured widgets use a clean title-first hierarchy with no
                    // decorative label or refresh control.
                    views.setViewVisibility(R.id.widget_label, android.view.View.GONE)
                    views.setTextViewText(R.id.widget_label, "")
                    views.setTextViewText(R.id.widget_title, reminder.title)
                    val now = LocalDateTime.now(ZoneId.systemDefault())
                    val today = now.toLocalDate()
                    val target = reminderTarget(reminder, now)
                    val countdown = widgetCountdownText(now, target, isSmall, isWide)
                    views.setTextViewText(R.id.widget_date, widgetDateText(now, reminder, isWide))
                    views.setTextViewText(R.id.widget_countdown, countdown)
                    views.setTextViewText(R.id.widget_total_days, totalDaysText(today, reminderTarget(reminder, now).toLocalDate()).removePrefix("Total: "))
                    if (isSmall) {
                        // Short reminders use their exact due context instead of a
                        // redundant total-days line, which keeps the 2x2 glanceable.
                        val isShort = target.isAfter(now) && ChronoUnit.HOURS.between(now, target) < 48L
                        val showTotal = !isShort && !countdown.equals("Due now", ignoreCase = true) &&
                            !countdown.matches(Regex("\\d+ day(s)?"))
                        views.setViewVisibility(
                            R.id.widget_total_days,
                            if (showTotal) android.view.View.VISIBLE else android.view.View.GONE
                        )
                    } else if (isWide) {
                        views.setViewVisibility(R.id.widget_total_days, android.view.View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_total_days, android.view.View.GONE)
                    }
                    if (isWide) {
                        views.setTextViewText(R.id.widget_weekday, weekdayText(reminderTarget(reminder, LocalDateTime.now()).toLocalDate()))
                        views.setViewVisibility(R.id.widget_weekday, android.view.View.VISIBLE)
                    } else {
                        views.setTextViewText(R.id.widget_weekday, "")
                    }
                }
            }

            // Every unconfigured/invalid widget opens the SAME MainActivity-based
            // chooser used by Android's widget configuration flow. This avoids a
            // second configuration Activity that may not be declared by the app and
            // keeps the tap-to-reconfigure path identical across launchers.
            val hasSelectedReminder = hasSelection
            val launchIntent = if (!hasSelectedReminder) {
                Intent(context, MainActivity::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_reminder", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Keep the 3.39 visual design unchanged: the whole card opens the reminder,
            // while the existing bottom-right progress ring is the manual refresh target.
            // The ring bitmap and its progress calculation are deliberately untouched.
            views.setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)
            if (hasSelectedReminder) {
                val refreshIntent = Intent(context, SmallDateReminderWidget::class.java).apply {
                    action = ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    REFRESH_REQUEST_CODE + appWidgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_ring, refreshPendingIntent)
            }
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}


/** Fixed 2×2 widget provider. It cannot be arbitrarily resized by the launcher. */
class SmallDateReminderWidget : DateReminderWidget()

/** Fixed 4×2 wide widget provider. It cannot be arbitrarily resized by the launcher. */
class WideDateReminderWidget : DateReminderWidget()
