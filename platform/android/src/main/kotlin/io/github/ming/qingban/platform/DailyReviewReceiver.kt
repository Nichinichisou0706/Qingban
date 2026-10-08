package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.time.*

object DailyReviewScheduler {
    fun schedule(context: Context) {
        val clock=context.dependencies.services.clock
        val now=ZonedDateTime.now(clock)
        val next=listOf(8,23).map { hour -> now.toLocalDate().atTime(hour,0).atZone(clock.zone).let { if(it>now) it else it.plusDays(1) } }.minOrNull()!!
        val pending=PendingIntent.getBroadcast(context,921,Intent(context,DailyReviewReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),pending)
    }
}
class DailyReviewReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val p=context.dependencies.services.review.refresh()
                context.dependencies.services.habits.load()
                if(p!=null && context.dependencies.services.sessions.read("review_notified:${p.id}")==null) {
                    createChannels(context)
                    val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra("open_route","today")
                    val pending=PendingIntent.getActivity(context,921,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    context.getSystemService(NotificationManager::class.java).notify(921,NotificationCompat.Builder(context,REMINDER_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("铭哥，今天的安排可以调整一下").setContentText("有 ${p.moves.size} 件建议调整，查看后由你决定").setContentIntent(pending).setAutoCancel(true).build())
                    context.dependencies.services.sessions.write("review_notified:${p.id}","true")
                }
            } finally { DailyReviewScheduler.schedule(context); result.finish() }
        }
    }
}
