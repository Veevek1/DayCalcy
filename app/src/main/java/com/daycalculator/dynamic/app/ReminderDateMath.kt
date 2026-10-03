package com.daycalculator.dynamic.app

import java.time.LocalDate
import java.time.Period
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Shared reminder countdown logic used by the app and home-screen widget. */
internal object ReminderDateMath {
    fun totalDays(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /**
     * Calendar-style countdown shared by the app and widget. Remaining calendar days
     * are always shown directly; weeks are never used in reminder countdown text.
     */
    fun calendarCountdown(from: LocalDate, to: LocalDate): String {
        if (to.isEqual(from)) return "Today"

        val isPast = to.isBefore(from)
        val start = if (isPast) to else from
        val end = if (isPast) from else to
        val period = Period.between(start, end)
        val parts = mutableListOf<String>()
        if (period.years > 0) parts += "${period.years} ${plural(period.years.toLong(), "year")}"
        if (period.months > 0) parts += "${period.months} ${plural(period.months.toLong(), "month")}"
        // Never split the remaining days into weeks. A 2-week remainder is shown
        // as 14 days so the countdown is consistent across every widget size.
        if (period.days > 0) parts += "${period.days} ${plural(period.days.toLong(), "day")}"

        if (parts.isEmpty()) return "Today"
        return parts.joinToString(" ") + if (isPast) " ago" else ""
    }

    /**
     * Exact calendar-and-clock countdown. Non-zero years, months, days, hours,
     * and minutes are included in that order. The calendar portion is calculated
     * first and the remaining clock duration is then derived from that anchor,
     * avoiding off-by-one-day errors around the selected time.
     */
    fun preciseCountdownParts(from: LocalDateTime, to: LocalDateTime): List<String> {
        if (!to.isAfter(from)) return emptyList()

        // Build the calendar portion against the latest whole calendar date that
        // does not overshoot the target's clock time. Subtracting one day from an
        // already-zero day field (for example 20 Apr 06:26 -> 20 Jul 03:10) would
        // create a Period containing -1 day and the old abs() formatting could then
        // incorrectly display a positive day. Using target.date - 1 day when the
        // target clock is earlier keeps years/months/days non-negative and leaves
        // the remaining clock duration to hours/minutes.
        val sameDate = from.toLocalDate().isEqual(to.toLocalDate())
        val calendarEndDate = if (!sameDate && to.toLocalTime().isBefore(from.toLocalTime())) {
            to.toLocalDate().minusDays(1)
        } else {
            to.toLocalDate()
        }
        val period = if (sameDate) Period.ZERO else Period.between(from.toLocalDate(), calendarEndDate)
        val anchor = from.plus(period)

        val duration = Duration.between(anchor, to)
        val totalMinutes = duration.toMinutes().coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        val parts = mutableListOf<String>()
        if (period.years != 0) parts += "${period.years} ${plural(period.years.toLong(), "year")}"
        if (period.months != 0) parts += "${period.months} ${plural(period.months.toLong(), "month")}"
        if (period.days != 0) parts += "${period.days} ${plural(period.days.toLong(), "day")}"
        if (hours > 0) parts += "$hours ${plural(hours, "hour")}"
        if (minutes > 0) parts += "$minutes ${plural(minutes, "minute")}"
        return parts
    }


    /**
     * Zone-aware version used by reminders. LocalDateTime alone cannot account for
     * daylight-saving transitions, so the final clock duration is measured between
     * ZonedDateTime values while the calendar portion still follows local dates.
     */
    fun preciseCountdownParts(from: ZonedDateTime, to: ZonedDateTime): List<String> {
        if (!to.isAfter(from)) return emptyList()

        val sameDate = from.toLocalDate().isEqual(to.toLocalDate())
        val calendarEndDate = if (!sameDate && to.toLocalTime().isBefore(from.toLocalTime())) {
            to.toLocalDate().minusDays(1)
        } else {
            to.toLocalDate()
        }
        val period = if (sameDate) Period.ZERO else Period.between(from.toLocalDate(), calendarEndDate)
        val anchor = from.plus(period)
        val duration = Duration.between(anchor, to)
        val totalMinutes = duration.toMinutes().coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        val parts = mutableListOf<String>()
        if (period.years != 0) parts += "${period.years} ${plural(period.years.toLong(), "year")}"
        if (period.months != 0) parts += "${period.months} ${plural(period.months.toLong(), "month")}"
        if (period.days != 0) parts += "${period.days} ${plural(period.days.toLong(), "day")}"
        if (hours > 0) parts += "$hours ${plural(hours, "hour")}"
        if (minutes > 0) parts += "$minutes ${plural(minutes, "minute")}"
        return parts
    }

    fun preciseCountdown(from: ZonedDateTime, to: ZonedDateTime): String {
        if (!to.isAfter(from)) return "Due now"
        val parts = preciseCountdownParts(from, to)
        return if (parts.isEmpty()) "Due now" else parts.joinToString(" ") + " remaining"
    }

    fun preciseCountdown(from: LocalDateTime, to: LocalDateTime): String {
        if (!to.isAfter(from)) return "Due now"
        val parts = preciseCountdownParts(from, to)
        return if (parts.isEmpty()) "Due now" else parts.joinToString(" ") + " remaining"
    }

    private fun plural(value: Long, singular: String): String =
        if (value == 1L) singular else "${singular}s"
}
