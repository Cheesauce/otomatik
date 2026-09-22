package com.revlv.conduit.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.revlv.conduit.core.model.Capability
import com.revlv.conduit.service.ConduitAccessibilityService

/**
 * Reads and requests the manual grants this app depends on.
 *
 * None of these can be obtained with a normal run-time permission dialog — each
 * one sends the user to a different system screen. Getting this wrong is the
 * most common reason an automation app appears broken, so the state is checked
 * live rather than cached.
 */
object Capabilities {

    data class Status(
        val capability: Capability,
        val granted: Boolean,
        val title: String,
        val why: String,
        val required: Boolean,
    )

    fun statusOf(context: Context, capability: Capability): Boolean = when (capability) {
        Capability.ACCESSIBILITY -> isAccessibilityEnabled(context)
        Capability.NOTIFICATION_ACCESS -> isNotificationListenerEnabled(context)
        Capability.NOTIFICATION_POLICY ->
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true
        Capability.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Capability.POST_NOTIFICATIONS ->
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        Capability.BACKGROUND_LOCATION ->
            context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        Capability.EXACT_ALARM ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        Capability.BATTERY_UNRESTRICTED ->
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun all(context: Context): List<Status> = listOf(
        Status(
            Capability.ACCESSIBILITY,
            statusOf(context, Capability.ACCESSIBILITY),
            "Accessibility service",
            "Required for anything that taps, types or reads another app. " +
                "Android offers no other way for one app to drive another.",
            required = true,
        ),
        Status(
            Capability.BATTERY_UNRESTRICTED,
            statusOf(context, Capability.BATTERY_UNRESTRICTED),
            "Unrestricted battery",
            "Without this the system suspends the runtime after a few minutes " +
                "and triggers stop firing. This is the most common cause of " +
                "\"it worked yesterday\".",
            required = true,
        ),
        Status(
            Capability.POST_NOTIFICATIONS,
            statusOf(context, Capability.POST_NOTIFICATIONS),
            "Post notifications",
            "Needed for the ongoing runtime notification and for Notify actions.",
            required = true,
        ),
        Status(
            Capability.EXACT_ALARM,
            statusOf(context, Capability.EXACT_ALARM),
            "Exact alarms",
            "Keeps a 07:00 routine at 07:00. Without it Android may delay it " +
                "by several minutes.",
            required = false,
        ),
        Status(
            Capability.NOTIFICATION_ACCESS,
            statusOf(context, Capability.NOTIFICATION_ACCESS),
            "Notification access",
            "Only needed for flows triggered by another app's notifications.",
            required = false,
        ),
        Status(
            Capability.NOTIFICATION_POLICY,
            statusOf(context, Capability.NOTIFICATION_POLICY),
            "Do Not Disturb access",
            "Only needed to silence the phone or switch DND on and off.",
            required = false,
        ),
        Status(
            Capability.WRITE_SETTINGS,
            statusOf(context, Capability.WRITE_SETTINGS),
            "Modify system settings",
            "Only needed to change screen brightness.",
            required = false,
        ),
        Status(
            Capability.BACKGROUND_LOCATION,
            statusOf(context, Capability.BACKGROUND_LOCATION),
            "Background location",
            "Only needed for location-triggered flows. Grant \"Allow all the " +
                "time\" or geofences will not fire with the screen off.",
            required = false,
        ),
    )

    /** The settings screen that grants a capability. */
    fun intentFor(context: Context, capability: Capability): Intent = when (capability) {
        Capability.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        Capability.NOTIFICATION_ACCESS ->
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

        Capability.NOTIFICATION_POLICY ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        Capability.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))

        Capability.POST_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        Capability.BACKGROUND_LOCATION, Capability.BATTERY_UNRESTRICTED ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))

        Capability.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", context.packageName, null))
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isAccessibilityEnabled(context: Context): Boolean {
        // The live instance is the ground truth, but it is null for a moment
        // after the user enables the service, so fall back to the secure
        // setting the system itself maintains.
        if (ConduitAccessibilityService.instance != null) return true
        val expected = ComponentName(context, ConduitAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(context.packageName)
    }
}
