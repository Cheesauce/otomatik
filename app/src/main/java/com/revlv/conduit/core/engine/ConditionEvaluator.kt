package com.revlv.conduit.core.engine

import com.revlv.conduit.core.model.Comparison
import com.revlv.conduit.core.model.Condition
import com.revlv.conduit.core.model.Weekday
import java.util.Calendar

/** Resolves [Condition]s against live device state and the flow's variables. */
class ConditionEvaluator(
    private val ui: UiController,
    private val device: DeviceController,
    private val clock: () -> Calendar = { Calendar.getInstance() },
) {

    fun evaluate(condition: Condition, vars: VariableStore): Boolean = when (condition) {
        is Condition.Battery -> compareNumbers(
            device.batteryPercent().toLong(),
            condition.percent.toLong(),
            condition.op,
        )

        is Condition.Charging -> device.isCharging() == condition.value

        is Condition.TimeRange -> inTimeRange(condition.from, condition.to)

        is Condition.OnWeekday -> currentWeekday() in condition.days

        is Condition.ForegroundApp ->
            ui.foregroundPackage() == vars.interpolate(condition.packageName)

        is Condition.OnScreen -> ui.exists(condition.selector.interpolate(vars))

        is Condition.ScreenOn -> device.isScreenOn() == condition.value

        is Condition.NetworkConnected -> device.isNetworkConnected() == condition.value

        is Condition.AppInstalled ->
            device.isAppInstalled(vars.interpolate(condition.packageName))

        is Condition.Variable -> {
            val left = vars[condition.name] ?: ""
            val right = vars.interpolate(condition.value)
            compareValues(left, right, condition.op)
        }

        is Condition.Not -> !evaluate(condition.condition, vars)
        is Condition.AllOf -> condition.conditions.all { evaluate(it, vars) }
        is Condition.AnyOf -> condition.conditions.any { evaluate(it, vars) }
    }

    /** A short reason string, so a skipped run explains itself in the log. */
    fun describe(condition: Condition): String = when (condition) {
        is Condition.Battery -> "battery ${condition.op.symbol} ${condition.percent}%"
        is Condition.Charging -> if (condition.value) "charging" else "not charging"
        is Condition.TimeRange -> "time in ${condition.from}–${condition.to}"
        is Condition.OnWeekday -> "weekday in ${condition.days.joinToString("/")}"
        is Condition.ForegroundApp -> "foreground app is ${condition.packageName}"
        is Condition.OnScreen -> "on screen: ${condition.selector.describe()}"
        is Condition.ScreenOn -> if (condition.value) "screen on" else "screen off"
        is Condition.NetworkConnected -> if (condition.value) "network up" else "network down"
        is Condition.AppInstalled -> "${condition.packageName} installed"
        is Condition.Variable -> "${condition.name} ${condition.op.symbol} ${condition.value}"
        is Condition.Not -> "not (${describe(condition.condition)})"
        is Condition.AllOf -> condition.conditions.joinToString(" and ") { describe(it) }
        is Condition.AnyOf -> condition.conditions.joinToString(" or ") { describe(it) }
    }

    private fun compareValues(left: String, right: String, op: Comparison): Boolean {
        // Compare numerically when both sides look like numbers, so "10" > "9"
        // behaves the way anyone writing a flow would expect.
        val l = left.trim().toLongOrNull()
        val r = right.trim().toLongOrNull()
        if (l != null && r != null && op.isOrdering) return compareNumbers(l, r, op)
        return when (op) {
            Comparison.EQ -> left == right
            Comparison.NEQ -> left != right
            Comparison.CONTAINS -> left.contains(right, ignoreCase = true)
            Comparison.MATCHES -> runCatching { Regex(right).containsMatchIn(left) }.getOrDefault(false)
            Comparison.LT -> left < right
            Comparison.LTE -> left <= right
            Comparison.GT -> left > right
            Comparison.GTE -> left >= right
        }
    }

    private fun compareNumbers(left: Long, right: Long, op: Comparison): Boolean = when (op) {
        Comparison.EQ -> left == right
        Comparison.NEQ -> left != right
        Comparison.LT -> left < right
        Comparison.LTE -> left <= right
        Comparison.GT -> left > right
        Comparison.GTE -> left >= right
        Comparison.CONTAINS -> left.toString().contains(right.toString())
        Comparison.MATCHES -> left == right
    }

    /** Handles windows that wrap past midnight, e.g. 22:00–06:00. */
    private fun inTimeRange(from: String, to: String): Boolean {
        val now = clock()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = parseMinutes(from) ?: return false
        val end = parseMinutes(to) ?: return false
        return if (start <= end) minutes in start..end else minutes >= start || minutes <= end
    }

    private fun currentWeekday(): Weekday = when (clock().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> Weekday.MON
        Calendar.TUESDAY -> Weekday.TUE
        Calendar.WEDNESDAY -> Weekday.WED
        Calendar.THURSDAY -> Weekday.THU
        Calendar.FRIDAY -> Weekday.FRI
        Calendar.SATURDAY -> Weekday.SAT
        else -> Weekday.SUN
    }

    companion object {
        fun parseMinutes(value: String): Int? {
            val parts = value.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

private val Comparison.isOrdering: Boolean
    get() = this in setOf(
        Comparison.LT, Comparison.LTE, Comparison.GT, Comparison.GTE,
        Comparison.EQ, Comparison.NEQ,
    )

val Comparison.symbol: String
    get() = when (this) {
        Comparison.EQ -> "=="
        Comparison.NEQ -> "!="
        Comparison.LT -> "<"
        Comparison.LTE -> "<="
        Comparison.GT -> ">"
        Comparison.GTE -> ">="
        Comparison.CONTAINS -> "contains"
        Comparison.MATCHES -> "matches"
    }
