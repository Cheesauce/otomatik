package com.revlv.conduit.core.store

import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.Comparison
import com.revlv.conduit.core.model.Condition
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.core.model.GlobalActionKind
import com.revlv.conduit.core.model.RingerMode
import com.revlv.conduit.core.model.ScrollDirection
import com.revlv.conduit.core.model.Selector
import com.revlv.conduit.core.model.SettingsPanel
import com.revlv.conduit.core.model.Trigger
import com.revlv.conduit.core.model.Weekday

/**
 * Worked examples, seeded on first launch.
 *
 * These are the format's documentation: each one shows a different shape of
 * automation, and all of them are meant to be copied and edited rather than
 * run as-is — the selectors reference apps that may not be installed.
 */
object SampleFlows {

    fun all(): List<Flow> = listOf(
        smokeTest(),
        accessibilitySmokeTest(),
        nightMode(),
        notificationLogger(),
        openAndSearch(),
        bulkScroll(),
    )


    /**
     * First thing to run after installing. Touches nothing that needs a
     * permission grant, so if this fails the problem is the app itself rather
     * than a missing capability.
     */
    private fun smokeTest() = Flow(
        id = "smoke-1-engine",
        name = "Smoke test 1 — engine",
        description = "Verifies the engine runs, loops, and does arithmetic. " +
            "Needs no permissions. Run it, then check the Runs tab.",
        enabled = false,
        tags = listOf("test"),
        triggers = listOf(Trigger.Manual),
        variables = mapOf("total" to "0"),
        actions = listOf(
            Action.Toast("Conduit: engine test started"),
            Action.Log("Engine reached step 1 at {{now.seconds}}"),
            Action.Repeat(
                times = 3,
                actions = listOf(
                    Action.Math(name = "total", expression = "{{total}} + 5"),
                    Action.Log("Iteration {{index}} — total is now {{total}}"),
                    Action.Delay(ms = 200),
                ),
            ),
            Action.If(
                condition = Condition.Variable("total", Comparison.EQ, "15"),
                then = listOf(Action.Log("Arithmetic correct: total = {{total}}")),
                otherwise = listOf(Action.Log("BUG: expected 15, got {{total}}")),
            ),
            Action.Vibrate(durationMs = 250),
            Action.Notify(
                title = "Engine test passed",
                text = "Looped 3×, total {{total}}, finished {{now.seconds}}",
                id = "smoke-1",
            ),
        ),
    )

    /**
     * Second test: proves the accessibility service can actually see and drive
     * another app. Targets the system Settings app because it is present on
     * every device and its UI is stable.
     */
    private fun accessibilitySmokeTest() = Flow(
        id = "smoke-2-accessibility",
        name = "Smoke test 2 — accessibility",
        description = "Opens Settings, waits for it, reads the screen, and " +
            "presses Back. Fails clearly if the accessibility service is off.",
        enabled = false,
        tags = listOf("test"),
        timeoutSeconds = 60,
        triggers = listOf(Trigger.Manual),
        actions = listOf(
            Action.Toast("Conduit: accessibility test started"),
            Action.OpenSettingsPanel(SettingsPanel.ALL_SETTINGS),
            // Any settings screen has a scrollable list; if this times out the
            // service is not connected or cannot read window content.
            Action.WaitFor(
                selector = Selector(scrollable = true),
                timeoutMs = 8_000,
            ),
            Action.Log("Foreground app is readable — service is working"),
            Action.Scroll(direction = ScrollDirection.DOWN, times = 2),
            Action.Delay(ms = 500),
            Action.Scroll(direction = ScrollDirection.UP, times = 2),
            Action.Global(GlobalActionKind.BACK),
            Action.Notify(
                title = "Accessibility test passed",
                text = "Opened Settings, scrolled it, and returned.",
                id = "smoke-2",
            ),
        ),
    )

    /**
     * Time-driven routine. Touches nothing that needs the accessibility
     * service, so it works the moment the app is installed.
     */
    private fun nightMode() = Flow(
        id = "sample-night-mode",
        name = "Night mode",
        description = "At 22:00 on weeknights, silence the phone and dim the screen.",
        enabled = false,
        tags = listOf("routine"),
        triggers = listOf(
            Trigger.Time(
                at = "22:00",
                days = setOf(Weekday.MON, Weekday.TUE, Weekday.WED, Weekday.THU, Weekday.SUN),
            ),
        ),
        actions = listOf(
            Action.SetRingerMode(RingerMode.VIBRATE),
            Action.SetDnd(enabled = true),
            Action.SetBrightness(percent = 15),
            Action.Notify(title = "Night mode", text = "Silenced until morning", id = "night"),
        ),
    )

    /**
     * Notification-driven reaction, with a condition gate so it stays quiet
     * during the working day.
     */
    private fun notificationLogger() = Flow(
        id = "sample-notification-watch",
        name = "Flag delivery notifications",
        description = "When a delivery app notifies outside work hours, buzz and log it.",
        enabled = false,
        tags = listOf("notifications"),
        triggers = listOf(
            Trigger.Notification(
                packages = setOf("com.shopee.ph", "com.lazada.android"),
                ignoreOngoing = true,
            ),
        ),
        conditions = listOf(
            Condition.Not(Condition.TimeRange(from = "09:00", to = "18:00")),
        ),
        actions = listOf(
            Action.Vibrate(durationMs = 400),
            Action.Log("Delivery notification at {{now}}"),
            Action.Notify(
                title = "Delivery update",
                text = "Arrived at {{now}} on {{date}}",
                id = "delivery",
            ),
        ),
    )

    /**
     * Cross-app tap-and-type sequence — the general case, and the one that
     * needs the accessibility service.
     *
     * Note the WaitFor before every interaction: without it each step races the
     * app's own rendering, which is the usual reason a recorded macro works
     * once and then fails.
     */
    private fun openAndSearch() = Flow(
        id = "sample-open-and-search",
        name = "Search in an app",
        description = "Open an app, wait for its search box, type a query, submit.",
        enabled = false,
        tags = listOf("ui"),
        variables = mapOf("query" to "hello world"),
        triggers = listOf(Trigger.Manual),
        actions = listOf(
            Action.LaunchApp(packageName = "com.android.chrome"),
            Action.WaitFor(
                selector = Selector(viewId = "com.android.chrome:id/search_box_text"),
                timeoutMs = 8_000,
            ),
            Action.Click(selector = Selector(viewId = "com.android.chrome:id/search_box_text")),
            Action.SetText(
                selector = Selector(editable = true),
                text = "{{query}}",
            ),
            Action.Delay(ms = 300),
            Action.Global(GlobalActionKind.BACK),
            Action.Log("Searched for \"{{query}}\""),
        ),
    )

    /**
     * Bulk repetition with failure recovery — the shape to copy for any
     * "do this N times" job.
     *
     * Try/Retry matter here: over hundreds of iterations something will
     * eventually not be on screen when expected, and the default behaviour of
     * aborting the whole run would throw away all the completed work.
     */
    private fun bulkScroll() = Flow(
        id = "sample-bulk-repeat",
        name = "Bulk repeat with recovery",
        description = "Scroll a list 50 times, acting on each page, tolerating misses.",
        enabled = false,
        tags = listOf("bulk"),
        timeoutSeconds = 900,
        variables = mapOf("handled" to "0"),
        triggers = listOf(Trigger.Manual),
        actions = listOf(
            Action.Log("Starting batch"),
            Action.Repeat(
                times = 50,
                actions = listOf(
                    Action.Try(
                        actions = listOf(
                            Action.WaitFor(
                                selector = Selector(textContains = "Load more"),
                                timeoutMs = 3_000,
                            ),
                            Action.Click(selector = Selector(textContains = "Load more")),
                            Action.Math(name = "handled", expression = "{{handled}} + 1"),
                        ),
                        onError = listOf(
                            Action.Log("Nothing to click on page {{index}} — scrolling on"),
                        ),
                    ),
                    Action.Scroll(direction = ScrollDirection.DOWN),
                    Action.Delay(ms = 400),
                ),
            ),
            Action.If(
                condition = Condition.Variable(
                    name = "handled",
                    op = Comparison.GT,
                    value = "0",
                ),
                then = listOf(
                    Action.Notify(title = "Batch done", text = "Handled {{handled}} items"),
                ),
                otherwise = listOf(
                    Action.Notify(title = "Batch done", text = "Nothing matched"),
                ),
            ),
        ),
    )
}
