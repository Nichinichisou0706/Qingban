package io.github.ming.qingban.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ming.qingban.domain.*
import java.time.Clock

class AndroidReminderScheduler(private val context: Context, private val clock: Clock) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    override suspend fun reconcile(item: PlanItem): ScheduleResult {
        val intent = triggerIntent(context, item, false)
        alarms.cancel(intent)
        val prefs = context.getSharedPreferences("snooze_state", Context.MODE_PRIVATE)
        val keepSnooze = item.status == ItemStatus.OPEN && prefs.getInt("${item.id}:version", -1) == item.version && prefs.getLong("${item.id}:at", 0) > clock.millis()
        if(!keepSnooze) { alarms.cancel(triggerIntent(context, item, true)); prefs.edit().remove("${item.id}:version").remove("${item.id}:at").apply() }
        val next = ScheduleEngine.nextTrigger(item, clock)
        if(next == null && !keepSnooze) return RecurrenceRules.unavailableMessage(item, clock)?.let { ScheduleResult(DeliveryState.FAILED, it) } ?: ScheduleResult(DeliveryState.NONE)
        if(Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) return ScheduleResult(DeliveryState.PERMISSION_REQUIRED, "需要开启精确闹钟权限")
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return ScheduleResult(DeliveryState.PERMISSION_REQUIRED, "需要开启通知权限")
        return try {
            if(keepSnooze) setWakeAlarm(context, item, prefs.getLong("${item.id}:at", 0), triggerIntent(context, item, true))
            if(next != null && item.kind == ItemKind.ALARM) {
                val show = PendingIntent.getActivity(context, item.id.hashCode(), context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(next.toEpochMilli(), show), intent)
            } else if(next != null) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
            ScheduleResult(DeliveryState.SCHEDULED)
        } catch(e: SecurityException) { ScheduleResult(DeliveryState.PERMISSION_REQUIRED, "系统拒绝了提醒权限") }
    }
    companion object {
        fun triggerIntent(context: Context, item: PlanItem, snooze: Boolean): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).setAction("${context.packageName}.alarm.${item.id}.${if(snooze) "snooze" else "regular"}").putExtra("item_id", item.id).putExtra("version", item.version).putExtra("snooze", snooze)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        /** Alarm-clock alarms are exempt from Doze/HyperOS batching; falls back to inexact when exact permission is missing. */
        private fun setWakeAlarm(context: Context, item: PlanItem, at: Long, operation: PendingIntent) {
            val manager = context.getSystemService(AlarmManager::class.java)
            if(Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation); return }
            val show = PendingIntent.getActivity(context, item.id.hashCode(), context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        }
        fun snooze(context: Context, item: PlanItem) {
            val at = System.currentTimeMillis() + item.alarm.snoozeMinutes * 60_000L
            try { setWakeAlarm(context, item, at, triggerIntent(context, item, true)) }
            catch(e: SecurityException) { context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, triggerIntent(context, item, true)) }
            context.getSharedPreferences("snooze_state", Context.MODE_PRIVATE).edit().putInt("${item.id}:version", item.version).putLong("${item.id}:at", at).apply()
        }
    }
}
