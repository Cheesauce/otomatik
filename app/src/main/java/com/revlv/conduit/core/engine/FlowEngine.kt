package com.revlv.conduit.core.engine

import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.Flow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Runs a [Flow]'s actions in order.
 *
 * The engine owns control flow, variables and logging; it owns nothing about
 * Android. Everything that touches the device goes through [UiController] and
 * [DeviceController], which keeps this class testable and keeps platform quirks
 * confined to the implementations.
 */
class FlowEngine(
    private val ui: UiController,
    private val device: DeviceController,
    private val conditions: ConditionEvaluator = ConditionEvaluator(ui, device),
    /** Resolves nested [Action.RunFlow] references. */
    private val flowLookup: suspend (String) -> Flow? = { null },
) {

    /** Non-local exits from a body of actions. */
    private sealed class Signal {
        data object None : Signal()
        data object Break : Signal()
        data object Continue : Signal()
        data class Stop(val message: String) : Signal()
    }

    private class RunState(flow: Flow) {
        val vars = VariableStore(flow.variables)
        val log = mutableListOf<LogEntry>()
        var depth = 0

        fun record(level: LogEntry.Level, message: String) {
            log += LogEntry(System.currentTimeMillis(), level, message, depth)
        }
    }

    suspend fun run(flow: Flow): RunResult {
        val started = System.currentTimeMillis()
        val state = RunState(flow)
        state.record(LogEntry.Level.INFO, "Starting \"${flow.name}\"")

        val outcome = try {
            val blocker = flow.conditions.firstOrNull { !conditions.evaluate(it, state.vars) }
            if (blocker != null) {
                state.record(
                    LogEntry.Level.INFO,
                    "Skipped: condition not met — ${conditions.describe(blocker)}",
                )
                RunOutcome.SkippedByConditions
            } else {
                val body: suspend () -> Signal = { execute(flow.actions, state) }
                val signal = if (flow.timeoutSeconds > 0) {
                    withTimeout(flow.timeoutSeconds * 1000L) { body() }
                } else {
                    body()
                }
                when (signal) {
                    is Signal.Stop -> {
                        if (signal.message.isNotEmpty()) {
                            state.record(LogEntry.Level.INFO, "Stopped: ${signal.message}")
                        }
                        RunOutcome.Stopped(signal.message)
                    }
                    else -> RunOutcome.Success
                }
            }
        } catch (e: TimeoutCancellationException) {
            state.record(LogEntry.Level.ERROR, "Timed out after ${flow.timeoutSeconds}s")
            RunOutcome.TimedOut
        } catch (e: ActionFailedException) {
            state.record(LogEntry.Level.ERROR, e.message ?: "Action failed")
            RunOutcome.Failed(e.message ?: "Action failed", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.record(LogEntry.Level.WARN, "Cancelled")
            RunOutcome.Cancelled
        } catch (e: Exception) {
            state.record(LogEntry.Level.ERROR, "Unexpected error: ${e.message}")
            RunOutcome.Failed(e.message ?: e::class.simpleName.orEmpty(), e)
        }

        return RunResult(
            flowId = flow.id,
            flowName = flow.name,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            outcome = outcome,
            log = state.log.toList(),
            variables = state.vars.snapshot(),
        )
    }

    private suspend fun execute(actions: List<Action>, state: RunState): Signal {
        for (action in actions) {
            coroutineContext.ensureActive()
            val signal = perform(action, state)
            if (signal != Signal.None) return signal
        }
        return Signal.None
    }

    private suspend fun perform(action: Action, state: RunState): Signal {
        val vars = state.vars

        when (action) {
            // ------------------------------------------------------ control flow
            is Action.Repeat -> {
                state.record(LogEntry.Level.STEP, "Repeat ${action.times}×")
                state.depth++
                try {
                    for (i in 0 until action.times) {
                        coroutineContext.ensureActive()
                        vars[action.indexVar] = i.toString()
                        when (val signal = execute(action.actions, state)) {
                            is Signal.Break -> break
                            is Signal.Stop -> return signal
                            else -> Unit
                        }
                    }
                } finally {
                    state.depth--
                }
                return Signal.None
            }

            is Action.While -> {
                state.record(LogEntry.Level.STEP, "While ${conditions.describe(action.condition)}")
                state.depth++
                try {
                    var i = 0
                    while (i < action.maxIterations && conditions.evaluate(action.condition, vars)) {
                        coroutineContext.ensureActive()
                        vars[action.indexVar] = i.toString()
                        when (val signal = execute(action.actions, state)) {
                            is Signal.Break -> break
                            is Signal.Stop -> return signal
                            else -> Unit
                        }
                        i++
                    }
                    if (i >= action.maxIterations) {
                        state.record(
                            LogEntry.Level.WARN,
                            "While hit its ${action.maxIterations}-iteration safety limit",
                        )
                    }
                } finally {
                    state.depth--
                }
                return Signal.None
            }

            is Action.If -> {
                val met = conditions.evaluate(action.condition, vars)
                state.record(
                    LogEntry.Level.STEP,
                    "If ${conditions.describe(action.condition)} → $met",
                )
                state.depth++
                try {
                    return execute(if (met) action.then else action.otherwise, state)
                } finally {
                    state.depth--
                }
            }

            is Action.Try -> {
                state.depth++
                try {
                    return execute(action.actions, state)
                } catch (e: ActionFailedException) {
                    state.record(LogEntry.Level.WARN, "Recovered from: ${e.message}")
                    vars["error"] = e.message ?: "failed"
                    return execute(action.onError, state)
                } finally {
                    state.depth--
                }
            }

            is Action.Retry -> {
                state.depth++
                try {
                    var lastError: ActionFailedException? = null
                    repeat(action.attempts) { attempt ->
                        try {
                            return execute(action.actions, state)
                        } catch (e: ActionFailedException) {
                            lastError = e
                            state.record(
                                LogEntry.Level.WARN,
                                "Attempt ${attempt + 1}/${action.attempts} failed: ${e.message}",
                            )
                            if (attempt < action.attempts - 1) delay(action.delayMs)
                        }
                    }
                    throw lastError ?: ActionFailedException("Retry exhausted")
                } finally {
                    state.depth--
                }
            }

            is Action.Break -> return Signal.Break
            is Action.Continue -> return Signal.Continue
            is Action.Stop -> return Signal.Stop(vars.interpolate(action.message))

            is Action.RunFlow -> {
                val nested = flowLookup(action.flowId)
                if (nested == null) {
                    fail(state, "No flow with id \"${action.flowId}\"")
                } else {
                    state.record(LogEntry.Level.STEP, "Run flow \"${nested.name}\"")
                    state.depth++
                    try {
                        // Shares the caller's variable store on purpose: nested
                        // flows are subroutines, not isolated runs.
                        when (val signal = execute(nested.actions, state)) {
                            is Signal.Stop -> return Signal.None // Stop is local to the nested flow.
                            else -> Unit
                        }
                    } finally {
                        state.depth--
                    }
                }
                return Signal.None
            }

            // --------------------------------------------------------- variables
            is Action.SetVariable -> {
                val value = vars.interpolate(action.value)
                vars[action.name] = value
                state.record(LogEntry.Level.STEP, "Set ${action.name} = \"$value\"")
                return Signal.None
            }

            is Action.Math -> {
                val expression = vars.interpolate(action.expression)
                val result = runCatching { MiniMath.eval(expression) }.getOrElse {
                    fail(state, "Bad expression \"$expression\": ${it.message}")
                    return Signal.None
                }
                vars[action.name] = result.toString()
                state.record(LogEntry.Level.STEP, "Math ${action.name} = $result")
                return Signal.None
            }

            is Action.Log -> {
                state.record(LogEntry.Level.INFO, vars.interpolate(action.message))
                return Signal.None
            }

            is Action.Delay -> {
                state.record(LogEntry.Level.STEP, "Wait ${action.ms}ms")
                delay(action.ms)
                return Signal.None
            }

            // ---------------------------------------------------------- UI steps
            is Action.WaitFor -> {
                val selector = action.selector.interpolate(vars)
                state.record(LogEntry.Level.STEP, "Wait for ${selector.describe()}")
                val found = ui.waitFor(selector, action.timeoutMs)
                if (!found && !action.optional) {
                    fail(state, "Timed out waiting for ${selector.describe()}")
                } else if (!found) {
                    state.record(LogEntry.Level.WARN, "Not found, continuing (optional)")
                }
                vars["found"] = found.toString()
                return Signal.None
            }

            is Action.WaitUntilGone -> {
                val selector = action.selector.interpolate(vars)
                state.record(LogEntry.Level.STEP, "Wait until gone: ${selector.describe()}")
                val gone = ui.waitUntilGone(selector, action.timeoutMs)
                if (!gone && !action.optional) {
                    fail(state, "Still on screen: ${selector.describe()}")
                }
                return Signal.None
            }

            is Action.Click -> {
                val selector = action.selector.interpolate(vars)
                state.record(LogEntry.Level.STEP, "Click ${selector.describe()}")
                requireUi(state)
                if (!ui.click(selector, action.climbToClickable)) {
                    fail(state, "Could not click ${selector.describe()}")
                }
                return Signal.None
            }

            is Action.LongClick -> {
                val selector = action.selector.interpolate(vars)
                state.record(LogEntry.Level.STEP, "Long-click ${selector.describe()}")
                requireUi(state)
                if (!ui.longClick(selector, action.climbToClickable)) {
                    fail(state, "Could not long-click ${selector.describe()}")
                }
                return Signal.None
            }

            is Action.TapXY -> {
                state.record(LogEntry.Level.STEP, "Tap (${action.x}, ${action.y})")
                requireUi(state)
                if (!ui.tapXY(action.x, action.y)) fail(state, "Tap gesture was not dispatched")
                return Signal.None
            }

            is Action.SetText -> {
                val selector = action.selector.interpolate(vars)
                val text = vars.interpolate(action.text)
                state.record(LogEntry.Level.STEP, "Type into ${selector.describe()}")
                requireUi(state)
                if (!ui.setText(selector, text, action.append)) {
                    fail(state, "Could not type into ${selector.describe()}")
                }
                return Signal.None
            }

            is Action.Scroll -> {
                val selector = action.selector?.interpolate(vars)
                state.record(LogEntry.Level.STEP, "Scroll ${action.direction} ×${action.times}")
                requireUi(state)
                repeat(action.times) {
                    if (!ui.scroll(action.direction, selector)) {
                        state.record(LogEntry.Level.WARN, "Nothing left to scroll")
                        return@repeat
                    }
                }
                return Signal.None
            }

            is Action.Swipe -> {
                state.record(LogEntry.Level.STEP, "Swipe")
                requireUi(state)
                if (!ui.swipe(action.fromX, action.fromY, action.toX, action.toY, action.durationMs)) {
                    fail(state, "Swipe gesture was not dispatched")
                }
                return Signal.None
            }

            is Action.Global -> {
                state.record(LogEntry.Level.STEP, "System ${action.action}")
                requireUi(state)
                if (!ui.performGlobal(action.action)) fail(state, "${action.action} was refused")
                return Signal.None
            }

            is Action.ReadText -> {
                val selector = action.selector.interpolate(vars)
                val text = ui.readText(selector) ?: action.default
                vars[action.into] = text
                state.record(LogEntry.Level.STEP, "Read ${action.into} = \"$text\"")
                return Signal.None
            }

            // ------------------------------------------------------ apps, device
            is Action.LaunchApp -> {
                val pkg = vars.interpolate(action.packageName)
                state.record(LogEntry.Level.STEP, "Launch $pkg")
                if (!device.launchApp(pkg, action.activity)) fail(state, "Could not launch $pkg")
                return Signal.None
            }

            is Action.OpenUrl -> {
                val url = vars.interpolate(action.url)
                state.record(LogEntry.Level.STEP, "Open $url")
                if (!device.openUrl(url)) fail(state, "Nothing could handle $url")
                return Signal.None
            }

            is Action.SendIntent -> {
                state.record(LogEntry.Level.STEP, "Intent ${action.action}")
                val resolved = action.copy(
                    data = action.data?.let(vars::interpolate),
                    extras = action.extras.mapValues { vars.interpolate(it.value) },
                )
                if (!device.sendIntent(resolved)) fail(state, "Intent was not delivered")
                return Signal.None
            }

            is Action.ShareText -> {
                state.record(LogEntry.Level.STEP, "Share text")
                if (!device.shareText(vars.interpolate(action.text), action.packageName)) {
                    fail(state, "Share target unavailable")
                }
                return Signal.None
            }

            is Action.SetRingerMode -> {
                state.record(LogEntry.Level.STEP, "Ringer → ${action.mode}")
                if (!device.setRingerMode(action.mode)) {
                    fail(state, "Ringer change refused — grant Do Not Disturb access")
                }
                return Signal.None
            }

            is Action.SetDnd -> {
                state.record(LogEntry.Level.STEP, "DND → ${action.enabled}")
                if (!device.setDnd(action.enabled)) {
                    fail(state, "DND change refused — grant Do Not Disturb access")
                }
                return Signal.None
            }

            is Action.SetVolume -> {
                state.record(LogEntry.Level.STEP, "${action.stream} volume → ${action.percent}%")
                if (!device.setVolume(action.stream, action.percent)) {
                    fail(state, "Volume change refused")
                }
                return Signal.None
            }

            is Action.SetBrightness -> {
                state.record(LogEntry.Level.STEP, "Brightness → ${action.percent}%")
                if (!device.setBrightness(action.percent, action.auto)) {
                    fail(state, "Brightness change refused — grant Modify system settings")
                }
                return Signal.None
            }

            is Action.MediaControl -> {
                state.record(LogEntry.Level.STEP, "Media ${action.command}")
                device.mediaControl(action.command)
                return Signal.None
            }

            is Action.OpenSettingsPanel -> {
                state.record(LogEntry.Level.STEP, "Open ${action.panel} settings")
                device.openSettingsPanel(action.panel)
                return Signal.None
            }

            is Action.Notify -> {
                device.notify(
                    action.id,
                    vars.interpolate(action.title),
                    vars.interpolate(action.text),
                )
                state.record(LogEntry.Level.STEP, "Notify")
                return Signal.None
            }

            is Action.Toast -> {
                device.toast(vars.interpolate(action.text))
                return Signal.None
            }

            is Action.Vibrate -> {
                device.vibrate(action.durationMs)
                return Signal.None
            }
        }
    }

    /** Every UI action funnels through here so the error names the real cause. */
    private fun requireUi(state: RunState) {
        if (!ui.isReady) {
            fail(state, "Accessibility service is not running — enable it in Settings")
        }
    }

    private fun fail(state: RunState, message: String): Nothing {
        state.record(LogEntry.Level.ERROR, message)
        throw ActionFailedException(message)
    }
}
