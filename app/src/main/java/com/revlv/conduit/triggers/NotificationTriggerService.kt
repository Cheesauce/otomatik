package com.revlv.conduit.triggers

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.Trigger

/**
 * Turns posted notifications into flow triggers.
 *
 * Requires the separate "Notification access" grant, which is why it is its own
 * service: a user who only wants time-based routines never has to hand it over.
 */
class NotificationTriggerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // Never react to our own notifications.

        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val ongoing = sbn.isOngoing

        val runtime = FlowRuntime.get(applicationContext)
        runtime.trigger { trigger ->
            trigger is Trigger.Notification && trigger.matches(pkg, title, text, ongoing)
        }
    }

    private fun Trigger.Notification.matches(
        pkg: String,
        title: String,
        text: String,
        ongoing: Boolean,
    ): Boolean {
        if (ignoreOngoing && ongoing) return false
        if (packages.isNotEmpty() && pkg !in packages) return false
        titleContains?.let { if (!title.contains(it, ignoreCase = true)) return false }
        textContains?.let { if (!text.contains(it, ignoreCase = true)) return false }
        return true
    }

    companion object {
        /** Whether the system has bound this listener, shown in the setup screen. */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
