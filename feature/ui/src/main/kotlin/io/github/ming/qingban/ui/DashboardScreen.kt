package io.github.ming.qingban.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*
import java.time.LocalDate
import java.time.Clock
import java.time.format.DateTimeFormatter

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

    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(greeting,fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                Text("${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value-1]}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("今天的节奏",fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                        TextButton(onClick={ onPage(0,null) },contentPadding=PaddingValues(0.dp)) { Text("查看今天"); Icon(Icons.Default.ChevronRight,null,Modifier.size(16.dp)) }
                    }
                    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(completed.toString(),fontSize=44.sp,fontWeight=FontWeight.SemiBold)
                        Text("/ ${completed+pending} 件已完成",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
                    }
                    LinearProgressIndicator(progress={ if(completed+pending==0) 0f else completed.toFloat()/(completed+pending) },modifier=Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(6.dp)),trackColor=MaterialTheme.colorScheme.primary.copy(alpha=0.12f))
                    Text(support,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                DashboardMetric("今日待办",pending,Icons.Default.CheckCircleOutline,Modifier.weight(1f)) { onPage(0,null) }
                DashboardMetric("随手备忘",undated,Icons.Default.EditNote,Modifier.weight(1f)) { onPage(2,null) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable { onPage(1,today) },colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("七天安排",fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                        Text(calendarHint,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        week.forEach { date ->
                            val occurrences=items.filter { it.kind!=ItemKind.ALARM }.mapNotNull { dayOccurrence(it,date) }
                            val done=occurrences.count { it.status==ItemStatus.COMPLETED }
                            val total=occurrences.size
                            val current=date==today
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if(current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onPage(1,date) }.padding(vertical=11.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                Text("一二三四五六日"[date.dayOfWeek.value-1].toString(),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(date.dayOfMonth.toString(),fontWeight=if(current) FontWeight.SemiBold else FontWeight.Normal)
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(when { total>0 && done==total -> MaterialTheme.colorScheme.primary; total>0 -> MaterialTheme.colorScheme.secondary.copy(alpha=0.5f); else -> MaterialTheme.colorScheme.outlineVariant }))
                            }
                        }
                    }
                    Text("绿点：当日已完成 · 紫点：有安排",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable { if(next?.first?.delivery in setOf(DeliveryState.PERMISSION_REQUIRED,DeliveryState.FAILED)) onFeature("权限与桌面") else onPage(3,null) },colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    DashboardIcon(Icons.Default.Alarm,MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("下一次提醒",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val alarm=next
                        Text(if(alarm==null) noReminder else {
                            val date=alarm.second.atZone(deps.services.clock.zone)
                            val dayLabel=when(date.toLocalDate()) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${date.monthValue}月${date.dayOfMonth}日" }
                            "$dayLabel ${date.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        },fontSize=18.sp,fontWeight=FontWeight.Medium)
                        alarm?.let { Text(it.first.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); if(it.first.delivery in setOf(DeliveryState.PERMISSION_REQUIRED,DeliveryState.FAILED)) Text("提醒待启用 · 检查权限",fontSize=11.sp,color=MaterialTheme.colorScheme.error) }
                    }
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("我的工具",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                DashboardTool("番茄钟","专注、休息与正计时",Icons.Default.Timer,Modifier.fillMaxWidth()) { onFeature("番茄钟") }
                DashboardTool("习惯与督促","本地打卡 · 温和提醒",Icons.Default.CheckCircleOutline,Modifier.fillMaxWidth()) { onFeature("习惯与督促") }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    DashboardTool("全部事项","记录与完成历史",Icons.AutoMirrored.Filled.ListAlt,Modifier.weight(1f)) { onFeature("全部事项") }
                    DashboardTool("资料库","$documents 份长期参考",Icons.AutoMirrored.Filled.MenuBook,Modifier.weight(1f)) { onFeature("资料库") }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    DashboardTool("习惯与偏好","$preferences 项已保存偏好",Icons.Default.FavoriteBorder,Modifier.weight(1f)) { onFeature("习惯与偏好") }
                    DashboardTool("一句话安排",if(modelReady) "助手已就绪" else "随手记录与追问",Icons.Default.AutoAwesome,Modifier.weight(1f),accent=true,onClick=onAssistant)
                }
            }
        }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column {
                    DashboardSetting("模型设置","连接你的智能体",Icons.Default.Tune) { onFeature("模型设置") }
                    HorizontalDivider(Modifier.padding(horizontal=18.dp),color=MaterialTheme.colorScheme.outlineVariant)
                    DashboardSetting("权限与桌面","提醒、悬浮宠物与备份",Icons.Default.NotificationsNone) { onFeature("权限与桌面") }
                    HorizontalDivider(Modifier.padding(horizontal=18.dp),color=MaterialTheme.colorScheme.outlineVariant)
                    DashboardSetting("关于与隐私","版本与数据说明",Icons.Default.Info) { onFeature("关于与隐私") }
                }
            }
        }
        item { Text("轻伴 ${deps.platform.appInfo()["version"].orEmpty()}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable private fun DashboardMetric(title:String,count:Int,icon:ImageVector,modifier:Modifier,onClick:()->Unit) {
    Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(icon,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
            Text(count.toString(),fontSize=30.sp,fontWeight=FontWeight.SemiBold)
            Text(title,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun DashboardIcon(icon:ImageVector,background:Color,tint:Color) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(background),contentAlignment=Alignment.Center) { Icon(icon,null,Modifier.size(22.dp),tint=tint) }
}
@Composable private fun DashboardTool(title:String,subtitle:String,icon:ImageVector,modifier:Modifier,accent:Boolean=false,onClick:()->Unit) {
    Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=if(accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(icon,null,Modifier.size(24.dp),tint=if(accent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium)
            Text(subtitle,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun DashboardSetting(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,Modifier.size(23.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) { Text(title,fontSize=15.sp); Text(subtitle,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
