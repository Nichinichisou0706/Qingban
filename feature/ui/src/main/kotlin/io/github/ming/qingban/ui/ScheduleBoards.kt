package io.github.ming.qingban.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import java.time.*

/** Day list grouped by period: 上午 / 下午 / 晚上 / 待安排; habits are marked with a lavender accent instead of a separate column. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun DailyBoard(items: List<PlanItem>, onEdit: (PlanItem) -> Unit, onComplete: (PlanItem) -> Unit, onFocus: (PlanItem) -> Unit, onCheck: (PlanItem,Int) -> Unit, selected: Set<String>? = null, onSelect: (PlanItem) -> Unit = {}, onLongPress: (PlanItem) -> Unit = {}) {
    fun bucket(item: PlanItem): Int = item.schedule?.time?.let { val hour=it.substringBefore(':').toIntOrNull() ?: 0; if(hour<12) 0 else if(hour<18) 1 else 2 } ?: when(item.schedule?.period) { "morning" -> 0; "afternoon" -> 1; "evening" -> 2; else -> 3 }
    val labels=listOf("上午","下午","晚上","待安排")
    val groups=items.groupBy(::bucket).toSortedMap()
    Column(verticalArrangement=Arrangement.spacedBy(QSpace.s)) {
        groups.forEach { (key,group) ->
            PeriodHeader(labels[key],group.size)
            DependencyPlanner.ordered(group).sortedBy { it.schedule?.time ?: "99" }.forEach { item -> BoardRow(item,onEdit,onComplete,onFocus,onCheck,selected,onSelect,onLongPress) }
        }
        Text(if(selected!=null) "勾选要删除的事项" else "重复习惯只完成当天这次 · 长按可多选",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=QSpace.xs))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BoardRow(item: PlanItem, onEdit: (PlanItem) -> Unit, onComplete: (PlanItem) -> Unit, onFocus: (PlanItem) -> Unit, onCheck: (PlanItem,Int) -> Unit, selected: Set<String>?, onSelect: (PlanItem) -> Unit, onLongPress: (PlanItem) -> Unit) {
    val done=item.status==ItemStatus.COMPLETED
    val selecting=selected!=null
    val accent=if(item.isHabitTask) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    QCard(Modifier.fillMaxWidth(),onClick={ if(selecting) onSelect(item) else onEdit(item) },onLongClick={ if(!selecting) onLongPress(item) },color=if(selecting && item.id in selected!!) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(if(done) MaterialTheme.colorScheme.outlineVariant else accent))
            Row(Modifier.weight(1f).padding(end=QSpace.s,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.Top) {
                if(selecting || item.kind!=ItemKind.ALARM) RoundCheck(if(selecting) item.id in selected!! else done,{ if(selecting) onSelect(item) else onComplete(item) },(if(selecting) "选择事项：" else "完成事项：")+item.title,tint=accent)
                else Spacer(Modifier.width(44.dp))
                Column(Modifier.weight(1f).padding(top=10.dp,bottom=4.dp)) {
                    Text(item.title,style=MaterialTheme.typography.titleSmall,color=if(done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,textDecoration=if(done) TextDecoration.LineThrough else null)
                    if(item.detail.isNotBlank()) Text(item.detail,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
                    if(item.dependsOn.isNotEmpty()) Text("有前置事项 · 点开查看",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                    if(!selecting) item.checklist.forEachIndexed { index,entry -> ChecklistRow(entry.text,entry.completed) { onCheck(item,index) } }
                    FlowRow(Modifier.padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(item.isHabitTask) SoftTag("习惯 · "+RecurrenceRules.label(item.schedule!!.recurrence!!),true)
                        if(item.label.isNotBlank() && item.label !in setOf("学习工作","习惯")) SoftTag(item.label,true)
                    }
                }
                Column(horizontalAlignment=Alignment.End,modifier=Modifier.padding(top=10.dp)) {
                    val range=item.schedule?.time?.let { time -> item.durationMinutes?.let { minutes -> val start=LocalTime.parse(time); val end=start.plusMinutes(minutes.toLong()); "$time–$end${if(start.hour*60+start.minute+minutes>=1440) "+1" else ""}" } ?: time } ?: item.schedule?.period?.let(::periodLabel).orEmpty()
                    Text(range,style=MaterialTheme.typography.labelMedium,color=if(done) MaterialTheme.colorScheme.outline else accent)
                    if(!selecting && item.kind!=ItemKind.ALARM && item.status==ItemStatus.OPEN) IconButton(onClick={ onFocus(item) },modifier=Modifier.size(36.dp)) { Icon(Icons.Default.Timer,"开始专注：${item.title}",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.outline) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScheduleCalendar(selected: LocalDate, items: List<PlanItem>, deps: UiDependencies, requestedView: String?, onSelect: (LocalDate) -> Unit, onEdit: (PlanItem,LocalDate) -> Unit) {
    var view by rememberSaveable { mutableStateOf("month") }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(requestedView) { view=requestedView ?: deps.services.sessions.read("calendar_view") ?: "month" }
    LaunchedEffect(selected) { monthText=YearMonth.from(selected).toString() }
    val month=YearMonth.parse(monthText)
    val today=LocalDate.now(deps.services.clock)
    QCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(QSpace.m),verticalArrangement=Arrangement.spacedBy(QSpace.s)) {
            val views=listOf("month" to "月","week" to "周","three_day" to "三日","day" to "日")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                views.forEachIndexed { index,(key,label) -> SegmentedButton(view==key,{ view=key; scope.launch { deps.services.sessions.write("calendar_view",key) } },SegmentedButtonDefaults.itemShape(index,views.size),icon={},colors=SegmentedButtonDefaults.colors(activeContainerColor=MaterialTheme.colorScheme.primaryContainer,inactiveContainerColor=MaterialTheme.colorScheme.surface)) { Text(label) } }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={ if(view=="month") monthText=month.minusMonths(1).toString() else onSelect(selected.minusDays(when(view) { "week" -> 7L; "three_day" -> 3L; else -> 1L })) }) { Icon(Icons.Default.ChevronLeft,"上一页日期") }
                Text(if(view=="month") "${month.year}年${month.monthValue}月" else "${selected.year}年${selected.monthValue}月${selected.dayOfMonth}日",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
                IconButton(onClick={ if(view=="month") monthText=month.plusMonths(1).toString() else onSelect(selected.plusDays(when(view) { "week" -> 7L; "three_day" -> 3L; else -> 1L })) }) { Icon(Icons.Default.ChevronRight,"下一页日期") }
            }
            if(selected!=today || (view=="month" && month!=YearMonth.from(today))) AssistChip(onClick={ onSelect(today); monthText=YearMonth.from(today).toString() },label={ Text("回到今天") },modifier=Modifier.align(Alignment.CenterHorizontally))
            if(view=="month") MonthEvents(month,selected,today,items,onSelect)
            else {
                val count=when(view) { "week" -> 7; "three_day" -> 3; else -> 1 }
                TimeGrid(DateWindows.centered(selected,count),selected,items,onSelect,onEdit)
            }
        }
    }
}
@Composable private fun MonthEvents(month: YearMonth, selected: LocalDate, today: LocalDate, items: List<PlanItem>, onSelect: (LocalDate) -> Unit) {
    Row { "一二三四五六日".forEach { Text(it.toString(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center) } }
    val start=month.atDay(1).minusDays(month.atDay(1).dayOfWeek.value-1L)
    val rows=(month.atDay(1).dayOfWeek.value-1+month.lengthOfMonth()+6)/7
    repeat(rows) { row -> Row(Modifier.fillMaxWidth().height(78.dp)) { repeat(7) { col ->
        val date=start.plusDays((row*7+col).toLong())
        val events=items.mapNotNull { ScheduleEngine.onDate(it,date) }.sortedBy { it.schedule?.time ?: "" }
        val isSelected=date==selected
        Column(Modifier.weight(1f).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(10.dp)).background(if(isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onSelect(date) }.padding(2.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if(date==today) MaterialTheme.colorScheme.primary else Color.Transparent),contentAlignment=Alignment.Center) {
                Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.labelMedium,color=when { date==today -> MaterialTheme.colorScheme.onPrimary; YearMonth.from(date)!=month -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.onSurface })
            }
            events.take(2).forEach { event -> Text(event.title,Modifier.fillMaxWidth().padding(top=2.dp).background(if(event.status==ItemStatus.COMPLETED) MaterialTheme.colorScheme.surfaceVariant else if(event.isHabitTask || event.kind==ItemKind.ALARM) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha=if(isSelected) 1f else .8f),RoundedCornerShape(4.dp)).padding(horizontal=2.dp,vertical=1.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=9.sp,lineHeight=12.sp) }
            if(events.size>2) Text("+${events.size-2}",style=MaterialTheme.typography.labelSmall,fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
