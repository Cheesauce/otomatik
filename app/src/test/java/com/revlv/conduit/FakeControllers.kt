package com.revlv.conduit

import com.revlv.conduit.core.engine.DeviceController
import com.revlv.conduit.core.engine.UiController
import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.GlobalActionKind
import com.revlv.conduit.core.model.MediaCommand
import com.revlv.conduit.core.model.RingerMode
import com.revlv.conduit.core.model.ScrollDirection
import com.revlv.conduit.core.model.Selector
import com.revlv.conduit.core.model.SettingsPanel
import com.revlv.conduit.core.model.VolumeStream

/**
 * A screen that is just a list of labels.
 *
 * Enough to exercise every branch of the engine's control flow without an
 * emulator, which is the whole reason UiController is an interface.
 */
class FakeUi(
    var onScreen: MutableSet<String> = mutableSetOf(),
    override var isReady: Boolean = true,
) : UiController {

    val clicks = mutableListOf<String>()
    val typed = mutableListOf<Pair<String, String>>()
    var scrolls = 0

    /** Labels to remove from the screen after the Nth click, keyed by click count. */
    var removeAfterClick: MutableMap<Int, String> = mutableMapOf()

    private fun key(selector: Selector): String =
        selector.text ?: selector.textContains ?: selector.viewId ?: "?"

    override fun foregroundPackage(): String? = "com.example.app"

    override fun exists(selector: Selector): Boolean = key(selector) in onScreen

    override suspend fun waitFor(selector: Selector, timeoutMs: Long): Boolean = exists(selector)

    override suspend fun waitUntilGone(selector: Selector, timeoutMs: Long): Boolean =
        !exists(selector)

    override fun click(selector: Selector, climbToClickable: Boolean): Boolean {
        val label = key(selector)
        if (label !in onScreen) return false
        clicks += label
        removeAfterClick[clicks.size]?.let { onScreen.remove(it) }
        return true
    }

    override fun longClick(selector: Selector, climbToClickable: Boolean): Boolean =
        click(selector, climbToClickable)

    override suspend fun tapXY(x: Int, y: Int): Boolean = true

    override fun setText(selector: Selector, text: String, append: Boolean): Boolean {
        typed += key(selector) to text
        return true
    }

    override fun scroll(direction: ScrollDirection, selector: Selector?): Boolean {
        scrolls++
        return true
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = true

    override fun readText(selector: Selector): String? =
        key(selector).takeIf { it in onScreen }

    override fun performGlobal(action: GlobalActionKind): Boolean = true
}

class FakeDevice(
    var battery: Int = 80,
    var charging: Boolean = false,
    var screenOn: Boolean = true,
    var installed: Set<String> = setOf("com.example.app"),
) : DeviceController {

    val launched = mutableListOf<String>()
    val notifications = mutableListOf<Pair<String, String>>()
    val logs = mutableListOf<String>()

    override fun launchApp(packageName: String, activity: String?): Boolean {
        launched += packageName
        return packageName in installed
    }

    override fun openUrl(url: String): Boolean = true
    override fun sendIntent(action: Action.SendIntent): Boolean = true
    override fun shareText(text: String, packageName: String?): Boolean = true
    override fun isAppInstalled(packageName: String): Boolean = packageName in installed
    override fun batteryPercent(): Int = battery
    override fun isCharging(): Boolean = charging
    override fun isScreenOn(): Boolean = screenOn
    override fun isNetworkConnected(): Boolean = true
    override fun setRingerMode(mode: RingerMode): Boolean = true
    override fun setDnd(enabled: Boolean): Boolean = true
    override fun setVolume(stream: VolumeStream, percent: Int): Boolean = true
    override fun setBrightness(percent: Int, auto: Boolean): Boolean = true
    override fun mediaControl(command: MediaCommand): Boolean = true
    override fun openSettingsPanel(panel: SettingsPanel): Boolean = true

    override fun notify(id: String, title: String, text: String) {
        notifications += title to text
    }

    override fun toast(text: String) {
        logs += text
    }

    override fun vibrate(durationMs: Long) = Unit
}
