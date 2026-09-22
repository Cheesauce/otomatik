package com.revlv.conduit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Something that happens on the device and starts a [Flow]. */
@Serializable
sealed class Trigger {

    /** Fired by hand: the flow list, a home-screen shortcut, or a quick-settings tile. */
    @Serializable
    @SerialName("manual")
    data object Manual : Trigger()

    /**
     * A wall-clock time on the given days.
     * Uses an exact alarm, so the app needs SCHEDULE_EXACT_ALARM on Android 12+.
     */
    @Serializable
    @SerialName("time")
    data class Time(
        /** 24-hour "HH:mm". */
        val at: String,
        /** Empty means every day. */
        val days: Set<Weekday> = emptySet(),
    ) : Trigger()

    /** Repeats forever while the flow is enabled. Doze may stretch long intervals. */
    @Serializable
    @SerialName("interval")
    data class Interval(val everyMinutes: Int) : Trigger()

    /** A notification was posted. Null filters mean "any". */
    @Serializable
    @SerialName("notification")
    data class Notification(
        val packages: Set<String> = emptySet(),
        val titleContains: String? = null,
        val textContains: String? = null,
        /** Ignore ongoing notifications such as music players and download bars. */
        val ignoreOngoing: Boolean = true,
    ) : Trigger()

    /** An app came to the foreground. Requires the accessibility service. */
    @Serializable
    @SerialName("appOpened")
    data class AppOpened(val packageName: String) : Trigger()

    /** An app left the foreground. Requires the accessibility service. */
    @Serializable
    @SerialName("appClosed")
    data class AppClosed(val packageName: String) : Trigger()

    /** Entering or leaving a circular region. Requires background location permission. */
    @Serializable
    @SerialName("geofence")
    data class Geofence(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float = 150f,
        val on: GeofenceEdge = GeofenceEdge.ENTER,
        /** Label shown in logs; not used for matching. */
        val label: String = "",
    ) : Trigger()

    @Serializable
    @SerialName("boot")
    data object BootCompleted : Trigger()

    @Serializable
    @SerialName("power")
    data class Power(val on: PowerEdge) : Trigger()

    @Serializable
    @SerialName("screen")
    data class Screen(val on: ScreenEdge) : Trigger()

    /** Fired by another flow via [Action.RunFlow], or by an external intent. */
    @Serializable
    @SerialName("broadcast")
    data class Broadcast(val name: String) : Trigger()
}

@Serializable
enum class Weekday { MON, TUE, WED, THU, FRI, SAT, SUN }

@Serializable
enum class GeofenceEdge { ENTER, EXIT, DWELL }

@Serializable
enum class PowerEdge { CONNECTED, DISCONNECTED }

@Serializable
enum class ScreenEdge { ON, OFF, UNLOCKED }
