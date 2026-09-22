package com.revlv.conduit.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.Trigger
import com.revlv.conduit.core.model.Weekday
import java.util.Calendar

/**
 * Wall-clock and interval triggers, backed by AlarmManager.
 *
 * Each (flow, trigger) pair owns one alarm, rescheduled after it fires. Exact
 * alarms are used where permitted, because an automation that fires "sometime
 * after 07:00, when Doze feels like it" is not an automation the user can rely
 * on — but the code degrades to an inexact alarm rather than crashing when the
 * permission is absent.
 */
class AlarmScheduler(private val context: Context) {

    private val manager = context.getSystemService(AlarmManager::class.java)

    fun rescheduleAll(runtime: FlowRuntime) {
        val flows = runtime.store.flows.value
        for (flow in flows) {
            flow.triggers.forEachIndexed { index, trigger ->
                val key = AlarmKey(flow.id, index)
                cancel(key)
                if (!flow.enabled) return@forEachIndexed
                when (trigger) {
                    is Trigger.Time -> scheduleTime(key, trigger)
                    is Trigger.Interval -> scheduleInterval(key, trigger)
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleTime(key: AlarmKey, trigger: Trigger.Time) {
        val next = nextOccurrence(trigger) ?: return
        setAlarm(key, next)
    }

    private fun scheduleInterval(key: AlarmKey, trigger: Trigger.Interval) {
        val minutes = trigger.everyMinutes.coerceAtLeast(1)
        setAlarm(key, System.currentTimeMillis() + minutes * 60_000L)
    }

    private fun setAlarm(key: AlarmKey, atMillis: Long) {
        val manager = manager ?: return
        val pending = pendingIntent(key, mutable = false)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        try {
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                // Without the exact-alarm grant the system may delay this by
                // minutes. The UI warns about it rather than hiding the drift.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    fun cancel(key: AlarmKey) {
        manager?.cancel(pendingIntent(key, mutable = false))
    }

    private fun pendingIntent(key: AlarmKey, mutable: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_FLOW_ID, key.flowId)
            putExtra(EXTRA_TRIGGER_INDEX, key.triggerIndex)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, key.requestCode, intent, flags)
    }

    data class AlarmKey(val flowId: String, val triggerIndex: Int) {
        val requestCode: Int get() = (flowId.hashCode() * 31 + triggerIndex) and 0x7FFFFFFF
    }

    companion object {
        const val ACTION_FIRE = "com.revlv.conduit.ALARM_FIRE"
        const val EXTRA_FLOW_ID = "flowId"
        const val EXTRA_TRIGGER_INDEX = "triggerIndex"

        /**
         * Next matching instant for a time trigger, searching up to a week out.
         * Returns null when the trigger's time cannot be parsed.
         */
        fun nextOccurrence(trigger: Trigger.Time, from: Calendar = Calendar.getInstance()): Long? {
            val parts = trigger.at.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null

            val candidate = (from.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= from.timeInMillis) {
                candidate.add(Calendar.DAY_OF_YEAR, 1)
            }
            if (trigger.days.isEmpty()) return candidate.timeInMillis

            repeat(8) {
                if (candidate.weekday() in trigger.days) return candidate.timeInMillis
                candidate.add(Calendar.DAY_OF_YEAR, 1)
            }
            return null
        }

        private fun Calendar.weekday(): Weekday = when (get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> Weekday.MON
            Calendar.TUESDAY -> Weekday.TUE
            Calendar.WEDNESDAY -> Weekday.WED
            Calendar.THURSDAY -> Weekday.THU
            Calendar.FRIDAY -> Weekday.FRI
            Calendar.SATURDAY -> Weekday.SAT
            else -> Weekday.SUN
        }
    }
}

/** Receives a fired alarm, runs the flow, and books the next one. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val flowId = intent.getStringExtra(AlarmScheduler.EXTRA_FLOW_ID) ?: return
        val index = intent.getIntExtra(AlarmScheduler.EXTRA_TRIGGER_INDEX, -1)

        val runtime = FlowRuntime.get(context)
        val flow = runtime.store.get(flowId) ?: return
        val trigger = flow.triggers.getOrNull(index) ?: return

        if (flow.enabled) runtime.runNow(flow)

        // Rebook immediately: a repeating alarm would drift, and an interval
        // trigger has no natural next time until this one has fired.
        val scheduler = AlarmScheduler(context)
        when (trigger) {
            is Trigger.Time, is Trigger.Interval -> scheduler.rescheduleAll(runtime)
            else -> Unit
        }
    }
}
