package com.revlv.conduit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.revlv.conduit.MainActivity
import com.revlv.conduit.R
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.Trigger
import com.revlv.conduit.triggers.AlarmScheduler
import com.revlv.conduit.triggers.GeofenceManager
import com.revlv.conduit.triggers.SystemEventReceiver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps the automation runtime alive.
 *
 * On modern Android a background process is killed within minutes, and OEM
 * power managers are more aggressive still. A foreground service with a visible
 * notification is the only supported way to keep event-driven automations
 * responsive — the notification is the price of the app working at all, not an
 * oversight.
 */
class ConduitForegroundService : LifecycleService() {

    private lateinit var runtime: FlowRuntime
    private lateinit var alarms: AlarmScheduler
    private lateinit var geofences: GeofenceManager

    private val systemEvents = SystemEventReceiver()

    override fun onCreate() {
        super.onCreate()
        runtime = FlowRuntime.get(applicationContext)
        alarms = AlarmScheduler(applicationContext)
        geofences = GeofenceManager(applicationContext)

        startForeground(NOTIFICATION_ID, buildNotification(idleText()))
        registerSystemEvents()

        lifecycleScope.launch {
            runtime.initialize()

            // Any change to the flow list rewrites the alarm and geofence
            // registrations, so enabling a flow takes effect immediately.
            launch {
                runtime.store.flows.collectLatest {
                    alarms.rescheduleAll(runtime)
                    geofences.rescheduleAll(runtime)
                }
            }

            // App-switch triggers ride on the accessibility service's event
            // stream, which only exists while that service is enabled.
            launch {
                ConduitAccessibilityService.instance?.appSwitches?.collectLatest { switch ->
                    runtime.trigger { trigger ->
                        (trigger is Trigger.AppOpened && trigger.packageName == switch.opened) ||
                            (trigger is Trigger.AppClosed && trigger.packageName == switch.closed)
                    }
                }
            }

            // Keep the notification honest about what is running right now.
            launch {
                runtime.activeFlowIds.collectLatest { active ->
                    val text = if (active.isEmpty()) {
                        idleText()
                    } else {
                        resources.getQuantityString(
                            R.plurals.running_flows,
                            active.size,
                            active.size,
                        )
                    }
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart if the system kills us: the whole point of this service is to
        // be there when a trigger fires.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(systemEvents) }
        super.onDestroy()
    }

    private fun registerSystemEvents() {
        // Screen and power events are only delivered to run-time receivers,
        // never to ones declared in the manifest.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            systemEvents,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun idleText(): String {
        val count = runtime.store.flows.value.count { it.enabled }
        return resources.getQuantityString(R.plurals.watching_flows, count, count)
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_runtime),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_runtime_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "conduit.runtime"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, ConduitForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConduitForegroundService::class.java))
        }
    }
}
