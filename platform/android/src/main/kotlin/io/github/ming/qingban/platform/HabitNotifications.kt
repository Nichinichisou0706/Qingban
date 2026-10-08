package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

class AndroidHabitScheduler(private val context: Context): HabitScheduler {
    override suspend fun cancel(id: String) { context.getSystemService(AlarmManager::class.java).cancel(habitIntent(context,id)); context.getSystemService(NotificationManager::class.java).cancel(("habit:$id").hashCode()) }
    override suspend fun reconcile(state: HabitState): String? {
        val manager=context.getSystemService(AlarmManager::class.java)
        val clock=context.dependencies.services.clock
        val now=LocalDateTime.now(clock)
        state.habits.forEach { h ->
            val pending=habitIntent(context,h.id)
            manager.cancel(pending)
            if(h.enabled && h.remind && h.time!=null) {
                val next=(0L..7).map { now.toLocalDate().plusDays(it).atTime(LocalTime.parse(h.time)) }.firstOrNull { it>now && HabitRules.scheduled(h,it.toLocalDate()) && state.checks.none { c -> c.habitId==h.id && c.date==it.toLocalDate().toString() && c.done } }
                next?.let { at ->
                    val action=habitIntent(context,h.id,h.version,at.toLocalDate().toString())
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at.atZone(clock.zone).toInstant().toEpochMilli(),action)
                }
            }
        }
        GentleTaskScheduler.reconcile(context,state.gentle,context.dependencies.services.items.store.all())
        return if(Build.VERSION.SDK_INT>=33 && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() && (state.gentle || state.habits.any { it.enabled && it.remind })) "已保存，通知权限待开启" else null
    }
}
private fun habitIntent(context: Context,id: String,version: Int=0,day: String="")=PendingIntent.getBroadcast(context,("habit:$id").hashCode(),Intent(context,HabitReceiver::class.java).setAction("habit:$id").putExtra("id",id).putExtra("version",version).putExtra("day",day),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
class HabitReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service=context.dependencies.services.habits
                val state=service.load(); val h=state.habits.firstOrNull { it.id==intent.getStringExtra("id") } ?: return@launch
                val day=intent.getStringExtra("day") ?: return@launch
                if(h.version!=intent.getIntExtra("version",0) || !h.remind || !HabitRules.scheduled(h,LocalDate.parse(day)) || state.checks.any { it.habitId==h.id && it.date==day && it.done } || day!=LocalDate.now(context.dependencies.services.clock).toString()) return@launch
                notifyLocal(context,("habit:${h.id}").hashCode(),"铭哥，留一点时间给小习惯",h.title,"habits")
            } finally { pending.finish() }
        }
    }
}
object GentleTaskScheduler {
    suspend fun reconcile(context: Context,enabled: Boolean,items: List<PlanItem>) {
        val now=LocalDateTime.now(context.dependencies.services.clock)
        val manager=context.getSystemService(AlarmManager::class.java)
        items.filter { it.kind==ItemKind.TASK }.forEach { source ->
            listOf(1,2).forEach { stage -> manager.cancel(gentleIntent(context,source.id,stage)) }
            GentleRules.times(enabled,source,now).forEachIndexed { index,at ->
                if(at>now) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at.atZone(context.dependencies.services.clock.zone).toInstant().toEpochMilli(),gentleIntent(context,source.id,index+1,source.version,now.toLocalDate().toString()))
            }
        }
    }
}
private fun gentleIntent(context: Context,id: String,stage: Int,version: Int=0,day: String="")=PendingIntent.getBroadcast(context,("gentle:$id:$stage").hashCode(),Intent(context,GentleTaskReceiver::class.java).setAction("gentle:$id:$stage").putExtra("id",id).putExtra("version",version).putExtra("day",day).putExtra("stage",stage),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
class GentleTaskReceiver: BroadcastReceiver() {
    companion object { private val deliveryMutex=Mutex() }
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val services=context.dependencies.services; val state=services.habits.load(); val now=LocalDateTime.now(services.clock)
                if(!state.gentle || now.hour !in 8..21 || intent.getStringExtra("day")!=now.toLocalDate().toString()) return@launch
                val source=services.items.store.get(intent.getStringExtra("id") ?: return@launch) ?: return@launch
                if(source.version!=intent.getIntExtra("version",0) || source.kind!=ItemKind.TASK || source.status!=ItemStatus.OPEN) return@launch
                val stage=intent.getIntExtra("stage",1)
                val at=GentleRules.times(true,source,now).getOrNull(stage-1) ?: return@launch
                if(now<at) return@launch
                val text=if(stage==1) "${source.title}：还需要继续吗？已做完可勾选完成。" else "${source.title}：今天忙的话，可以改期或先放下。"
                deliveryMutex.withLock {
                    val prefs=context.getSharedPreferences("gentle_notice",Context.MODE_PRIVATE)
                    val day=now.toLocalDate().toString()
                    val sameDay=prefs.getString("day","")==day
                    val sent=if(sameDay) prefs.getStringSet("sent",emptySet()).orEmpty() else emptySet()
                    val delivery="${source.id}:${source.version}:$stage"
                    val count=if(sameDay) prefs.getInt("count",0) else 0
                    if(!GentleRules.canNotify(now,day,count,delivery in sent)) return@withLock
                    notifyLocal(context,931,"铭哥，轻轻提醒一下",text,"today")
                    prefs.edit().putString("day",day).putInt("count",count+1).putStringSet("sent",sent+delivery).apply()
                }
            } finally { pending.finish() }
        }
    }
}
private fun notifyLocal(context: Context,id: Int,title: String,text: String,route: String) {
    createChannels(context)
    val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra("open_route",route)
    val action=PendingIntent.getActivity(context,id,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(NotificationManager::class.java).notify(id,NotificationCompat.Builder(context,REMINDER_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text).setContentIntent(action).setAutoCancel(true).build())
}
