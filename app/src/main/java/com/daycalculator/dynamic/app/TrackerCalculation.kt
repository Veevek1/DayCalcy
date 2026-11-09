package com.daycalculator.dynamic.app

import org.json.JSONObject
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Shared, side-effect-free calculation used by the Trackers list and its widget. */
internal data class TrackerSnapshot(
    val currentValue: Double,
    val unit: String,
    val targetValue: Double?,
    val startDate: LocalDate?,
    val targetDate: LocalDate?,
    val valueProgress: Float?,
    val timeProgress: Float?,
    val progress: Float?,
    val valueTargetReached: Boolean,
    val dateTargetReached: Boolean
) {
    fun formattedValue(): String = formatTrackerNumber(currentValue) + if (unit.isBlank()) "" else " $unit"
    fun targetLabel(): String? = targetValue?.let { "Target: ${formatTrackerNumber(it)}${if (unit.isBlank()) "" else " $unit"}" }
    fun statusLabel(): String? {
        val valueStatus = targetValue?.let {
            if (valueTargetReached) "Value target reached"
            else "Difference to target: ${formatTrackerNumber(kotlin.math.abs(it - currentValue))}${if (unit.isBlank()) "" else " $unit"}"
        }
        val dateStatus = targetDate?.let { if (dateTargetReached) "Target date reached" else "Target date: $it" }
        return listOfNotNull(valueStatus, dateStatus).joinToString(" · ").ifBlank { null }
    }
}

internal fun formatTrackerNumber(value: Double): String = DecimalFormat("#,##0.########").format(value)

internal fun calculateTrackerSnapshot(payload: String, today: LocalDate = LocalDate.now()): TrackerSnapshot {
    val p = runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
    val start = runCatching { LocalDate.parse(p.optString("startDate")) }.getOrNull()
    // Saved Tracker calculations are independent of the main calculator's targetDate.
    // Accumulate through today; the tracker's own optional date is used only for time progress.
    val savedGoalDate = runCatching { LocalDate.parse(p.optString("savedTrackerTargetDate")) }.getOrNull()
    val validDateRange = start != null && savedGoalDate != null && !savedGoalDate.isBefore(start)
    val initial = p.optString("startingValue", "0").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    val amount = p.optString("amount", "0").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    val add = p.optBoolean("add", true)
    val frequency = p.optString("frequency", "Daily")
    val intervalDays = when (frequency) {
        "Daily" -> 1L
        "Weekly" -> 7L
        "Monthly" -> 0L
        else -> p.optString("customDays", "1").toLongOrNull()?.coerceAtLeast(1L) ?: 1L
    }

    // A malformed saved range must never reach LocalDate.coerceIn (which throws
    // when the lower bound is after the upper bound). Treat it as not started.
    val bounded = when {
        start == null -> today
        today.isBefore(start) -> start
        else -> today
    }
    val occurrences = when {
        start == null || today.isBefore(start) -> 0L
        frequency == "Monthly" && bounded >= start -> {
            val wholeMonths = ChronoUnit.MONTHS.between(start, bounded)
            val baseCount = wholeMonths + 1L
            if (start.plusMonths(baseCount).isAfter(bounded)) baseCount else baseCount + 1L
        }
        bounded >= start -> ChronoUnit.DAYS.between(start, bounded).coerceAtLeast(0L) / intervalDays + 1L
        else -> 0L
    }.coerceAtMost(1_000_000L)

    val signedAmount = if (add) amount else -amount
    val projectedCurrent = (initial + signedAmount * occurrences.toDouble()).takeIf { it.isFinite() } ?: initial
    // Manual corrections are stored separately from the projection inputs so they
    // never overwrite the tracker's original schedule or starting value.
    val adjustmentTotal = runCatching {
        val adjustments = p.optJSONArray("adjustments") ?: org.json.JSONArray()
        (0 until adjustments.length()).sumOf { index ->
            adjustments.optJSONObject(index)?.optDouble("delta", 0.0)?.takeIf { it.isFinite() } ?: 0.0
        }
    }.getOrDefault(0.0)
    val current = (projectedCurrent + adjustmentTotal).takeIf { it.isFinite() } ?: projectedCurrent
    val target = if (p.isNull("trackerTargetValue")) null else p.optDouble("trackerTargetValue", Double.NaN).takeIf { it.isFinite() }
    val unit = p.optString("unit", "").trim()
    val valueProgress = target?.let { goal ->
        when {
            // Preserve the existing Add behavior: the starting balance already
            // counts toward a positive savings target (for example 100 / 500 = 20%).
            add && goal > initial && goal > 0.0 -> (current / goal).toFloat().coerceIn(0f, 1f)
            add && goal > initial -> ((current - initial) / (goal - initial)).toFloat().coerceIn(0f, 1f)
            // Adding toward a lower target, or subtracting toward a higher target,
            // moves away from the goal and must not show misleading progress.
            (add && goal < initial) || (!add && goal > initial) -> 0f
            initial == goal -> 1f
            !add && initial > goal -> ((initial - current) / (initial - goal)).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }
    }
    val goalDateBounded = if (validDateRange && start != null && savedGoalDate != null) {
        today.coerceIn(start, savedGoalDate)
    } else null
    val timeProgress = if (validDateRange && start != null && savedGoalDate != null && goalDateBounded != null) {
        if (start == savedGoalDate) {
            if (today.isBefore(start)) 0f else 1f
        } else {
            (ChronoUnit.DAYS.between(start, goalDateBounded).toDouble() /
                ChronoUnit.DAYS.between(start, savedGoalDate).toDouble()).toFloat().coerceIn(0f, 1f)
        }
    } else null
    val valueTargetReached = target?.let { goal ->
        when {
            goal > initial -> current >= goal
            goal < initial -> current <= goal
            else -> true
        }
    } ?: false
    return TrackerSnapshot(
        currentValue = current,
        unit = unit,
        targetValue = target,
        startDate = start,
        targetDate = savedGoalDate,
        valueProgress = valueProgress,
        timeProgress = timeProgress,
        progress = valueProgress ?: timeProgress,
        valueTargetReached = valueTargetReached,
        dateTargetReached = validDateRange && savedGoalDate != null && !today.isBefore(savedGoalDate)
    )
}
