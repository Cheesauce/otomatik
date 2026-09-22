package com.revlv.conduit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A test evaluated at run time. Conditions gate a whole flow (all must pass)
 * and also drive [Action.If] and [Action.While].
 */
@Serializable
sealed class Condition {

    @Serializable
    @SerialName("battery")
    data class Battery(val op: Comparison, val percent: Int) : Condition()

    @Serializable
    @SerialName("charging")
    data class Charging(val value: Boolean = true) : Condition()

    /**
     * Wall-clock window. Wraps midnight correctly, so 22:00–06:00 is one night.
     */
    @Serializable
    @SerialName("timeRange")
    data class TimeRange(val from: String, val to: String) : Condition()

    @Serializable
    @SerialName("weekday")
    data class OnWeekday(val days: Set<Weekday>) : Condition()

    /** The named package is the current foreground app. */
    @Serializable
    @SerialName("foregroundApp")
    data class ForegroundApp(val packageName: String) : Condition()

    /** A node matching the selector exists on screen right now. */
    @Serializable
    @SerialName("onScreen")
    data class OnScreen(val selector: Selector) : Condition()

    @Serializable
    @SerialName("screenOn")
    data class ScreenOn(val value: Boolean = true) : Condition()

    /** Any network is connected. Cannot distinguish metered state on all OEMs. */
    @Serializable
    @SerialName("connected")
    data class NetworkConnected(val value: Boolean = true) : Condition()

    /** Compares a flow variable against a literal. Both sides are interpolated. */
    @Serializable
    @SerialName("variable")
    data class Variable(
        val name: String,
        val op: Comparison = Comparison.EQ,
        val value: String,
    ) : Condition()

    @Serializable
    @SerialName("appInstalled")
    data class AppInstalled(val packageName: String) : Condition()

    @Serializable
    @SerialName("not")
    data class Not(val condition: Condition) : Condition()

    @Serializable
    @SerialName("allOf")
    data class AllOf(val conditions: List<Condition>) : Condition()

    @Serializable
    @SerialName("anyOf")
    data class AnyOf(val conditions: List<Condition>) : Condition()
}

@Serializable
enum class Comparison {
    @SerialName("==") EQ,
    @SerialName("!=") NEQ,
    @SerialName("<") LT,
    @SerialName("<=") LTE,
    @SerialName(">") GT,
    @SerialName(">=") GTE,
    @SerialName("contains") CONTAINS,
    @SerialName("matches") MATCHES,
}
