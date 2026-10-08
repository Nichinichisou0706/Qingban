package io.github.ming.qingban.platform

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*
import java.time.LocalDate

class QingbanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { updateAll(context) } finally { pending.finish() } }
    }
    companion object {
        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QingbanWidget::class.java))
            if(ids.isEmpty()) return
            val clock = context.dependencies.services.clock
            val all = context.dependencies.services.items.store.all()
            val next = all.filter { it.status == ItemStatus.OPEN }.mapNotNull { item -> ScheduleEngine.nextTrigger(item, clock)?.let { item to it } }.minByOrNull { it.second }
            val today = all.firstOrNull { ScheduleEngine.occursOn(it, LocalDate.now(clock)) }
            val label = next?.let { "${it.second.atZone(clock.zone).toLocalTime().toString().take(5)} · ${it.first.title}" } ?: today?.title ?: "点击 GPT 娘，随手记一件事"
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra("open_assistant", true)
            val click = PendingIntent.getActivity(context, 812, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val views = RemoteViews(context.packageName, R.layout.qingban_widget).apply { setImageViewResource(R.id.widget_pet, context.dependencies.petDrawable); setTextViewText(R.id.widget_next, label); setOnClickPendingIntent(R.id.widget_root, click) }
            manager.updateAppWidget(ids, views)
        }
    }
}
