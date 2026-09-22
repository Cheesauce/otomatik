package com.revlv.conduit.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One step in a flow.
 *
 * Every string field supports `{{variable}}` interpolation, resolved against the
 * flow's variable store just before the action runs.
 *
 * Actions marked UI need the accessibility service to be enabled; actions marked
 * SETTINGS need the WRITE_SETTINGS or notification-policy grant. Nothing here
 * requires root — capabilities that are impossible without it (toggling WiFi,
 * mobile data, Bluetooth, airplane mode) are deliberately absent; use
 * [OpenSettingsPanel] to put the user one tap away instead of failing silently.
 */
@Serializable
sealed class Action {

    // ---------------------------------------------------------------- launching

    /** Brings an app to the foreground via its launcher intent. */
    @Serializable
    @SerialName("launchApp")
    data class LaunchApp(
        val packageName: String,
        /** Optional explicit activity, for apps with several entry points. */
        val activity: String? = null,
    ) : Action()

    /** Opens a URL or deep link. Far more reliable than driving the UI by hand. */
    @Serializable
    @SerialName("openUrl")
    data class OpenUrl(val url: String) : Action()

    /** Fires an arbitrary intent — the escape hatch for app-specific entry points. */
    @Serializable
    @SerialName("sendIntent")
    data class SendIntent(
        val action: String,
        val data: String? = null,
        val packageName: String? = null,
        val component: String? = null,
        val extras: Map<String, String> = emptyMap(),
        val kind: IntentKind = IntentKind.ACTIVITY,
    ) : Action()

    /** Android's share sheet, or a direct share to one package. */
    @Serializable
    @SerialName("share")
    data class ShareText(val text: String, val packageName: String? = null) : Action()

    // ------------------------------------------------------------------- UI: tap

    /** UI. Taps the first node matching the selector. */
    @Serializable
    @SerialName("click")
    data class Click(
        val selector: Selector,
        /**
         * When the node itself is not clickable, walk up to the nearest clickable
         * ancestor. Most text labels inside buttons need this.
         */
        val climbToClickable: Boolean = true,
    ) : Action()

    /** UI. Long-presses a matching node. */
    @Serializable
    @SerialName("longClick")
    data class LongClick(val selector: Selector, val climbToClickable: Boolean = true) : Action()

    /** UI. Taps raw screen coordinates. Brittle across screen sizes — prefer [Click]. */
    @Serializable
    @SerialName("tapXY")
    data class TapXY(val x: Int, val y: Int) : Action()

    /** UI. Types into a field, replacing or appending to its current contents. */
    @Serializable
    @SerialName("setText")
    data class SetText(
        val selector: Selector,
        val text: String,
        val append: Boolean = false,
    ) : Action()

    /** UI. Scrolls a scrollable container, or the first one found. */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: ScrollDirection,
        val selector: Selector? = null,
        val times: Int = 1,
    ) : Action()

    /** UI. A straight-line swipe in screen coordinates. */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Long = 300,
    ) : Action()

    /** UI. Back, Home, Recents, notification shade, quick settings, lock screen. */
    @Serializable
    @SerialName("global")
    data class Global(val action: GlobalActionKind) : Action()

    /**
     * UI. Blocks until a matching node appears, or the timeout expires.
     * The single most important action for reliability: without it, every step
     * races the app's own rendering.
     */
    @Serializable
    @SerialName("waitFor")
    data class WaitFor(
        val selector: Selector,
        val timeoutMs: Long = 10_000,
        /** When false, a timeout aborts the flow instead of continuing. */
        val optional: Boolean = false,
    ) : Action()

    /** UI. Blocks until no node matches — useful for waiting out loading spinners. */
    @Serializable
    @SerialName("waitGone")
    data class WaitUntilGone(
        val selector: Selector,
        val timeoutMs: Long = 10_000,
        val optional: Boolean = false,
    ) : Action()

    /** UI. Copies a matching node's text into a variable for later steps. */
    @Serializable
    @SerialName("readText")
    data class ReadText(
        val selector: Selector,
        val into: String,
        val default: String = "",
    ) : Action()

    // -------------------------------------------------------------- device state

    /** SETTINGS. Silent / vibrate / normal. Silent needs notification-policy access. */
    @Serializable
    @SerialName("ringerMode")
    data class SetRingerMode(val mode: RingerMode) : Action()

    /** SETTINGS. Do Not Disturb. Needs notification-policy access. */
    @Serializable
    @SerialName("dnd")
    data class SetDnd(val enabled: Boolean) : Action()

    /** Volume for one stream, 0–100. Media and alarm work without special grants. */
    @Serializable
    @SerialName("volume")
    data class SetVolume(val stream: VolumeStream, val percent: Int) : Action()

    /** SETTINGS. Screen brightness 0–100. Needs WRITE_SETTINGS. */
    @Serializable
    @SerialName("brightness")
    data class SetBrightness(val percent: Int, val auto: Boolean = false) : Action()

    /** Play / pause / next / previous, delivered to whichever app holds media focus. */
    @Serializable
    @SerialName("media")
    data class MediaControl(val command: MediaCommand) : Action()

    /**
     * Opens a system settings screen. The supported fallback for anything a
     * non-root app cannot change directly, such as WiFi or airplane mode.
     */
    @Serializable
    @SerialName("openSettings")
    data class OpenSettingsPanel(val panel: SettingsPanel) : Action()

    // ----------------------------------------------------------------- feedback

    @Serializable
    @SerialName("notify")
    data class Notify(
        val title: String,
        val text: String = "",
        /** Reusing an id replaces the previous notification instead of stacking. */
        val id: String = "default",
    ) : Action()

    @Serializable
    @SerialName("toast")
    data class Toast(val text: String) : Action()

    @Serializable
    @SerialName("vibrate")
    data class Vibrate(val durationMs: Long = 200) : Action()

    /** Writes a line into the flow's run log. The debugging workhorse. */
    @Serializable
    @SerialName("log")
    data class Log(val message: String) : Action()

    // -------------------------------------------------------------- control flow

    @Serializable
    @SerialName("delay")
    data class Delay(val ms: Long) : Action()

    /** Runs the body a fixed number of times. `{{index}}` holds the 0-based counter. */
    @Serializable
    @SerialName("repeat")
    data class Repeat(
        val times: Int,
        val actions: List<Action>,
        /** Variable receiving the iteration counter. */
        val indexVar: String = "index",
    ) : Action()

    /** Runs the body while a condition holds, up to [maxIterations] as a safety stop. */
    @Serializable
    @SerialName("while")
    data class While(
        val condition: Condition,
        val actions: List<Action>,
        val maxIterations: Int = 500,
        val indexVar: String = "index",
    ) : Action()

    @Serializable
    @SerialName("if")
    data class If(
        val condition: Condition,
        val then: List<Action>,
        val otherwise: List<Action> = emptyList(),
    ) : Action()

    /** Leaves the innermost [Repeat] or [While]. */
    @Serializable
    @SerialName("break")
    data object Break : Action()

    /** Skips to the next iteration of the innermost loop. */
    @Serializable
    @SerialName("continue")
    data object Continue : Action()

    /** Ends the whole flow successfully. */
    @Serializable
    @SerialName("stop")
    data class Stop(val message: String = "") : Action()

    /**
     * Runs the body and swallows any failure, so one flaky step cannot kill a
     * long batch job. The error lands in the run log either way.
     */
    @Serializable
    @SerialName("try")
    data class Try(
        val actions: List<Action>,
        val onError: List<Action> = emptyList(),
    ) : Action()

    /** Re-runs the body until it succeeds, with a pause between attempts. */
    @Serializable
    @SerialName("retry")
    data class Retry(
        val actions: List<Action>,
        val attempts: Int = 3,
        val delayMs: Long = 1_000,
    ) : Action()

    @Serializable
    @SerialName("setVar")
    data class SetVariable(val name: String, val value: String) : Action()

    /** Evaluates a small integer expression, e.g. "{{count}} + 1". */
    @Serializable
    @SerialName("math")
    data class Math(val name: String, val expression: String) : Action()

    /** Runs another flow inline and waits for it, sharing the variable store. */
    @Serializable
    @SerialName("runFlow")
    data class RunFlow(val flowId: String, val await: Boolean = true) : Action()
}

@Serializable
enum class IntentKind { ACTIVITY, BROADCAST, SERVICE }

@Serializable
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT, FORWARD, BACKWARD }

@Serializable
enum class GlobalActionKind {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, POWER_DIALOG, SPLIT_SCREEN
}

@Serializable
enum class RingerMode { SILENT, VIBRATE, NORMAL }

@Serializable
enum class VolumeStream { MEDIA, RING, ALARM, NOTIFICATION, CALL }

@Serializable
enum class MediaCommand { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP }

@Serializable
enum class SettingsPanel {
    WIFI, BLUETOOTH, MOBILE_DATA, AIRPLANE_MODE, LOCATION, NFC, BATTERY_SAVER,
    DISPLAY, SOUND, APP_DETAILS, ACCESSIBILITY, NOTIFICATION_ACCESS, ALL_SETTINGS
}
