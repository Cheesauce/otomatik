package com.revlv.conduit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.revlv.conduit.core.engine.UiController
import com.revlv.conduit.core.model.GlobalActionKind
import com.revlv.conduit.core.model.ScrollDirection
import com.revlv.conduit.core.model.Selector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's eyes and hands.
 *
 * Android has no sanctioned API for one app to drive another, so this service —
 * the same mechanism screen readers use — is the only way to read the current
 * screen and act on it without root. The user must enable it by hand in
 * Settings, and the system can tear it down at any time, which is why every
 * call here is written to fail softly rather than throw.
 */
class ConduitAccessibilityService : AccessibilityService(), UiController {

    /** Foreground package, kept current from window-state events. */
    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> = _foreground.asStateFlow()

    /** App-switch events, consumed by the trigger registry. */
    private val _appSwitches = MutableSharedFlow<AppSwitch>(extraBufferCapacity = 32)
    val appSwitches: SharedFlow<AppSwitch> = _appSwitches.asSharedFlow()

    data class AppSwitch(val opened: String, val closed: String?)

    override val isReady: Boolean
        get() = instance != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore our own windows and the system UI, which would otherwise fire
        // a spurious "app closed" every time a notification slides in.
        if (pkg == packageName || pkg == "com.android.systemui") return
        val previous = _foreground.value
        if (previous == pkg) return
        _foreground.value = pkg
        _appSwitches.tryEmit(AppSwitch(opened = pkg, closed = previous))
    }

    // ------------------------------------------------------------ UiController

    override fun foregroundPackage(): String? = _foreground.value

    /**
     * The active window's root. Returns null while the screen is transitioning,
     * which is normal and is why callers poll rather than read once.
     */
    private fun root(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        null
    }

    override fun exists(selector: Selector): Boolean =
        NodeMatching.findOne(root(), selector) != null

    override suspend fun waitFor(selector: Selector, timeoutMs: Long): Boolean =
        pollUntil(timeoutMs) { exists(selector) }

    override suspend fun waitUntilGone(selector: Selector, timeoutMs: Long): Boolean =
        pollUntil(timeoutMs) { !exists(selector) }

    /**
     * Polls instead of listening for events: accessibility events are noisy,
     * coalesced by the system, and routinely missed during animations, so a
     * short fixed poll is both simpler and more reliable here.
     */
    private suspend fun pollUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!predicate()) delay(POLL_INTERVAL_MS)
            true
        } ?: false

    override fun click(selector: Selector, climbToClickable: Boolean): Boolean {
        val node = NodeMatching.findOne(root(), selector) ?: return false
        val target = if (node.isClickable && node.isEnabled) {
            node
        } else if (climbToClickable) {
            NodeMatching.clickableAncestor(node) ?: return false
        } else {
            return false
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun longClick(selector: Selector, climbToClickable: Boolean): Boolean {
        val node = NodeMatching.findOne(root(), selector) ?: return false
        val target = if (node.isLongClickable) {
            node
        } else if (climbToClickable) {
            NodeMatching.longClickableAncestor(node) ?: node
        } else {
            node
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    override suspend fun tapXY(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    override fun setText(selector: Selector, text: String, append: Boolean): Boolean {
        val node = NodeMatching.findOne(root(), selector) ?: return false
        val value = if (append) (node.text?.toString() ?: "") + text else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        // Focusing first makes the target app's own listeners fire; without it
        // some apps keep a stale internal value even though the field updates.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun scroll(direction: ScrollDirection, selector: Selector?): Boolean {
        val root = root() ?: return false
        val node = if (selector != null) {
            NodeMatching.findOne(root, selector)
        } else {
            NodeMatching.firstScrollable(root)
        } ?: return false
        val action = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.RIGHT, ScrollDirection.FORWARD ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP, ScrollDirection.LEFT, ScrollDirection.BACKWARD ->
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    override fun readText(selector: Selector): String? =
        NodeMatching.findOne(root(), selector)?.let(NodeMatching::textOf)

    override fun performGlobal(action: GlobalActionKind): Boolean {
        val id = when (action) {
            GlobalActionKind.BACK -> GLOBAL_ACTION_BACK
            GlobalActionKind.HOME -> GLOBAL_ACTION_HOME
            GlobalActionKind.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalActionKind.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalActionKind.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalActionKind.POWER_DIALOG -> GLOBAL_ACTION_POWER_DIALOG
            GlobalActionKind.SPLIT_SCREEN -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            GlobalActionKind.LOCK_SCREEN ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    return false // Not offered before Android 9.
                }
        }
        return performGlobalAction(id)
    }

    /** Bridges the callback-based gesture API into a suspending call. */
    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completion.complete(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    completion.complete(false)
                }
            },
            null,
        )
        if (!accepted) return false
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { completion.await() } ?: false
    }

    /** Flat dump of the current screen, backing the in-app screen inspector. */
    fun dumpScreen(): List<String> {
        val root = root() ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || out.size > 400) return
            out += "  ".repeat(depth) + NodeMatching.describe(node)
            for (i in 0 until node.childCount) {
                walk(node.getChild(i) ?: continue, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    companion object {
        private const val POLL_INTERVAL_MS = 120L
        private const val TAP_DURATION_MS = 60L
        private const val GESTURE_TIMEOUT_MS = 5_000L

        /**
         * The live service, or null when the user has not enabled it.
         * A plain static reference is correct here: the system owns the
         * lifecycle and guarantees at most one instance.
         */
        @Volatile
        var instance: ConduitAccessibilityService? = null
            private set
    }
}
