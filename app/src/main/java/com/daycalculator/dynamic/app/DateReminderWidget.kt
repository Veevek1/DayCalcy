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
internal enum class WidgetWidth { COMPACT, MEDIUM, WIDE }

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
        if (intent.action == TodoWidget.ACTION_TOGGLE || intent.action == TodoWidget.ACTION_EDIT || intent.action == TodoWidget.ACTION_ADD) {
            TodoWidget.handleToggle(context, intent)
            return
        }
        if (intent.action == ACTION_REFRESH_TRACKER) {
            val manager = AppWidgetManager.getInstance(context)
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) updateAppWidget(context, manager, widgetId) else updateAll(context)
            return
        }
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
        appWidgetIds.forEach { clearSelectedReminder(context, it); setSelectedContentType(context, it, "REMINDER"); TrackerWidgetData.clear(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private val WIDGET_PROVIDERS = listOf(
            SmallDateReminderWidget::class.java,
            WideDateReminderWidget::class.java,
            ResizableDayCalcyWidget::class.java,
            ResizableTodoWidget::class.java
        )

        private const val PREFS = "date_reminder_widgets"
        private const val KEY_PREFIX = "reminder_"
        private const val KEY_START_PREFIX = "start_"
        private const val KEY_REMINDER_START_PREFIX = "ringstart_"
        const val YEAR_PROGRESS_ID = "__YEAR_PROGRESS__"
        private const val YEAR_PROGRESS_PREFS = "date_reminder_settings"
        private const val YEAR_PROGRESS_ENABLED_KEY = "year_progress_enabled"
        private const val ACTION_REFRESH = "com.daycalculator.dynamic.app.WIDGET_REFRESH"
        private const val ACTION_REFRESH_TRACKER = "com.daycalculator.dynamic.app.TRACKER_WIDGET_REFRESH"
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

        private const val KEY_TYPE_PREFIX = "type_"

        /** "TRACKER" or "REMINDER". */
        fun selectedContentType(context: Context, appWidgetId: Int): String {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TYPE_PREFIX + appWidgetId, "REMINDER")
            return if (stored == "TRACKER") "TRACKER" else "REMINDER"
        }

        fun setSelectedContentType(context: Context, appWidgetId: Int, type: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TYPE_PREFIX + appWidgetId, if (type == "TRACKER") "TRACKER" else "REMINDER")
                .apply()
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

        /**
         * When the ring for a reminder starts filling. It is stored once per reminder, not per
         * widget, so every widget showing the same reminder has the same ring, and adding,
         * resizing or replacing a widget never resets it to empty.
         */
        private fun assignmentStartMillis(context: Context, appWidgetId: Int, reminderId: String): Long {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = KEY_REMINDER_START_PREFIX + reminderId
            val existing = prefs.getLong(key, 0L)
            if (existing > 0L) return existing

            // First time: reuse the oldest start time that widgets already showing this
            // reminder have stored, so rings that were already partly filled keep their progress.
            var oldest = Long.MAX_VALUE
            prefs.all.forEach { (k, v) ->
                if (k.startsWith(KEY_PREFIX) && v == reminderId) {
                    val id = k.removePrefix(KEY_PREFIX)
                    val start = prefs.getLong(KEY_START_PREFIX + id, 0L)
                    if (start in 1 until oldest) oldest = start
                }
            }
            val own = prefs.getLong(KEY_START_PREFIX + appWidgetId, 0L)
            if (own in 1 until oldest) oldest = own
            val start = if (oldest != Long.MAX_VALUE) oldest else System.currentTimeMillis()
            prefs.edit().putLong(key, start).apply()
            return start
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

            // Schedule the next tick on the next minute boundary. These are
            // NON-wakeup (RTC) alarms on purpose: the widget only needs to be
            // fresh when the screen is on, so the device is never woken up just
            // to redraw a countdown. If the phone is asleep, the alarm is
            // delivered when it wakes, the widget redraws, and the next tick is
            // aligned again. The Play build always uses an inexact alarm (exact
            // alarms are reserved for the reminders themselves); the direct
            // build uses a non-wakeup exact alarm when the user granted access.
            val now = System.currentTimeMillis()
            val nextMinute = ((now / 60_000L) + 1L) * 60_000L
            val canUseExact = BuildConfig.EXACT_WIDGET_REFRESH &&
                (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                    alarm.canScheduleExactAlarms())
            if (canUseExact) {
                alarm.setExact(AlarmManager.RTC, nextMinute, pending)
            } else {
                alarm.set(AlarmManager.RTC, nextMinute, pending)
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
            wide: Boolean,
            medium: Boolean = false
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
                // Only when years are involved, use a compact form (e.g. "1 year" / "1m 13d")
                // so it fits the 2x2 widget. Everything else stays as before.
                ReminderDateMath.compactYearCountdown(now.toLocalDate(), target.toLocalDate())?.let { return it }
                val calendar = ReminderDateMath.calendarCountdown(now.toLocalDate(), target.toLocalDate())
                return calendar.lines().take(2).joinToString("\n")
            }

            val parts = ReminderDateMath.preciseCountdownParts(zonedNow, zonedTarget)
            if (wide) {
                // The 4×2 widget is deliberately wide: keep the complete countdown
                // but split it into two balanced lines so it remains readable in a
                // single-row widget.
                if (medium) return balanceCountdown(parts.joinToString(" · "))
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

        private fun rowLevel(width: WidgetWidth): Int = when (width) {
            WidgetWidth.COMPACT -> 0
            WidgetWidth.MEDIUM -> 1
            WidgetWidth.WIDE -> 2
        }

        /** Single-line countdown for the 1-row strip (2×1 compact, 3×1 medium, 4×1 full words). */
        private fun rowCountdownText(now: LocalDateTime, target: LocalDateTime, width: WidgetWidth): String {
            if (!target.isAfter(now)) return "Due now"
            val zone = ZoneId.systemDefault()
            val zonedNow = now.atZone(zone)
            val zonedTarget = target.atZone(zone)
            val level = rowLevel(width)
            val totalMinutes = java.time.Duration.between(zonedNow, zonedTarget).toMinutes()
            if (totalMinutes < 24L * 60L) {
                val parts = ReminderDateMath.preciseCountdownParts(zonedNow, zonedTarget)
                if (parts.isEmpty()) return "Due now"
                return when (level) {
                    0 -> parts.joinToString(" ") {
                        it.replace(" hours", "h").replace(" hour", "h").replace(" minutes", "m").replace(" minute", "m")
                    }
                    1 -> parts.joinToString(" ")
                    else -> parts.joinToString(" · ")
                }
            }
            return ReminderDateMath.rowCountdown(now.toLocalDate(), target.toLocalDate(), level)
        }

        private fun rowDateText(now: LocalDateTime, reminder: DateReminder, target: LocalDateTime, width: WidgetWidth): String {
            val base = widgetDateText(now, reminder, false)
            if (width != WidgetWidth.WIDE) return base
            val days = ReminderDateMath.totalDays(now.toLocalDate(), target.toLocalDate())
            return if (days >= 2L) "$base · $days days" else base
        }

        private fun yearRowCountdown(p: YearProgressData, width: WidgetWidth): String = when (width) {
            WidgetWidth.COMPACT -> {
                val parts = mutableListOf<String>()
                if (p.months > 0) parts += "${p.months}m"
                if (p.days > 0) parts += "${p.days}d"
                if (parts.isEmpty()) parts += "${p.daysLeft}d"
                parts.joinToString(" ") + " left"
            }
            WidgetWidth.MEDIUM -> p.shortRemainingText
            WidgetWidth.WIDE -> {
                val parts = mutableListOf<String>()
                if (p.months > 0) parts += "${p.months} ${if (p.months == 1) "month" else "months"}"
                if (p.days > 0) parts += "${p.days} ${if (p.days == 1) "day" else "days"}"
                if (parts.isEmpty()) parts += "${p.daysLeft} ${if (p.daysLeft == 1L) "day" else "days"}"
                "${p.year} ends in " + parts.joinToString(" · ")
            }
        }

        private fun currentDateText(now: LocalDateTime): String =
            now.toLocalDate().format(DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()))

        private fun currentDateTimeText(now: LocalDateTime): String {
            val datePart = now.toLocalDate().format(dateFormatter)
            val dayPart = weekdayText(now.toLocalDate())
            val timePart = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
            return "$datePart · $dayPart · $timePart"
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
                    if (wide) "Today · ${weekdayText(target.toLocalDate())} · ${reminder.time.format(timeFormatter)}"
                    else "Today · ${reminder.time.format(timeFormatter)}"
                diffMinutes >= 24L * 60L && diffMinutes < 48L * 60L && target.toLocalDate().isEqual(now.toLocalDate().plusDays(1)) ->
                    if (wide) "Tomorrow · ${weekdayText(target.toLocalDate())} · ${reminder.time.format(timeFormatter)}"
                    else "Tomorrow · ${reminder.time.format(timeFormatter)}"
                else -> {
                    if (wide) {
                        "${target.toLocalDate().format(dateFormatter)} · ${weekdayText(target.toLocalDate())} · ${reminder.time.format(timeFormatter)}"
                    } else {
                        target.toLocalDate().format(dateFormatter)
                    }
                }
            }
        }

        private fun isWidgetDark(context: Context): Boolean {
            val prefs = context.getSharedPreferences("day_calculator_settings", Context.MODE_PRIVATE)
            // The widget intentionally has only two appearances: Light and pure black.
            // System Default follows Android's current light/dark mode; either manual
            // dark variant (DARK or AMOLED) uses the same pure-black widget background.
            return when (prefs.getString("theme_mode", "SYSTEM")) {
                "LIGHT" -> false
                "DARK", "AMOLED" -> true
                else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
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
            val startMillis = previousReminderOccurrence(reminder, targetDateTime)
                ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                ?: assignmentStartMillis(context, appWidgetId, reminder.id)
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
            val shortRemainingText: String,
            val months: Int = 0,
            val days: Int = 0
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
            // Keep the wide widget countdown consistent: the year-ending label
            // explains the destination, while the two countdown lines contain
            // only matching time units (hours above minutes), without a one-off
            // "left" suffix on either line.
            val remainingText = when {
                minutes > 0L && parts.isNotEmpty() -> parts.joinToString(" · ") + "\n$minutes ${if (minutes == 1L) "minute" else "minutes"}"
                minutes > 0L -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
                parts.isNotEmpty() -> parts.joinToString(" · ")
                else -> "0 minutes"
            }

            val shortParts = mutableListOf<String>()
            if (period.months != 0) shortParts += "${period.months} ${if (period.months == 1) "month" else "months"}"
            if (period.days != 0) shortParts += "${period.days} ${if (period.days == 1) "day" else "days"}"
            if (shortParts.isEmpty()) shortParts += "$left ${if (left == 1L) "day" else "days"}"

            return YearProgressData(
                date.year, total, passed, left, percent,
                remainingText,
                shortParts.joinToString(" ") + " left",
                period.months,
                period.days
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
            // The widget has only two appearances: Light and pure black.
            // Both manual dark variants (Dark and AMOLED) use pure black.
            // System Default follows Android's current light/dark mode.
            val foreground = if (dark) Color.rgb(245, 245, 245) else Color.rgb(17, 17, 17)
            // Preserve rounded corners; widget_background_dark is pure black (#000000).
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

        /**
         * The resizable widget always keeps a 2-cell height, so its width-to-height ratio tells
         * the sizes apart on any phone grid: about 1:1 at 2 cells, 1.5:1 at 3 cells and 2:1 at
         * 4 cells. 2 cells use the compact layout; 3 and 4 cells use the wide layout, with
         * slightly smaller countdown text at 3 cells so nothing is cut off.
         */
        private const val MEDIUM_MIN_RATIO = 1.3f
        private const val WIDE_MIN_RATIO = 1.9f
        private const val MEDIUM_FALLBACK_MIN_WIDTH_DP = 180
        private const val WIDE_FALLBACK_MIN_WIDTH_DP = 250
        /** Heights below this (dp) mean the resizable widget was shrunk to a single row. */
        private const val ROW_MAX_HEIGHT_DP = 95
        private const val ROW_MEDIUM_MIN_RATIO = 2.3f
        private const val ROW_WIDE_MIN_RATIO = 3.2f

        /** True when the resizable widget has been resized down to one row. */
        internal fun isRowHeight(manager: AppWidgetManager, appWidgetId: Int): Boolean {
            if (manager.getAppWidgetInfo(appWidgetId)?.provider?.className != ResizableDayCalcyWidget::class.java.name) return false
            val height = manager.getAppWidgetOptions(appWidgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return height in 1 until ROW_MAX_HEIGHT_DP
        }

        internal fun widgetWidth(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetWidth {
            return when (manager.getAppWidgetInfo(appWidgetId)?.provider?.className) {
                WideDateReminderWidget::class.java.name -> WidgetWidth.WIDE
                ResizableDayCalcyWidget::class.java.name -> {
                    val options = manager.getAppWidgetOptions(appWidgetId)
                    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    if (height > 0) {
                        val ratio = width.toFloat() / height
                        if (height < ROW_MAX_HEIGHT_DP) {
                            // 1-row strip: 2, 3 and 4 cells wide give ratios of roughly 1.8, 2.7 and 3.7.
                            when {
                                ratio >= ROW_WIDE_MIN_RATIO -> WidgetWidth.WIDE
                                ratio >= ROW_MEDIUM_MIN_RATIO -> WidgetWidth.MEDIUM
                                else -> WidgetWidth.COMPACT
                            }
                        } else when {
                            ratio >= WIDE_MIN_RATIO -> WidgetWidth.WIDE
                            ratio >= MEDIUM_MIN_RATIO -> WidgetWidth.MEDIUM
                            else -> WidgetWidth.COMPACT
                        }
                    } else when {
                        width >= WIDE_FALLBACK_MIN_WIDTH_DP -> WidgetWidth.WIDE
                        width >= MEDIUM_FALLBACK_MIN_WIDTH_DP -> WidgetWidth.MEDIUM
                        else -> WidgetWidth.COMPACT
                    }
                }
                else -> WidgetWidth.COMPACT
            }
        }

        /** True when this widget instance should use the wide layout (3 cells and wider). */
        fun useWideLayout(context: Context, manager: AppWidgetManager, appWidgetId: Int): Boolean =
            widgetWidth(context, manager, appWidgetId) != WidgetWidth.COMPACT

        /** Re-splits a countdown such as "1 month · 14 days · 12 hours 48 minutes" into two even lines. */
        private fun balanceCountdown(text: String): String {
            val parts = text.replace("\n", " · ").split(" · ").filter { it.isNotBlank() }
            if (parts.size < 2) return text
            val firstLine = (parts.size + 1) / 2
            return parts.take(firstLine).joinToString(" · ") + "\n" + parts.drop(firstLine).joinToString(" · ")
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
                // Resizable widget: only 2 to 4 cells wide, so only two layouts are ever needed.
                ResizableDayCalcyWidget::class.java.name -> {
                    val manager = AppWidgetManager.getInstance(context)
                    when {
                        isRowHeight(manager, appWidgetId) -> R.layout.widget_date_reminder_row
                        useWideLayout(context, manager, appWidgetId) -> R.layout.widget_date_reminder_wide
                        else -> R.layout.widget_date_reminder_small
                    }
                }
                // Compatibility fallback for an old widget ID during an upgrade.
                else -> R.layout.widget_date_reminder_small
            }
        }

        fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            // The resizable widget always shows the to-do list (there is no content type to choose).
            if (manager.getAppWidgetInfo(appWidgetId)?.provider?.className == ResizableTodoWidget::class.java.name) {
                TodoWidget.update(context, manager, appWidgetId)
                return
            }
            if (selectedContentType(context, appWidgetId) == "TRACKER") {
                TrackerWidgetProvider.updateForExistingWidget(context, manager, appWidgetId)
                return
            }
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
            val medium = isWide && widgetWidth(context, manager, appWidgetId) == WidgetWidth.MEDIUM
            val isRow = layoutId == R.layout.widget_date_reminder_row
            val rowWidth = if (isRow) widgetWidth(context, manager, appWidgetId) else WidgetWidth.COMPACT
            val hasSelection = reminder != null || isYearProgress

            applyWidgetPalette(context, views, reminder)
            views.setViewVisibility(R.id.widget_ring, if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE)
            if (hasSelection) {
                val ringSizeDp = when {
                    isRow -> 28
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

            if (medium) {
                views.setTextViewTextSize(R.id.widget_countdown, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            }

            if (isWide) {
                views.setViewVisibility(R.id.widget_year_ends_label, android.view.View.GONE)
                views.setTextViewText(R.id.widget_year_ends_label, "")
            }

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
                    } else if (isRow) {
                        views.setTextViewText(
                            R.id.widget_title,
                            if (rowWidth == WidgetWidth.WIDE) "Year Progress" else progress.year.toString()
                        )
                        views.setTextViewText(R.id.widget_date, "${progress.daysPassed} days passed")
                        views.setTextViewText(R.id.widget_countdown, yearRowCountdown(progress, rowWidth))
                    } else if (isWide) {
                        views.setTextViewText(R.id.widget_title, "Year Progress")
                        views.setTextViewText(R.id.widget_date, currentDateTimeText(LocalDateTime.now(ZoneId.systemDefault())))
                        views.setTextViewText(R.id.widget_year_ends_label, "${progress.year} ends in")
                        views.setViewVisibility(R.id.widget_year_ends_label, android.view.View.VISIBLE)
                        views.setTextViewText(R.id.widget_countdown, if (medium) balanceCountdown(progress.remainingText) else progress.remainingText)
                        views.setTextViewText(R.id.widget_total_days, "${progress.daysPassed} days passed")
                        views.setViewVisibility(R.id.widget_total_days, android.view.View.VISIBLE)
                        views.setTextViewText(R.id.widget_weekday, "")
                        views.setViewVisibility(R.id.widget_weekday, android.view.View.GONE)
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
                    val displayTitle = if (isSmall) {
                        // Let TextView wrap the complete reminder title naturally
                        // within the actual widget width. Words stay on the first
                        // line whenever they fit; overflow moves to line two, where
                        // the XML end-ellipsis handles any remaining text.
                        reminder.title.trim()
                    } else reminder.title
                    views.setTextViewText(R.id.widget_title, displayTitle)
                    val now = LocalDateTime.now(ZoneId.systemDefault())
                    val today = now.toLocalDate()
                    val target = reminderTarget(reminder, now)
                    val countdown = if (isRow) rowCountdownText(now, target, rowWidth)
                        else widgetCountdownText(now, target, isSmall, isWide, medium)
                    views.setTextViewText(
                        R.id.widget_date,
                        if (isRow) rowDateText(now, reminder, target, rowWidth) else widgetDateText(now, reminder, isWide)
                    )
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
                        views.setTextViewText(R.id.widget_weekday, "")
                        views.setViewVisibility(R.id.widget_weekday, android.view.View.GONE)
                    } else {
                        views.setTextViewText(R.id.widget_weekday, "")
                    }
                }
            }

            // The 2-cell strip has no room for the date line.
            // Set it both ways: when the launcher keeps the same layout while the widget is
            // resized, it only re-applies these changes, so an old GONE would otherwise stay.
            if (isRow) {
                views.setViewVisibility(
                    R.id.widget_date,
                    if (rowWidth == WidgetWidth.COMPACT) android.view.View.GONE else android.view.View.VISIBLE
                )
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

/**
 * One resizable widget for a date reminder or a saved tracker. It is 2 to 4 cells wide and
 * 1 or 2 cells tall: 2-row sizes use the compact/wide layouts, 1-row sizes use a text strip.
 */
class ResizableDayCalcyWidget : DateReminderWidget()

/** To-do list widget that the launcher can resize freely (width and height). Always shows the to-do list. */
class ResizableTodoWidget : DateReminderWidget()
