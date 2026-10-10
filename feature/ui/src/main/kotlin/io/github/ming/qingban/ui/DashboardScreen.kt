package io.github.ming.qingban.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*
import java.time.LocalDate
import java.time.Clock
import java.time.format.DateTimeFormatter

/** “我的”页：今日概览、七天安排、下一次提醒，以及工具与设置入口。 */
@Composable fun DashboardScreen(
    deps: UiDependencies,
    onPage: (Int, LocalDate?) -> Unit,
    onFeature: (String) -> Unit,
    onAssistant: () -> Unit
) {
    val vm: DashboardViewModel = viewModel(factory=factory { DashboardViewModel(deps.services) })
    val items by vm.items.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val now by produceState(deps.services.clock.instant()) {
        while(true) { value=deps.services.clock.instant(); kotlinx.coroutines.delay(30_000) }
    }
    val snapshotClock=Clock.fixed(now,deps.services.clock.zone)
    val today=LocalDate.now(snapshotClock)
    val todayItems=items.filter { it.kind!=ItemKind.ALARM }.mapNotNull { dayOccurrence(it,today) }
    val completed=todayItems.count { it.status==ItemStatus.COMPLETED }
    val pending=todayItems.count { it.status==ItemStatus.OPEN }
    val undated=items.count { it.status==ItemStatus.OPEN && it.kind!=ItemKind.ALARM && it.schedule?.date==null && it.schedule?.rangeStart==null && it.schedule?.recurrence==null }
    val next=items.mapNotNull { item -> ScheduleEngine.nextTrigger(item,snapshotClock)?.let { item to it } }.minByOrNull { it.second }
    val week=DateWindows.centered(today)
    val modelReady=deps.platform.modelSummary()["has_key"]=="true"
    val progress=DayProgress(pending,completed)
    val greeting=rotatingCopy("dashboard.title",CopyCatalog.dashboardTitle(progress))
    val support=rotatingCopy("dashboard.support",CopyCatalog.dashboardSupport(progress))
    val calendarHint=rotatingCopy("dashboard.calendar",CopyCatalog.calendarTip)
    val noReminder=rotatingCopy("dashboard.noReminder",CopyCatalog.alarmEmpty)

    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=QSpace.page,end=QSpace.page,top=QSpace.xs,bottom=QSpace.fabClearance),verticalArrangement=Arrangement.spacedBy(QSpace.l)) {
        item {
            QCard(Modifier.fillMaxWidth(),onClick=onAssistant,brush=Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,MaterialTheme.colorScheme.secondaryContainer))) {
                Row(Modifier.padding(QSpace.l),verticalAlignment=Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.gpt_icon),"GPT 娘",Modifier.size(56.dp).clip(CircleShape).border(2.dp,MaterialTheme.colorScheme.surface,CircleShape))
                    Column(Modifier.weight(1f).padding(start=QSpace.m)) {
                        Text(greeting,style=MaterialTheme.typography.titleLarge)
                        Text("${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value-1]} · ${if(modelReady) "助手已就绪" else "离线记录模式"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(QSpace.m)) {
                QCard(Modifier.weight(1.4f),onClick={ onPage(0,null) }) {
                    Column(Modifier.padding(QSpace.l),verticalArrangement=Arrangement.spacedBy(QSpace.s)) {
                        Text("今天的节奏",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment=Alignment.Bottom) {
                            Text(completed.toString(),style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
                            Text(" / ${completed+pending} 已完成",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=6.dp))
                        }
                        LinearProgressIndicator(progress={ if(completed+pending==0) 0f else completed.toFloat()/(completed+pending) },modifier=Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),trackColor=MaterialTheme.colorScheme.primaryContainer,drawStopIndicator={})
                        Text(support,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(QSpace.m)) {
                    DashboardMetric("今日待办",pending,Icons.Default.CheckCircleOutline,false) { onPage(0,null) }
                    DashboardMetric("随手备忘",undated,Icons.Default.EditNote,true) { onPage(2,null) }
                }
            }
        }
        item {
            QCard(Modifier.fillMaxWidth(),onClick={ onPage(1,today) }) {
                Column(Modifier.padding(QSpace.l),verticalArrangement=Arrangement.spacedBy(QSpace.m)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("七天安排",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                        Text(calendarHint,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        week.forEach { date ->
                            val occurrences=items.filter { it.kind!=ItemKind.ALARM }.mapNotNull { dayOccurrence(it,date) }
                            val done=occurrences.count { it.status==ItemStatus.COMPLETED }
                            val total=occurrences.size
                            val current=date==today
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if(current) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { onPage(1,date) }.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("一二三四五六日"[date.dayOfWeek.value-1].toString(),style=MaterialTheme.typography.labelSmall,color=if(current) MaterialTheme.colorScheme.onPrimary.copy(alpha=.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.titleSmall,color=if(current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                Box(Modifier.size(6.dp).clip(CircleShape).background(when { total>0 && done==total -> if(current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary; total>0 -> MaterialTheme.colorScheme.secondary; else -> Color.Transparent }))
                            }
                        }
                    }
                    Text("绿点：当日已完成 · 紫点：有安排",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            val alarm=next
            val problem=alarm?.first?.delivery in setOf(DeliveryState.PERMISSION_REQUIRED,DeliveryState.FAILED)
            QCard(Modifier.fillMaxWidth(),onClick={ if(problem) onFeature("权限与桌面") else onPage(3,null) }) {
                Row(Modifier.padding(QSpace.l),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(QSpace.m)) {
                    IconBadge(Icons.Default.Alarm,lavender=true,size=44.dp)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("下一次提醒",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(alarm==null) noReminder else {
                            val date=alarm.second.atZone(deps.services.clock.zone)
                            val dayLabel=when(date.toLocalDate()) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${date.monthValue}月${date.dayOfMonth}日" }
                            "$dayLabel ${date.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        },style=MaterialTheme.typography.titleMedium)
                        alarm?.let { Text(it.first.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        if(problem) Text("提醒待启用 · 检查权限",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                    }
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.outline)
                }
            }
        }
        item {
            Text("我的工具",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=QSpace.xs))
            Spacer(Modifier.height(QSpace.m))
            val tools=listOf(
                Triple("番茄钟","专注、休息与正计时",Icons.Default.Timer),
                Triple("习惯与督促","本地打卡 · 温和提醒",Icons.Default.CheckCircleOutline),
                Triple("全部事项","记录与完成历史",Icons.AutoMirrored.Filled.ListAlt),
                Triple("资料库","$documents 份长期参考",Icons.AutoMirrored.Filled.MenuBook),
                Triple("习惯与偏好","$preferences 项已保存偏好",Icons.Default.FavoriteBorder),
            )
            Column(verticalArrangement=Arrangement.spacedBy(QSpace.m)) {
                tools.chunked(2).forEach { row ->
                    Row(horizontalArrangement=Arrangement.spacedBy(QSpace.m)) {
                        row.forEach { (title,subtitle,icon) -> DashboardTool(title,subtitle,icon,Modifier.weight(1f)) { onFeature(title) } }
                        if(row.size==1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            QCard(Modifier.fillMaxWidth()) {
                DashboardSetting("模型设置","连接你的智能体",Icons.Default.Tune) { onFeature("模型设置") }
                HorizontalDivider(Modifier.padding(start=68.dp),color=MaterialTheme.colorScheme.outlineVariant)
                DashboardSetting("权限与桌面","提醒、悬浮宠物与备份",Icons.Default.NotificationsNone) { onFeature("权限与桌面") }
                HorizontalDivider(Modifier.padding(start=68.dp),color=MaterialTheme.colorScheme.outlineVariant)
                DashboardSetting("关于与隐私","版本与数据说明",Icons.Default.Info) { onFeature("关于与隐私") }
            }
        }
        item { Text("轻伴 ${deps.platform.appInfo()["version"].orEmpty()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.outline,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center) }
    }
}

@Composable private fun DashboardMetric(title:String,count:Int,icon:ImageVector,lavender:Boolean,onClick:()->Unit) {
    QCard(Modifier.fillMaxWidth(),onClick=onClick) {
        Row(Modifier.padding(horizontal=QSpace.m,vertical=QSpace.m),verticalAlignment=Alignment.CenterVertically) {
            IconBadge(icon,lavender,size=32.dp)
            Column(Modifier.padding(start=QSpace.s)) {
                Text(count.toString(),style=MaterialTheme.typography.titleLarge)
                Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun DashboardTool(title:String,subtitle:String,icon:ImageVector,modifier:Modifier,accent:Boolean=false,onClick:()->Unit) {
    QCard(modifier,onClick=onClick,color=if(accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(QSpace.l),verticalArrangement=Arrangement.spacedBy(QSpace.s)) {
            IconBadge(icon,lavender=accent,size=36.dp)
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text(subtitle,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun DashboardSetting(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=QSpace.l,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(QSpace.m)) {
        IconBadge(icon,size=36.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(subtitle,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.outline)
    }
}
