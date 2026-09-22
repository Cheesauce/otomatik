package com.revlv.conduit.core.engine

import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.GlobalActionKind
import com.revlv.conduit.core.model.MediaCommand
import com.revlv.conduit.core.model.RingerMode
import com.revlv.conduit.core.model.ScrollDirection
import com.revlv.conduit.core.model.Selector
import com.revlv.conduit.core.model.SettingsPanel
import com.revlv.conduit.core.model.VolumeStream

/**
 * Everything the engine can do to the screen.
 *
 * Backed at run time by the accessibility service. Kept as an interface so the
 * control flow — loops, retries, conditions — can be exercised in unit tests
 * against a fake screen, with no device in the loop.
 */
interface UiController {

    /** True when the accessibility service is connected and able to act. */
    val isReady: Boolean

    /** Package of the app currently in the foreground, or null if unknown. */
    fun foregroundPackage(): String?

    /** True when at least one node matches right now, without waiting. */
    fun exists(selector: Selector): Boolean

    /** Waits for a match, returning false on timeout. */
    suspend fun waitFor(selector: Selector, timeoutMs: Long): Boolean

    /** Waits until nothing matches, returning false on timeout. */
    suspend fun waitUntilGone(selector: Selector, timeoutMs: Long): Boolean

    fun click(selector: Selector, climbToClickable: Boolean): Boolean

    fun longClick(selector: Selector, climbToClickable: Boolean): Boolean

    suspend fun tapXY(x: Int, y: Int): Boolean

    fun setText(selector: Selector, text: String, append: Boolean): Boolean

    fun scroll(direction: ScrollDirection, selector: Selector?): Boolean

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean

    fun readText(selector: Selector): String?

    fun performGlobal(action: GlobalActionKind): Boolean
}

/** Everything the engine can do to the device that is not the screen. */
interface DeviceController {

    fun launchApp(packageName: String, activity: String?): Boolean

    fun openUrl(url: String): Boolean

    fun sendIntent(action: Action.SendIntent): Boolean

    fun shareText(text: String, packageName: String?): Boolean

    fun isAppInstalled(packageName: String): Boolean

    fun batteryPercent(): Int

    fun isCharging(): Boolean

    fun isScreenOn(): Boolean

    fun isNetworkConnected(): Boolean

    fun setRingerMode(mode: RingerMode): Boolean

    fun setDnd(enabled: Boolean): Boolean

    fun setVolume(stream: VolumeStream, percent: Int): Boolean

    fun setBrightness(percent: Int, auto: Boolean): Boolean

    fun mediaControl(command: MediaCommand): Boolean

    fun openSettingsPanel(panel: SettingsPanel): Boolean

    fun notify(id: String, title: String, text: String)

    fun toast(text: String)

    fun vibrate(durationMs: Long)
}

/** One line in a run log. */
data class LogEntry(
    val timestamp: Long,
    val level: Level,
    val message: String,
    val depth: Int = 0,
) {
    enum class Level { INFO, STEP, WARN, ERROR }
}

/** Why a run ended. */
sealed class RunOutcome {
    data object Success : RunOutcome()
    data class Stopped(val message: String) : RunOutcome()
    data class Failed(val message: String, val cause: Throwable? = null) : RunOutcome()
    data object SkippedByConditions : RunOutcome()
    data object TimedOut : RunOutcome()
    data object Cancelled : RunOutcome()
}

/** The full record of one execution, surfaced in the UI and used for debugging. */
data class RunResult(
    val flowId: String,
    val flowName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: RunOutcome,
    val log: List<LogEntry>,
    val variables: Map<String, String>,
) {
    val durationMs: Long get() = finishedAt - startedAt
    val succeeded: Boolean
        get() = outcome is RunOutcome.Success || outcome is RunOutcome.Stopped

    /** Flat text form, for the share/export button on the run detail screen. */
    fun toText(): String = buildString {
        appendLine("Flow: $flowName ($flowId)")
        appendLine("Outcome: ${outcome::class.simpleName}")
        appendLine("Duration: ${durationMs}ms")
        appendLine()
        for (entry in log) {
            append("  ".repeat(entry.depth))
            append("[${entry.level}] ")
            appendLine(entry.message)
        }
    }
}

/** Raised when an action fails and the flow is not configured to tolerate it. */
class ActionFailedException(message: String) : Exception(message)
