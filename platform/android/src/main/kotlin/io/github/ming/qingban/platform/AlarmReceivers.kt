package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val item = context.dependencies.services.items.store.get(intent.getStringExtra("item_id") ?: return@launch) ?: return@launch
                if(item.version != intent.getIntExtra("version", -1) || item.status != ItemStatus.OPEN) return@launch
                if(intent.getBooleanExtra("snooze", false)) context.getSharedPreferences("snooze_state", Context.MODE_PRIVATE).edit().remove("${item.id}:version").remove("${item.id}:at").apply()
                if(item.kind == ItemKind.ALARM) ContextCompat.startForegroundService(context, Intent(context, RingingService::class.java).putExtra("item_id", item.id).putExtra("version", item.version))
                else {
                    createChannels(context)
                    val launch = PendingIntent.getActivity(context, item.id.hashCode(), context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(item.title).setContentText("到你安排的时间了").setContentIntent(launch).setAutoCancel(true).build()
                    context.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), notification)
                }
                if(item.schedule?.recurrence == null) context.dependencies.services.items.store.setDelivery(item.id, item.version, DeliveryState.FIRED)
                else {
                    context.dependencies.services.items.reconcileCurrent(item.id, item.version)
                }
                QingbanWidget.updateAll(context)
                if(context.getSharedPreferences("pet_settings",Context.MODE_PRIVATE).getBoolean("running",false)) runCatching { context.startService(Intent(context,PetOverlayService::class.java).setAction("bubble").putExtra("title","到点啦：${item.title}")) }
            } catch(e: Exception) {
                val id = intent.getStringExtra("item_id")
                if(id != null) runCatching { context.dependencies.services.items.store.setDelivery(id, intent.getIntExtra("version", -1), if(e is SecurityException) DeliveryState.PERMISSION_REQUIRED else DeliveryState.FAILED) }
            } finally { pending.finish() }
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { context.dependencies.services.items.restoreSchedules(); context.dependencies.services.focus.load(); context.dependencies.services.habits.load(); context.dependencies.services.review.refresh(); DailyReviewScheduler.schedule(context); QingbanWidget.updateAll(context) } finally { pending.finish() } }
    }
}
internal const val ALARM_CHANNEL = "qingban_alarm"
internal const val REMINDER_CHANNEL = "qingban_reminder"
internal const val PET_CHANNEL = "qingban_pet"
internal fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "音乐闹钟", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "事项提醒", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(PET_CHANNEL, "悬浮 GPT 娘", NotificationManager.IMPORTANCE_LOW))
}
