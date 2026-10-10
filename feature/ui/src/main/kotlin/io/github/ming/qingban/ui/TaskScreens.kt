package io.github.ming.qingban.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable fun TaskScreen(page: Int, deps: UiDependencies, onAssistant: () -> Unit, onSettings: () -> Unit, initialDate: LocalDate? = null, requestedView: String? = null, onFocus: (PlanItem?) -> Unit = {}) {
    val vm: ItemListViewModel = viewModel(key = "items:$page", factory = factory { ItemListViewModel(deps.services) })
    val all by vm.items.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now(deps.services.clock)) { while(true) { value=LocalDate.now(deps.services.clock); kotlinx.coroutines.delay(30_000) } }
    var day by rememberSaveable(stateSaver=Saver<LocalDate,String>(save={ it.toString() },restore={ LocalDate.parse(it) })) { mutableStateOf(initialDate ?: today) }
    var appliedInitialDate by rememberSaveable { mutableStateOf(initialDate?.toString()) }
    LaunchedEffect(today,page) { if(page==0) day=today }
    LaunchedEffect(initialDate,page) { if(page==1 && initialDate!=null && appliedInitialDate!=initialDate.toString()) { day=initialDate; appliedInitialDate=initialDate.toString() } }
    var completed by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    BackHandler(selecting) { selecting=false; selected=emptyList() }
    var deleting by remember { mutableStateOf<List<PlanItem>?>(null) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editDay by rememberSaveable { mutableStateOf<String?>(null) }
    val editing=all.firstOrNull { it.id==editingId }
    var createKind by rememberSaveable { mutableStateOf(if(page == 3) ItemKind.ALARM else if(page == 2) ItemKind.NOTE else ItemKind.TASK) }
    val context = LocalContext.current
    LaunchedEffect(result) { result?.let { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show(); if(it.status != "error") { editor=false; selected=emptyList(); selecting=false }; vm.consumeResult(it) } }
    val shown = when(page) {
        2 -> all.filter { (it.kind == ItemKind.NOTE || (it.kind == ItemKind.TASK && it.schedule?.date == null && it.schedule?.rangeStart == null && it.schedule?.recurrence == null)) && it.status != ItemStatus.CANCELLED && (search.isBlank() || search in it.title || search in it.detail) && (it.status == ItemStatus.COMPLETED) == completed }
        3 -> all.filter { it.kind == ItemKind.ALARM }.sortedBy { it.schedule?.time }
        else -> all.mapNotNull { dayOccurrence(it, day) }.filter { (it.status == ItemStatus.COMPLETED) == completed }
    }
    val copyTime=LocalCopyTime.current
    val overdue = if(page==0 && !completed) all.filter { it.kind==ItemKind.TASK && it.status==ItemStatus.OPEN && it.schedule?.recurrence==null && shown.none { current -> current.id==it.id } && (it.schedule?.date?.let { d -> LocalDate.parse(d).isBefore(today) }==true || it.deadline?.date?.let { d -> LocalDate.parse(d).isBefore(today) }==true) }.take(10) else emptyList()
    val selectable=(shown+overdue).distinctBy { it.id }
    LaunchedEffect(selectable.map { it.id },day) { selected=selected.filter { id -> selectable.any { it.id==id } } }
    fun toggle(item: PlanItem) { selected=if(item.id in selected) selected-item.id else selected+item.id }
    val todayItems=all.filter { it.kind!=ItemKind.ALARM }.mapNotNull { dayOccurrence(it,today) }
    val progress=DayProgress(todayItems.count { it.status==ItemStatus.OPEN },todayItems.count { it.status==ItemStatus.COMPLETED })
    val headline=if(page==0) rotatingCopy("home.headline",CopyCatalog.homeHeadline(copyTime,progress)) else ""
    val support=if(page==0) rotatingCopy("home.support",CopyCatalog.homeSupport(progress)) else ""
    val cta=if(page==0) rotatingCopy("home.cta",CopyCatalog.homeCta) else ""
    val searchHint=if(page==2) rotatingCopy("memo.search",CopyCatalog.searchHint) else ""
    val alarmIntro=if(page==3) rotatingCopy("alarm.intro",CopyCatalog.alarmIntro) else ""
    val searching=page==2 && search.isNotBlank()
    val emptyTitle=rotatingCopy("task.empty.$page",CopyCatalog.emptyTitle(page,completed,searching,day,today),day.toString())
    val emptyHint=rotatingCopy("task.emptyHint.$page",CopyCatalog.emptyHint(searching))
    fun edit(item: PlanItem) { editingId=item.id; editDay=day.toString(); createKind = item.kind; editor = true }
    fun add() { editingId = null; editDay=day.toString(); createKind = if(page == 3) ItemKind.ALARM else if(page == 2) ItemKind.NOTE else ItemKind.TASK; editor = true }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if(selecting) stickyHeader { Surface(color=MaterialTheme.colorScheme.background) { ItemSelectionBar(selecting,selected.size,selectable.size,{ selecting=true },{ selected=selectable.map { it.id } },{ selected=emptyList() },{ selecting=false; selected=emptyList() },{ deleting=all.filter { it.id in selected } },{ vm.undo() }) } }
        if(page == 0) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))).padding(start = 22.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SoftTag("${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value-1]}")
                        Text(headline,style=MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                        Text(support, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp))
                        TextButton(onClick = onAssistant, contentPadding = PaddingValues(top = 10.dp)) { Text(cta); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 6.dp).size(16.dp)) }
                    }
                    GptPet(modifier = Modifier.width(98.dp).height(160.dp), onClick = onAssistant)
                }
            }
        }
        if(page == 0) item { DailyReviewCard(deps) }
        if(page == 0) item { HabitTodayCard(deps) }
        if(page == 1) item { ScheduleCalendar(day, all, deps, requestedView, { day=it }) { item,date -> editingId=item.id; createKind=item.kind; editDay=date.toString(); editor=true } }
        if(page == 0) item { TextButton(onClick={ onFocus(null) },modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Timer,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始一段专注") } }
        if(page == 2) item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) },trailingIcon={ if(search.isNotEmpty()) IconButton(onClick={ search="" }) { Icon(Icons.Default.Close,"清除搜索") } }, placeholder = { Text(searchHint) }, singleLine = true, shape = RoundedCornerShape(18.dp)) }
        if(page == 3) item { Text(alarmIntro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)); if(all.any { it.delivery == DeliveryState.PERMISSION_REQUIRED }) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("有闹钟等待开启权限 · 去设置") } }
        item { SectionTitle(when(page) { 0 -> "今天的事情"; 1 -> "${day.monthValue}月${day.dayOfMonth}日"; 2 -> "随手备忘"; else -> "我的闹钟" }, if(page != 3) "${shown.size} 件${if(completed) "已完成" else "待处理"}" else "音乐与振动，按你的方式提醒", trailing = { IconButton(onClick = ::add) { Icon(Icons.Default.Add, "新增") } }) }
        if(page != 3) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!completed, { completed = false }, label = { Text("待办") }); FilterChip(completed, { completed = true }, label = { Text("已完成") }) } }
        if(!selecting) item { ItemSelectionBar(selecting,selected.size,selectable.size,{ selecting=true },{ selected=selectable.map { it.id } },{ selected=emptyList() },{ selecting=false; selected=emptyList() },{ deleting=all.filter { it.id in selected } },{ vm.undo() }) }
        if(shown.isEmpty()) item { EmptyCard(emptyTitle,emptyHint,::add) }
        if(page == 3) items(shown, key = { it.id }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else AlarmCard(item, { edit(item) }) { enabled -> vm.enableAlarm(item, enabled) } }
        else if(page in 0..1 && shown.isNotEmpty()) {
            item { DailyBoard(shown,{ edit(it) },{ vm.complete(it,day.toString()) },{ onFocus(it) },{ item,index -> vm.check(item,index,day.toString()) },if(selecting) selected.toSet() else null,::toggle) }
        } else {
            items(DependencyPlanner.ordered(shown), key = { it.id }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else ItemCard(item, { edit(item) }, { vm.complete(item, day.toString()) }, onPin = { vm.pin(item) }, prerequisites = item.dependsOn.mapNotNull { id -> all.firstOrNull { it.id == id }?.title }, onCheck={ vm.check(item,it,day.toString()) },onMove=if(page==2) { direction -> vm.move(shown,item.id,direction) } else null) }
        }
        if(page == 0 && !completed) {
            if(overdue.isNotEmpty()) { item { SectionTitle("还没处理的事情", "可以补做、改期，也可以放下") }; items(overdue, key = { "overdue:${it.id}" }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else ItemCard(item, { edit(item) }, { vm.complete(item, null) }, overdue = true) } }
        }
        item { OutlinedButton(onClick = onAssistant, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("一句话安排") } }
    }
    deleting?.let { targets -> DeleteItemsDialog(targets,day.toString().takeIf { page in 0..1 },{ deleting=null }) { scope -> vm.delete(targets,scope,day.toString().takeIf { page in 0..1 }); deleting=null } }
    if(editor && (editingId==null || editing!=null)) ItemEditor(createKind, editing, if(page in 0..1) editDay?.let(LocalDate::parse) ?: day else null, deps.platform, all, { editor = false }, { draft, scope, date -> vm.save(draft, editing, scope, date) }, { scope, date -> editing?.let { vm.cancel(it,scope,date) }; editor = false },onSplit={ vm.split(editing,it) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ItemCard(item: PlanItem, onEdit: () -> Unit, onComplete: () -> Unit, overdue: Boolean = false, onPin: (() -> Unit)? = null, prerequisites: List<String> = emptyList(), onCheck: ((Int) -> Unit)? = null, onMove: ((Int) -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if(item.kind != ItemKind.ALARM) Checkbox(item.status == ItemStatus.COMPLETED, { onComplete() }) else Icon(Icons.Default.Alarm, null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(item.title,style=MaterialTheme.typography.titleMedium)
                if(item.detail.isNotBlank()) Text(item.detail, maxLines = 2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if(prerequisites.isNotEmpty()) Text("先完成：${prerequisites.joinToString("、")}",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                item.checklist.forEachIndexed { index,entry -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(entry.completed,{ onCheck?.invoke(index) },enabled=onCheck!=null,modifier=Modifier.size(44.dp)); Text(entry.text,style=MaterialTheme.typography.bodySmall) } }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    SoftTag(if(overdue) item.schedule?.date ?: "待处理" else itemTime(item))
                    if(item.label.isNotBlank()) SoftTag(item.label, true)
                    item.deadline?.let { SoftTag("${it.date.substring(5)} 截止", true) }
                }
            }
            onPin?.let { IconButton(onClick=it,modifier=Modifier.size(44.dp)) { Icon(Icons.Default.PushPin,if(item.pinned) "取消置顶" else "置顶",tint=if(item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,modifier=Modifier.size(18.dp)) } }
            onMove?.let { move -> Column { IconButton(onClick={ move(-1) },modifier=Modifier.size(44.dp)) { Icon(Icons.Default.KeyboardArrowUp,"上移") }; IconButton(onClick={ move(1) },modifier=Modifier.size(44.dp)) { Icon(Icons.Default.KeyboardArrowDown,"下移") } } }
            Icon(Icons.Default.ChevronRight, "编辑", tint = MaterialTheme.colorScheme.outline)
        }
    }
}
@Composable private fun AlarmCard(item: PlanItem, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.schedule?.time ?: "未设时间", fontSize = 34.sp, fontWeight = FontWeight.Light)
                Text(item.title, fontWeight = FontWeight.Medium)
                val repeat = item.schedule?.recurrence?.let(RecurrenceRules::label)
                Text(repeat ?: "只响一次", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                val clock=java.time.Clock.systemDefaultZone()
                val calendarPending=item.delivery==DeliveryState.FAILED && ScheduleEngine.nextTrigger(item,clock)==null && RecurrenceRules.unavailableMessage(item,clock)!=null
                val delivery = when { item.status != ItemStatus.OPEN -> "已关闭"; item.delivery == DeliveryState.PERMISSION_REQUIRED -> "待开启权限"; item.delivery == DeliveryState.FAILED -> if(calendarPending) "法定日历待更新" else "调度失败"; item.delivery == DeliveryState.FIRED -> "本次已响铃"; item.delivery == DeliveryState.SCHEDULED -> "已启用"; else -> "无后续响铃" }
                Text("$delivery · ${if(item.alarm.headphonesOnly) "仅耳机／振动" else if(item.alarm.soundPath != null) "导入音乐" else "系统铃声"}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(item.status == ItemStatus.OPEN && item.delivery != DeliveryState.FIRED, onToggle)
        }
    }
}
