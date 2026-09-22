package com.revlv.conduit.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.PowerEdge
import com.revlv.conduit.core.model.ScreenEdge
import com.revlv.conduit.core.model.Trigger
import com.revlv.conduit.service.ConduitForegroundService

/**
 * Power, screen and boot events.
 *
 * Screen on/off cannot be declared in the manifest — Android only delivers them
 * to receivers registered at run time — so the foreground service registers
 * this one dynamically and the manifest declares only BOOT_COMPLETED.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val runtime = FlowRuntime.get(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Alarms do not survive a reboot or a reinstall, so rebook them
                // before anything else, then let boot-triggered flows run.
                ConduitForegroundService.start(context)
                runtime.trigger { it is Trigger.BootCompleted }
            }

            Intent.ACTION_POWER_CONNECTED ->
                runtime.trigger { it is Trigger.Power && it.on == PowerEdge.CONNECTED }

            Intent.ACTION_POWER_DISCONNECTED ->
                runtime.trigger { it is Trigger.Power && it.on == PowerEdge.DISCONNECTED }

            Intent.ACTION_SCREEN_ON ->
                runtime.trigger { it is Trigger.Screen && it.on == ScreenEdge.ON }

            Intent.ACTION_SCREEN_OFF ->
                runtime.trigger { it is Trigger.Screen && it.on == ScreenEdge.OFF }

            Intent.ACTION_USER_PRESENT ->
                runtime.trigger { it is Trigger.Screen && it.on == ScreenEdge.UNLOCKED }
        }
    }
}

/** Lets other apps or shell commands start a named flow via a broadcast. */
class ExternalTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        FlowRuntime.get(context).trigger { it is Trigger.Broadcast && it.name == name }
    }

    companion object {
        const val ACTION = "com.revlv.conduit.TRIGGER"
        const val EXTRA_NAME = "name"
    }
}
