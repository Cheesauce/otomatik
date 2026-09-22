package com.revlv.conduit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.revlv.conduit.R
import com.revlv.conduit.core.engine.DeviceController
import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.IntentKind
import com.revlv.conduit.core.model.MediaCommand
import com.revlv.conduit.core.model.RingerMode
import com.revlv.conduit.core.model.SettingsPanel
import com.revlv.conduit.core.model.VolumeStream

/**
 * Everything the engine does to the device that is not the screen.
 *
 * Each method answers a plain question — did it work? — rather than throwing,
 * so the engine can turn a refusal into a run-log line that names the missing
 * grant instead of an opaque stack trace.
 */
class AndroidDeviceController(private val context: Context) : DeviceController {

    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }
    private val power by lazy { context.getSystemService(PowerManager::class.java) }
    private val main = Handler(Looper.getMainLooper())

    // --------------------------------------------------------------- launching

    override fun launchApp(packageName: String, activity: String?): Boolean {
        val intent = if (activity != null) {
            Intent().apply {
                component = ComponentName(packageName, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } ?: return false
        return start(intent)
    }

    override fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent)
    }

    override fun sendIntent(action: Action.SendIntent): Boolean {
        val intent = Intent(action.action).apply {
            action.data?.let { data = Uri.parse(it) }
            action.packageName?.let { setPackage(it) }
            action.component?.let {
                component = ComponentName.unflattenFromString(it) ?: return false
            }
            for ((key, value) in action.extras) putExtra(key, value)
            if (action.kind == IntentKind.ACTIVITY) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            when (action.kind) {
                IntentKind.ACTIVITY -> context.startActivity(intent)
                IntentKind.BROADCAST -> context.sendBroadcast(intent)
                IntentKind.SERVICE -> context.startService(intent)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun shareText(text: String, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            packageName?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val target = if (packageName == null) {
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent
        }
        return start(target)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun isAppInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------ device state

    override fun batteryPercent(): Int {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return -1
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun isCharging(): Boolean {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return false
        return manager.isCharging
    }

    override fun isScreenOn(): Boolean = power?.isInteractive == true

    override fun isNetworkConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ----------------------------------------------------------------- audio

    override fun setRingerMode(mode: RingerMode): Boolean {
        val manager = audio ?: return false
        // Dropping to silent counts as a DND change, so the system refuses it
        // without notification-policy access. Fail loudly rather than no-op.
        if (mode == RingerMode.SILENT && !hasNotificationPolicyAccess()) return false
        return try {
            manager.ringerMode = when (mode) {
                RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
                RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun setDnd(enabled: Boolean): Boolean {
        val manager = notifications ?: return false
        if (!hasNotificationPolicyAccess()) return false
        return try {
            manager.setInterruptionFilter(
                if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                },
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun setVolume(stream: VolumeStream, percent: Int): Boolean {
        val manager = audio ?: return false
        val id = when (stream) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
        }
        return try {
            val max = manager.getStreamMaxVolume(id)
            val target = (percent.coerceIn(0, 100) * max / 100)
            manager.setStreamVolume(id, target, 0)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun mediaControl(command: MediaCommand): Boolean {
        val manager = audio ?: return false
        val key = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        // Down then up: whichever app holds media focus receives the pair.
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return true
    }

    // --------------------------------------------------------------- settings

    override fun setBrightness(percent: Int, auto: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                } else {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                },
            )
            if (!auto) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (percent.coerceIn(0, 100) * 255 / 100),
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens a settings screen.
     *
     * This is the honest fallback for WiFi, Bluetooth, mobile data and airplane
     * mode: Android removed programmatic control of all four for third-party
     * apps, so the most an unrooted automation can do is put the toggle one tap
     * away. Pretending otherwise would mean actions that silently do nothing.
     */
    override fun openSettingsPanel(panel: SettingsPanel): Boolean {
        val action = when (panel) {
            SettingsPanel.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsPanel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPanel.MOBILE_DATA -> Settings.ACTION_DATA_ROAMING_SETTINGS
            SettingsPanel.AIRPLANE_MODE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsPanel.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsPanel.NFC -> Settings.ACTION_NFC_SETTINGS
            SettingsPanel.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            SettingsPanel.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsPanel.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsPanel.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsPanel.NOTIFICATION_ACCESS ->
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            SettingsPanel.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            SettingsPanel.ALL_SETTINGS -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (panel == SettingsPanel.APP_DETAILS) {
            intent.data = Uri.fromParts("package", context.packageName, null)
        }
        return start(intent)
    }

    // --------------------------------------------------------------- feedback

    override fun notify(id: String, title: String, text: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, FLOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            // Stable per-id so a repeating flow replaces its own notification
            // instead of stacking hundreds of them.
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. Nothing to do.
        }
    }

    override fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    override fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    }

    private fun ensureChannel() {
        val manager = notifications ?: return
        if (manager.getNotificationChannel(FLOW_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                FLOW_CHANNEL,
                context.getString(R.string.channel_flow_output),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun hasNotificationPolicyAccess(): Boolean =
        notifications?.isNotificationPolicyAccessGranted == true

    companion object {
        const val FLOW_CHANNEL = "conduit.flow"
    }
}
