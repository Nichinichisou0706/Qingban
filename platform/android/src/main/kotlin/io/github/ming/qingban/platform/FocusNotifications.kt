package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*

class AndroidElapsedClock(private val context: Context): ElapsedClock {
    override fun millis()=android.os.SystemClock.elapsedRealtime()
    override fun bootId(): String?=runCatching { android.provider.Settings.Global.getInt(context.contentResolver,"boot_count").toString() }.getOrNull()
}

class AndroidFocusScheduler(private val context: Context): FocusScheduler {
    private fun pending(): PendingIntent = PendingIntent.getBroadcast(context,7301,Intent(context,FocusReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override suspend fun reconcile(run: FocusRun?, notify: Boolean): String? {
        val manager=context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending())
        val active=run?.status=="running" && run.mode=="pomodoro"
        if(active) runCatching { ContextCompat.startForegroundService(context,Intent(context,FocusTimerService::class.java).putExtra("title",run!!.title).putExtra("phase",run.phase).putExtra("end",run.endAt() ?: System.currentTimeMillis())) }
        else context.stopService(Intent(context,FocusTimerService::class.java))
        if(!notify || run?.endAt()==null) return null
        val exact=Build.VERSION.SDK_INT<31 || manager.canScheduleExactAlarms()
        val monotonic=run.monotonicStart!=null && run.bootId!=null && run.bootId==AndroidElapsedClock(context).bootId()
        val at=if(monotonic) run.monotonicStart!!+run.targetMillis-run.elapsedMillis else run.endAt()!!
        val type=if(monotonic) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP
        if(exact) manager.setExactAndAllowWhileIdle(type,at,pending())
        else manager.setAndAllowWhileIdle(type,at,pending())
        return when {
            !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() -> "番茄钟正在计时，通知权限未开启"
            !exact -> "番茄钟正在计时；开启精确闹钟权限可减少后台提醒延迟"
            else -> null
        }
    }
    override suspend fun completed(run: FocusRun) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("qingban_focus","专注与休息",NotificationManager.IMPORTANCE_HIGH))
        val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra("open_focus",true)
        val pending=PendingIntent.getActivity(context,7302,launch,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val message=if(run.phase=="rest") "休息结束，准备好后开始下一轮吧" else "完成一轮专注，休息一下吧"
        manager.notify(run.id.hashCode(),NotificationCompat.Builder(context,"qingban_focus").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(message).setContentText(run.title).setContentIntent(pending).setAutoCancel(true).build())
    }
}
class FocusReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { context.dependencies.services.focus.load() } finally { pending.finish() } }
    }
}
