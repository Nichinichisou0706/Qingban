package io.github.ming.qingban.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import java.time.*

@Composable fun DailyBoard(items: List<PlanItem>, onEdit: (PlanItem) -> Unit, onComplete: (PlanItem) -> Unit, onFocus: (PlanItem) -> Unit, onCheck: (PlanItem,Int) -> Unit, selected: Set<String>? = null, onSelect: (PlanItem) -> Unit = {}) {
    val stacked=LocalDensity.current.fontScale>1.25f || LocalConfiguration.current.screenWidthDp<360
    val groups=items.groupBy { item -> item.schedule?.time?.substringBefore(':')?.let { "$it:00" } ?: when(item.schedule?.period) { "morning" -> "上午"; "afternoon" -> "下午"; "evening" -> "晚上"; else -> "当天待安排" } }
    val order=groups.keys.sortedBy { key -> when(key) { "上午" -> 6; "下午" -> 12; "晚上" -> 18; "当天待安排" -> 25; else -> key.substringBefore(':').toIntOrNull() ?: 25 } }
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row { Text("时段",Modifier.width(48.dp),style=MaterialTheme.typography.labelSmall); Text("事项",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); if(!stacked) Text("习惯",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary) }
            HorizontalDivider(Modifier.padding(top=10.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.35f))
            order.forEach { key ->
                val tasks=DependencyPlanner.ordered(groups.getValue(key))
                val life=tasks.filter { it.isHabitTask }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical=10.dp)) {
                    Text(key,Modifier.width(48.dp).padding(top=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
                    if(stacked) Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(tasks.size>life.size) BoardColumn(tasks-life.toSet(),Modifier.fillMaxWidth(),onEdit,onComplete,onFocus,onCheck,selected,onSelect)
                        if(life.isNotEmpty()) { Text("习惯",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary); BoardColumn(life,Modifier.fillMaxWidth(),onEdit,onComplete,onFocus,onCheck,selected,onSelect) }
                    } else {
                        BoardColumn(tasks-life.toSet(),Modifier.weight(1f),onEdit,onComplete,onFocus,onCheck,selected,onSelect)
                        VerticalDivider(Modifier.padding(horizontal=5.dp),color=MaterialTheme.colorScheme.outlineVariant)
                        BoardColumn(life,Modifier.weight(1f),onEdit,onComplete,onFocus,onCheck,selected,onSelect)
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
            Text(if(selected!=null) "勾选要删除的事项" else "重复习惯只完成当天这次",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp))
        }
    }
}
@Composable private fun BoardColumn(items: List<PlanItem>, modifier: Modifier, onEdit: (PlanItem) -> Unit, onComplete: (PlanItem) -> Unit, onFocus: (PlanItem) -> Unit, onCheck: (PlanItem,Int) -> Unit, selected: Set<String>? = null, onSelect: (PlanItem) -> Unit = {}) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(items.isEmpty()) Text("—",color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(8.dp))
        items.forEach { item ->
            Surface(Modifier.fillMaxWidth().clickable { if(selected!=null) onSelect(item) else onEdit(item) },shape=RoundedCornerShape(12.dp),color=if(item.status==ItemStatus.COMPLETED) MaterialTheme.colorScheme.surfaceVariant else if(item.isHabitTask) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(7.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { if(selected!=null || item.kind!=ItemKind.ALARM) Checkbox(if(selected!=null) item.id in selected else item.status==ItemStatus.COMPLETED,{ if(selected!=null) onSelect(item) else onComplete(item) },modifier=Modifier.size(44.dp).semantics { contentDescription=(if(selected!=null) "选择事项：" else "完成事项：")+item.title }); Text(item.title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall) }
                    val range=item.schedule?.time?.let { time -> item.durationMinutes?.let { minutes -> val start=LocalTime.parse(time); val end=start.plusMinutes(minutes.toLong()); "$time — $end${if(start.hour*60+start.minute+minutes>=1440) "（次日）" else ""}" } ?: time } ?: item.schedule?.period?.let(::periodLabel).orEmpty()
                    Text(range,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(item.dependsOn.isNotEmpty()) Text("有前置事项 · 点开查看",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
                    if(item.detail.isNotBlank()) Text(item.detail,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(selected==null) item.checklist.forEachIndexed { index,entry -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(entry.completed,{ onCheck(item,index) },modifier=Modifier.size(44.dp)); Text(entry.text,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall) } }
                    if(item.isHabitTask) Text(RecurrenceRules.label(item.schedule!!.recurrence!!),fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
                    if(item.label.isNotBlank() && item.label !in setOf("学习工作","习惯")) Text(item.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                    if(selected==null && item.kind!=ItemKind.ALARM && item.status==ItemStatus.OPEN) TextButton(onClick={ onFocus(item) },contentPadding=PaddingValues(horizontal=4.dp)) { Text("开始专注",style=MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ScheduleCalendar(selected: LocalDate, items: List<PlanItem>, deps: UiDependencies, requestedView: String?, onSelect: (LocalDate) -> Unit, onEdit: (PlanItem,LocalDate) -> Unit) {
    var view by rememberSaveable { mutableStateOf("month") }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(requestedView) { view=requestedView ?: deps.services.sessions.read("calendar_view") ?: "month" }
    LaunchedEffect(selected) { monthText=YearMonth.from(selected).toString() }
    val month=YearMonth.parse(monthText)
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("month" to "月","week" to "周","three_day" to "三日","day" to "日").forEach { (key,label) -> FilterChip(view==key,{ view=key; scope.launch { deps.services.sessions.write("calendar_view",key) } },label={ Text(label) }) }
                TextButton(onClick={ onSelect(LocalDate.now(deps.services.clock)); monthText=YearMonth.from(LocalDate.now(deps.services.clock)).toString() }) { Text("今天") }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={ if(view=="month") monthText=month.minusMonths(1).toString() else onSelect(selected.minusDays(when(view) { "week" -> 7L; "three_day" -> 3L; else -> 1L })) }) { Icon(Icons.Default.ChevronLeft,"上一页日期") }
                Text(if(view=="month") "${month.year}年${month.monthValue}月" else "${selected.year}年${selected.monthValue}月${selected.dayOfMonth}日",Modifier.weight(1f),fontWeight=FontWeight.Medium)
                IconButton(onClick={ if(view=="month") monthText=month.plusMonths(1).toString() else onSelect(selected.plusDays(when(view) { "week" -> 7L; "three_day" -> 3L; else -> 1L })) }) { Icon(Icons.Default.ChevronRight,"下一页日期") }
            }
            if(view=="month") MonthEvents(month,selected,items,onSelect)
            else {
                val count=when(view) { "week" -> 7; "three_day" -> 3; else -> 1 }
                TimeGrid(DateWindows.centered(selected,count),selected,items,onSelect,onEdit)
            }
        }
    }
}
@Composable private fun MonthEvents(month: YearMonth, selected: LocalDate, items: List<PlanItem>, onSelect: (LocalDate) -> Unit) {
    Row { "一二三四五六日".forEach { Text(it.toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
    val start=month.atDay(1).minusDays(month.atDay(1).dayOfWeek.value-1L)
    val rows=(month.atDay(1).dayOfWeek.value-1+month.lengthOfMonth()+6)/7
    repeat(rows) { row -> Row(Modifier.fillMaxWidth().height(84.dp)) { repeat(7) { col ->
        val date=start.plusDays((row*7+col).toLong())
        val events=items.mapNotNull { ScheduleEngine.onDate(it,date) }.sortedBy { it.schedule?.time ?: "" }
        Column(Modifier.weight(1f).fillMaxHeight().border(.5.dp,MaterialTheme.colorScheme.outlineVariant).background(if(date==selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onSelect(date) }.padding(2.dp)) {
            Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.labelMedium,color=if(YearMonth.from(date)!=month) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            events.take(2).forEach { event -> Text(event.title,Modifier.fillMaxWidth().background(if(event.status==ItemStatus.COMPLETED) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,RoundedCornerShape(4.dp)).padding(2.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,lineHeight=14.sp) }
            if(events.size>2) Text("+${events.size-2}",style=MaterialTheme.typography.labelSmall)
        }
    } } }
}
@Composable private fun TimeGrid(days: List<LocalDate>, selected: LocalDate, items: List<PlanItem>, onSelect: (LocalDate) -> Unit, onEdit: (PlanItem,LocalDate) -> Unit) {
    val horizontal=rememberScrollState()
    val vertical=rememberScrollState()
    val density=LocalDensity.current
    val first=days.flatMap { CalendarLayout.blocks(items,it) }.minOfOrNull { it.start } ?: (8*60)
    LaunchedEffect(days.first(),first) {
        vertical.scrollTo(with(density) { ((first/60-1).coerceAtLeast(0)*52).dp.roundToPx() })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dayWidth=if(days.size==1) maxWidth-42.dp else maxOf(90.dp,(maxWidth-42.dp)/days.size)
        LaunchedEffect(days,dayWidth,maxWidth) {
            val index=days.indexOf(selected).coerceAtLeast(0)
            val offset=(dayWidth*(index+.5f)-(maxWidth-42.dp)/2).coerceAtLeast(0.dp)
            horizontal.scrollTo(with(density) { offset.roundToPx() })
        }
        Column(Modifier.horizontalScroll(horizontal)) {
            Row { Spacer(Modifier.width(42.dp)); days.forEach { date ->
                Column(Modifier.width(dayWidth).clickable { onSelect(date) }.background(if(date==selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(5.dp)) {
                    Text("${date.monthValue}/${date.dayOfMonth} 周${"一二三四五六日"[date.dayOfWeek.value-1]}",fontSize=11.sp)
                    val untimed=items.mapNotNull { ScheduleEngine.onDate(it,date) }.filter { it.schedule?.time==null }
                    untimed.forEach { event -> Text(event.title,Modifier.fillMaxWidth().clickable { onEdit(event,date) }.padding(vertical=2.dp),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
                }
            } }
            Row(Modifier.height(460.dp).verticalScroll(vertical)) {
                Column(Modifier.width(42.dp)) { repeat(24) { Text("${it.toString().padStart(2,'0')}:00",Modifier.height(52.dp).padding(top=3.dp),fontSize=9.sp,color=MaterialTheme.colorScheme.outline) } }
                days.forEach { date ->
                    val blocks=CalendarLayout.blocks(items,date)
                    Box(Modifier.width(dayWidth).height((24*52).dp).border(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        repeat(24) { hour -> HorizontalDivider(Modifier.offset(y=(hour*52).dp),color=MaterialTheme.colorScheme.outlineVariant) }
                        blocks.forEach { block ->
                            val width=dayWidth/block.lanes
                            Surface(Modifier.offset(x=width*block.lane,y=(block.start*52f/60).dp).width(width).height(maxOf(22.dp,((block.end-block.start)*52f/60).dp)).padding(1.dp).clickable { onEdit(block.item,if(block.continuation) date.minusDays(1) else date) },color=if(block.item.status==ItemStatus.COMPLETED) MaterialTheme.colorScheme.surfaceVariant else if(block.item.kind==ItemKind.ALARM || block.item.isHabitTask) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(5.dp)) {
                                Column(Modifier.padding(3.dp)) {
                                    val time=if(block.continuation) "接前一天" else block.item.schedule?.time.orEmpty()
                                    if(block.end-block.start<50) Text("$time ${block.item.title}",fontSize=10.sp,lineHeight=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    else { Text(block.item.title,fontSize=10.sp,lineHeight=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis); Text(time,fontSize=9.sp) }
                                }
                            }
                        }
                    }
                }
            }
            Text("横向滑动看日期 · 点块编辑",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
