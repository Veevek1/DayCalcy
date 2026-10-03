package com.daycalculator.dynamic.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.time.ZoneId
import java.util.UUID

internal data class DateReminder(
    val id: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime = LocalTime.of(9, 0),
    val repeatsYearly: Boolean = false
)

internal object ReminderStore {
    private const val PREFS = "day_calculator_reminders"
    private const val KEY = "items"

    fun load(context: Context): List<DateReminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split("|", limit = 5)
            if (p.size < 3) return@mapNotNull null
            val date = runCatching { LocalDate.parse(p[2]) }.getOrNull() ?: return@mapNotNull null
            val time = if (p.size >= 4) {
                runCatching { LocalTime.parse(p[3]) }.getOrNull() ?: LocalTime.of(9, 0)
            } else {
                LocalTime.of(9, 0)
            }
            val repeatsYearly = p.getOrNull(4)?.toBooleanStrictOrNull() ?: false
            if (p[0].isBlank() || p[1].isBlank()) null else DateReminder(p[0], p[1], date, time, repeatsYearly)
        }.sortedWith(compareBy<DateReminder> { it.date }.thenBy { it.time }).toList()
    }

    fun save(context: Context, reminders: List<DateReminder>) {
        val raw = reminders.joinToString("\n") {
            "${it.id}|${it.title.replace("|", " ").replace("\n", " ")}|${it.date}|${it.time}|${it.repeatsYearly}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}

internal fun reminderTarget(reminder: DateReminder, now: LocalDateTime): LocalDateTime {
    if (!reminder.repeatsYearly) return LocalDateTime.of(reminder.date, reminder.time)
    val year = now.year
    val candidateDate = runCatching { reminder.date.withYear(year) }.getOrElse {
        if (reminder.date.monthValue == 2 && reminder.date.dayOfMonth == 29) LocalDate.of(year, 2, 28) else reminder.date.withYear(year)
    }
    val candidate = LocalDateTime.of(candidateDate, reminder.time)
    return if (candidate.isAfter(now)) candidate else {
        val nextDate = runCatching { reminder.date.withYear(year + 1) }.getOrElse {
            if (reminder.date.monthValue == 2 && reminder.date.dayOfMonth == 29) LocalDate.of(year + 1, 2, 28) else reminder.date.withYear(year + 1)
        }
        LocalDateTime.of(nextDate, reminder.time)
    }
}

private const val REMINDER_CHANNEL_ID = "date_reminders"
private const val REMINDER_ACTION = "com.daycalculator.dynamic.app.DATE_REMINDER"
private const val REMINDER_ACTION_MARK_DONE = "com.daycalculator.dynamic.app.MARK_REMINDER_DONE"
private const val REMINDER_ACTION_SNOOZE = "com.daycalculator.dynamic.app.SNOOZE_REMINDER"
private const val REMINDER_SNOOZE_MINUTES = 10L

internal object ReminderScheduler {
    private const val CHANNEL_ID = REMINDER_CHANNEL_ID
    private const val ACTION = REMINDER_ACTION

    // AlarmManager/PendingIntent request codes are finite integers. Do not derive
    // them from String.hashCode(): different reminder IDs can collide and then
    // replace/cancel each other's alarms. A small persistent allocator gives each
    // reminder a stable, unique request code for the lifetime of that reminder.
    private const val REQUEST_PREFS = "date_reminder_alarm_request_codes"
    private const val ID_PREFIX = "id_"
    private const val NEXT_KEY = "next_code"
    private const val REQUEST_BASE = 100_000
    private const val REQUEST_LIMIT = 1_000_000_000
    private const val LEGACY_REQUEST_BASE = 42_000

    private val requestCodeLock = Any()

    fun schedule(context: Context, reminder: DateReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val target = reminderTarget(reminder, now)
        val trigger = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (trigger <= System.currentTimeMillis()) return

        val code = requestCode(context, reminder.id)
        val intent = reminderIntent(context, reminder, target)
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // AlarmClock is preferred because this is a user-visible clock event. On
        // Android 12+, exact-alarm access is a special permission and may be denied.
        // In that case, still schedule an inexact idle-safe alarm instead of silently
        // dropping the reminder. If exact access is granted later, rescheduleAll()
        // upgrades the same PendingIntent to an exact alarm.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context,
                code + 100_000,
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_reminder", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun snooze(context: Context, reminder: DateReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val target = LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(REMINDER_SNOOZE_MINUTES)
        val code = requestCode(context, reminder.id)
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            reminderIntent(context, reminder, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context,
                code + 100_000,
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_reminder", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), pending)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val code = existingRequestCode(context, reminderId)
        cancelWithCode(context, alarm, reminderId, code ?: legacyRequestCode(reminderId))
        // A reminder may already have fired. Remove that posted notification too.
        // Do this before releasing the persistent request-code mapping.
        if (code != null) {
            NotificationManagerCompat.from(context).cancel(code)
            context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
                .edit().remove(ID_PREFIX + reminderId).apply()
        }
    }

    fun rescheduleAll(context: Context) {
        // Cancel alarms created by the older hash-based request-code scheme before
        // scheduling with the collision-safe allocator. This prevents duplicate
        // notifications after an upgrade and also repairs any previous collision.
        ReminderStore.load(context).forEach { reminder ->
            cancelLegacyAlarm(context, reminder.id)
            schedule(context, reminder)
        }
    }

    private const val YEAR_END_REQUEST_CODE = 2_000_001
    private const val NEW_YEAR_REQUEST_CODE = 2_000_002
    private const val YEAR_END_NOTIFICATION_ID = 2_100_001
    private const val NEW_YEAR_NOTIFICATION_ID = 2_100_002
    private const val YEAR_END_HOUR = 21
    private const val NEW_YEAR_HOUR = 9
    private const val YEAR_END_ACTION = "com.daycalculator.dynamic.app.YEAR_END"
    private const val NEW_YEAR_ACTION = "com.daycalculator.dynamic.app.NEW_YEAR"

    private val yearEndMessages = listOf(
        "Another year has become memories. Keep the lessons, cherish the moments, and move forward with hope.",
        "You made it through another year. Take a moment to appreciate how far you've come.",
        "One chapter closes tonight. Carry the good memories and the lessons into the next one.",
        "The year is ending, but your journey continues. Be proud of the progress you made.",
        "The days became moments and memories. Take the good with you into the new year."
    )

    private val newYearMessages = listOf(
        "A new year begins with new possibilities. Make each day count.",
        "Welcome to a fresh year. Take the next step with confidence and keep moving forward.",
        "A new chapter starts today. Fill it with moments that matter.",
        "New days are waiting. Use them well, one day at a time.",
        "A fresh beginning is here. Keep your goals close and make this year meaningful."
    )

    fun scheduleYearProgressNotifications(context: Context) {
        createChannel(context)
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val yearEndDate = LocalDate.of(now.year, 12, 31)
        val newYearDate = yearEndDate.plusDays(1)
        scheduleSpecialAlarm(
            context,
            YEAR_END_REQUEST_CODE,
            YEAR_END_ACTION,
            LocalDateTime.of(yearEndDate, LocalTime.of(YEAR_END_HOUR, 0))
        )
        scheduleSpecialAlarm(
            context,
            NEW_YEAR_REQUEST_CODE,
            NEW_YEAR_ACTION,
            LocalDateTime.of(newYearDate, LocalTime.of(NEW_YEAR_HOUR, 0))
        )
    }

    private fun scheduleSpecialAlarm(
        context: Context,
        requestCode: Int,
        action: String,
        requestedTarget: LocalDateTime
    ) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val target = if (requestedTarget.isAfter(now)) requestedTarget else {
            if (action == YEAR_END_ACTION) {
                LocalDateTime.of(LocalDate.of(now.year + 1, 12, 31), LocalTime.of(YEAR_END_HOUR, 0))
            } else {
                LocalDateTime.of(LocalDate.of(now.year + 1, 1, 1), LocalTime.of(NEW_YEAR_HOUR, 0))
            }
        }
        val trigger = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DateReminderReceiver::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode + 10_000,
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_year_progress", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger, showIntent),
                pending
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancelSpecialAlarms(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(
            YEAR_END_REQUEST_CODE to YEAR_END_ACTION,
            NEW_YEAR_REQUEST_CODE to NEW_YEAR_ACTION
        ).forEach { (code, action) ->
            val pending = PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, DateReminderReceiver::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
            pending.cancel()
        }
    }

    fun yearEndMessage(year: Int): String = yearEndMessages[Math.floorMod(year, yearEndMessages.size)]
    fun newYearMessage(year: Int): String = newYearMessages[Math.floorMod(year, newYearMessages.size)]

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Date reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for saved DayCalcy reminders" }
            manager.createNotificationChannel(channel)
        }
    }

    fun channelId() = CHANNEL_ID

    private fun reminderIntent(
        context: Context,
        reminder: DateReminder,
        target: LocalDateTime? = null
    ): Intent = Intent(context, DateReminderReceiver::class.java).apply {
        action = ACTION
        putExtra("id", reminder.id)
        putExtra("title", reminder.title)
        putExtra("date", reminder.date.toString())
        target?.let { putExtra("target", it.toString()) }
    }

    private fun cancelIntent(context: Context, id: String): Intent =
        Intent(context, DateReminderReceiver::class.java).apply {
            action = ACTION
            putExtra("id", id)
        }

    fun notificationId(context: Context, id: String): Int =
        existingRequestCode(context, id) ?: requestCode(context, id)

    private fun requestCode(context: Context, id: String): Int = synchronized(requestCodeLock) {
        val prefs = context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
        val key = ID_PREFIX + id
        val existing = prefs.getInt(key, -1)
        if (existing >= REQUEST_BASE) return@synchronized existing

        val used = prefs.all.values.mapNotNull { (it as? Number)?.toInt() }.toHashSet()
        var next = prefs.getInt(NEXT_KEY, REQUEST_BASE).coerceAtLeast(REQUEST_BASE)
        while (next in used || next >= REQUEST_LIMIT) {
            next++
            if (next >= REQUEST_LIMIT) next = REQUEST_BASE
        }
        prefs.edit()
            .putInt(key, next)
            .putInt(NEXT_KEY, if (next + 1 >= REQUEST_LIMIT) REQUEST_BASE else next + 1)
            .apply()
        next
    }

    private fun existingRequestCode(context: Context, id: String): Int? =
        context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
            .getInt(ID_PREFIX + id, -1)
            .takeIf { it >= REQUEST_BASE }

    private fun legacyRequestCode(id: String): Int =
        LEGACY_REQUEST_BASE + (id.hashCode() and 0x7fff)

    private fun cancelWithCode(context: Context, alarm: AlarmManager, id: String, code: Int) {
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            cancelIntent(context, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)
        pending.cancel()
    }

    private fun cancelLegacyAlarm(context: Context, id: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelWithCode(context, alarm, id, legacyRequestCode(id))
    }
}

internal class DateReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.createChannel(context)
        when (intent.action) {
            REMINDER_ACTION_MARK_DONE -> {
                val reminderId = intent.getStringExtra("id") ?: return
                val reminders = ReminderStore.load(context)
                if (reminders.any { it.id == reminderId }) {
                    ReminderScheduler.cancel(context, reminderId)
                    ReminderStore.save(context, reminders.filterNot { it.id == reminderId })
                    DateReminderWidget.updateAll(context)
                }
                return
            }
            REMINDER_ACTION_SNOOZE -> {
                val reminderId = intent.getStringExtra("id") ?: return
                val reminder = ReminderStore.load(context).firstOrNull { it.id == reminderId } ?: return
                NotificationManagerCompat.from(context).cancel(ReminderScheduler.notificationId(context, reminderId))
                ReminderScheduler.snooze(context, reminder)
                return
            }
            "com.daycalculator.dynamic.app.YEAR_END" -> {
                postYearEndNotification(context)
                ReminderScheduler.scheduleYearProgressNotifications(context)
                return
            }
            "com.daycalculator.dynamic.app.NEW_YEAR" -> {
                postNewYearNotification(context)
                ReminderScheduler.scheduleYearProgressNotifications(context)
                return
            }
        }

        val title = intent.getStringExtra("title") ?: "Date Reminder"
        val date = intent.getStringExtra("date") ?: ""
        val reminderId = intent.getStringExtra("id") ?: title
        val reminder = ReminderStore.load(context).firstOrNull { it.id == reminderId }
        // Ignore stale alarm deliveries for reminders that were already deleted.
        // Alarm cancellation and receiver delivery can race, so cancellation alone
        // cannot guarantee that a queued broadcast will never execute.
        if (reminder == null) return
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_reminder", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            ReminderScheduler.notificationId(context, reminderId) + 10_000_000,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val target = intent.getStringExtra("target")
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: runCatching { LocalDateTime.of(LocalDate.parse(date), reminder?.time ?: LocalTime.of(9, 0)) }.getOrNull()

        val now = LocalDateTime.now(ZoneId.systemDefault())
        val relation = when {
            target == null -> "Reminder"
            target.toLocalDate().isEqual(now.toLocalDate()) -> "Today"
            target.toLocalDate().isEqual(now.toLocalDate().plusDays(1)) -> "Tomorrow"
            target.toLocalDate().isAfter(now.toLocalDate()) -> {
                val days = ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())
                "In $days ${if (days == 1L) "day" else "days"}"
            }
            else -> "Reminder"
        }
        val dateText = target?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        val weekday = target?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val special = reminderNotificationStyle(title)
        val lines = buildList {
            add(relation)
            if (dateText != null && weekday != null) add("$dateText · $weekday")
            if (reminder?.repeatsYearly == true) add("Every year")
            special.message?.let { add(it) }
        }
        val contentText = lines.firstOrNull() ?: "Reminder"
        val expandedText = lines.joinToString("\n")
        val notificationId = ReminderScheduler.notificationId(context, reminderId)
        val doneIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 20_000_000,
            Intent(context, DateReminderReceiver::class.java).apply {
                action = REMINDER_ACTION_MARK_DONE
                putExtra("id", reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 30_000_000,
            Intent(context, DateReminderReceiver::class.java).apply {
                action = REMINDER_ACTION_SNOOZE
                putExtra("id", reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(special.titlePrefix?.let { "$it $title" } ?: title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_notification, "Done", doneIntent)
            .addAction(R.drawable.ic_notification, "Snooze 10 min", snoozeIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
        if (reminder?.repeatsYearly == true) {
            ReminderScheduler.schedule(context, reminder)
        }
    }

    private fun reminderNotificationStyle(title: String): ReminderNotificationStyle {
        val normalized = title.lowercase(Locale.getDefault())
        return when {
            Regex("\\b(birthday|bday)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("🎂", "Have a wonderful day!")
            Regex("\\b(anniversary|wedding anniversary)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("💍", "Another special year together.")
            Regex("\\b(electricity|power)\\b.*\\b(bill|payment|due)\\b|\\b(bill|payment|due)\\b.*\\b(electricity|power)\\b")
                .containsMatchIn(normalized) ->
                ReminderNotificationStyle("⚡", "Payment reminder")
            Regex("\\b(school|college|tuition)\\b.*\\b(fee|fees|payment)\\b|\\b(fee|fees)\\b.*\\b(school|college|tuition)\\b")
                .containsMatchIn(normalized) ->
                ReminderNotificationStyle("🎓", "Fee reminder")
            Regex("\\b(doctor|dentist|clinic|hospital|appointment)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("🩺", "Appointment reminder")
            Regex("\\b(vehicle|car|bike|scooter)\\b.*\\b(service|servicing|maintenance)\\b|\\b(service|servicing|maintenance)\\b.*\\b(vehicle|car|bike|scooter)\\b")
                .containsMatchIn(normalized) ->
                ReminderNotificationStyle("🚗", "Service reminder")
            Regex("\\b(rent|emi|loan|insurance|tax|recharge|water bill|internet bill|mobile bill)\\b")
                .containsMatchIn(normalized) ->
                ReminderNotificationStyle("💳", "Payment reminder")
            Regex("\\b(exam|test|assignment|project|admission|registration)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("📝", "Education reminder")
            Regex("\\b(meeting|work|office|deadline)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("💼", "Work reminder")
            Regex("\\b(passport|visa|license|licence|renewal|document)\\b").containsMatchIn(normalized) ->
                ReminderNotificationStyle("📄", "Renewal reminder")
            else -> ReminderNotificationStyle(null, null)
        }
    }

    private data class ReminderNotificationStyle(
        val titlePrefix: String?,
        val message: String?
    )

    private fun postYearEndNotification(context: Context) {
        val year = LocalDate.now().year
        val total = LocalDate.of(year, 1, 1).lengthOfYear()
        val message = ReminderScheduler.yearEndMessage(year)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_year_progress", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 2_100_011, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$year is complete")
            .setContentText("$total / $total days")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$total / $total days\n$message\nA new year begins tomorrow."))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(2_100_001, notification)
        }
    }

    private fun postNewYearNotification(context: Context) {
        val year = LocalDate.now().year
        val total = LocalDate.of(year, 1, 1).lengthOfYear()
        val message = ReminderScheduler.newYearMessage(year)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_year_progress", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 2_100_012, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Welcome, $year")
            .setContentText("Day 1 / $total")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Day 1 / $total\n$message"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(2_100_002, notification)
        }
    }
}

internal class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ReminderScheduler.rescheduleAll(context)
                ReminderScheduler.scheduleYearProgressNotifications(context)
                DateReminderWidget.ensureRefreshSchedule(context)
                DateReminderWidget.updateAll(context)
            }
        }
    }
}

internal fun newReminderId(): String = UUID.randomUUID().toString()
