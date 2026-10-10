package io.github.ming.qingban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val snackbar=LocalSnackbar.current
    val uiScope=rememberCoroutineScope()
    // 撤销本身的结果不再提供“撤销”按钮
    var undoing by remember { mutableStateOf(false) }
    LaunchedEffect(result) { result?.let { r ->
        if(r.status != "error") { editor=false; selected=emptyList(); selecting=false }
        vm.consumeResult(r)
        val offerUndo=!undoing && r.status!="error" && r.changeId!=null
        undoing=false
        uiScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val action=snackbar.showSnackbar(r.message,actionLabel=if(offerUndo) "撤销" else null,withDismissAction=!offerUndo,duration=if(offerUndo) SnackbarDuration.Long else SnackbarDuration.Short)
            if(action==SnackbarResult.ActionPerformed) { undoing=true; vm.undo() }
        }
    } }
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
    fun startSelection(item: PlanItem) { selecting=true; selected=listOf(item.id) }
    val todayItems=all.filter { it.kind!=ItemKind.ALARM }.mapNotNull { dayOccurrence(it,today) }
    val progress=DayProgress(todayItems.count { it.status==ItemStatus.OPEN },todayItems.count { it.status==ItemStatus.COMPLETED })
    val headline=if(page==0) rotatingCopy("home.headline",CopyCatalog.homeHeadline(copyTime,progress)) else ""
    val support=if(page==0) rotatingCopy("home.support",CopyCatalog.homeSupport(progress)) else ""
    val searchHint=if(page==2) rotatingCopy("memo.search",CopyCatalog.searchHint) else ""
    val alarmIntro=if(page==3) rotatingCopy("alarm.intro",CopyCatalog.alarmIntro) else ""
    val searching=page==2 && search.isNotBlank()
    val emptyTitle=rotatingCopy("task.empty.$page",CopyCatalog.emptyTitle(page,completed,searching,day,today),day.toString())
    val emptyHint=rotatingCopy("task.emptyHint.$page",CopyCatalog.emptyHint(searching))
    fun edit(item: PlanItem) { editingId=item.id; editDay=day.toString(); createKind = item.kind; editor = true }
    fun add() { editingId = null; editDay=day.toString(); createKind = if(page == 3) ItemKind.ALARM else if(page == 2) ItemKind.NOTE else ItemKind.TASK; editor = true }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = QSpace.page, end = QSpace.page, top = QSpace.xs, bottom = QSpace.fabClearance), verticalArrangement = Arrangement.spacedBy(QSpace.m)) {
        if(selecting) stickyHeader { Surface(color=MaterialTheme.colorScheme.background) { ItemSelectionBar(true,selected.size,selectable.size,{ selecting=true },{ selected=selectable.map { it.id } },{ selected=emptyList() },{ selecting=false; selected=emptyList() },{ deleting=all.filter { it.id in selected } },{ vm.undo() }) } }
        if(page == 0) item { TodayHero(today,headline,support,progress,onAssistant) }
        if(page == 0) item { DailyReviewCard(deps) }
        if(page == 0) item { HabitTodayCard(deps) }
        if(page == 1) item { ScheduleCalendar(day, all, deps, requestedView, { day=it }) { item,date -> editingId=item.id; createKind=item.kind; editDay=date.toString(); editor=true } }
        if(page == 2) item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) },trailingIcon={ if(search.isNotEmpty()) IconButton(onClick={ search="" }) { Icon(Icons.Default.Close,"清除搜索") } }, placeholder = { Text(searchHint) }, singleLine = true, shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)) }
        if(page == 3) item {
            Text(alarmIntro, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = QSpace.xs))
            if(all.any { it.delivery == DeliveryState.PERMISSION_REQUIRED }) QCard(Modifier.fillMaxWidth().padding(top = QSpace.m), onClick = onSettings, color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(QSpace.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
                    Text("有闹钟等待开启权限", Modifier.weight(1f).padding(start = QSpace.m), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
                    Text("去设置", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item { SectionTitle(when(page) { 0 -> "今天的事情"; 1 -> "${day.monthValue}月${day.dayOfMonth}日"; 2 -> "随手备忘"; else -> "我的闹钟" }, if(page != 3) "${shown.size} 件${if(completed) "已完成" else "待处理"} · 长按可多选" else "音乐与振动，按你的方式提醒 · 长按可多选", trailing = { FilledTonalIconButton(onClick = ::add) { Icon(Icons.Default.Add, "新增") } }) }
        if(page != 3) item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "待办", true to "已完成").forEachIndexed { index, (value, label) ->
                    SegmentedButton(completed == value, { completed = value }, SegmentedButtonDefaults.itemShape(index, 2), colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer, inactiveContainerColor = MaterialTheme.colorScheme.surface)) { Text(label) }
                }
            }
        }
        if(shown.isEmpty()) item { EmptyCard(emptyTitle,emptyHint,::add) }
        if(page == 3) items(shown, key = { it.id }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else AlarmCard(item, { edit(item) }, { startSelection(item) }) { enabled -> vm.enableAlarm(item, enabled) } }
        else if(page in 0..1 && shown.isNotEmpty()) {
            item { DailyBoard(shown,{ edit(it) },{ vm.complete(it,day.toString()) },{ onFocus(it) },{ item,index -> vm.check(item,index,day.toString()) },if(selecting) selected.toSet() else null,::toggle,onLongPress=::startSelection) }
        } else {
            items(DependencyPlanner.ordered(shown), key = { it.id }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else ItemCard(item, { edit(item) }, { vm.complete(item, day.toString()) }, onLongPress = { startSelection(item) }, onPin = { vm.pin(item) }, prerequisites = item.dependsOn.mapNotNull { id -> all.firstOrNull { it.id == id }?.title }, onCheck={ vm.check(item,it,day.toString()) },onMove=if(page==2) { direction -> vm.move(shown,item.id,direction) } else null) }
        }
        if(page == 0 && !completed && overdue.isNotEmpty()) {
            item { SectionTitle("还没处理的事情", "可以补做、改期，也可以放下") }
            items(overdue, key = { "overdue:${it.id}" }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { toggle(item) } else ItemCard(item, { edit(item) }, { vm.complete(item, null) }, onLongPress = { startSelection(item) }, overdue = true) }
        }
    }
    deleting?.let { targets -> DeleteItemsDialog(targets,day.toString().takeIf { page in 0..1 },{ deleting=null }) { scope -> vm.delete(targets,scope,day.toString().takeIf { page in 0..1 }); deleting=null } }
    if(editor && (editingId==null || editing!=null)) ItemEditor(createKind, editing, if(page in 0..1) editDay?.let(LocalDate::parse) ?: day else null, deps.platform, all, { editor = false }, { draft, scope, date -> vm.save(draft, editing, scope, date) }, { scope, date -> editing?.let { vm.cancel(it,scope,date) }; editor = false },onSplit={ vm.split(editing,it) })
}

@Composable private fun TodayHero(today: LocalDate, headline: String, support: String, progress: DayProgress, onAssistant: () -> Unit) {
    val total=progress.pending+progress.completed
    QCard(Modifier.fillMaxWidth(), brush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))) {
        Row(Modifier.fillMaxWidth().padding(start = QSpace.xl, top = QSpace.l, bottom = QSpace.l, end = QSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value-1]}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = QSpace.s))
                Text(support, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = QSpace.m), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { if(total==0) 0f else progress.completed.toFloat()/total }, modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .7f), drawStopIndicator = {})
                    Text("${progress.completed}/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = QSpace.s))
                }
            }
            GptPet(modifier = Modifier.padding(start = QSpace.s).width(92.dp).height(150.dp), onClick = onAssistant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ItemCard(item: PlanItem, onEdit: () -> Unit, onComplete: () -> Unit, onLongPress: () -> Unit, overdue: Boolean = false, onPin: (() -> Unit)? = null, prerequisites: List<String> = emptyList(), onCheck: ((Int) -> Unit)? = null, onMove: ((Int) -> Unit)? = null) {
    val done=item.status == ItemStatus.COMPLETED
    QCard(Modifier.fillMaxWidth(), onClick = onEdit, onLongClick = onLongPress) {
        Row(Modifier.padding(start = QSpace.xs, end = QSpace.s, top = QSpace.m, bottom = QSpace.m), verticalAlignment = Alignment.Top) {
            if(item.kind != ItemKind.ALARM) RoundCheck(done, onComplete, "完成事项：${item.title}") else Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Alarm, null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(top = 10.dp, start = QSpace.xs)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = if(done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, textDecoration = if(done) TextDecoration.LineThrough else null)
                if(item.detail.isNotBlank()) Text(item.detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                if(prerequisites.isNotEmpty()) Text("先完成：${prerequisites.joinToString("、")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 2.dp))
                item.checklist.forEachIndexed { index, entry -> ChecklistRow(entry.text, entry.completed, onCheck?.let { cb -> { cb(index) } }) }
                FlowRow(Modifier.padding(top = QSpace.s), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SoftTag(if(overdue) item.schedule?.date ?: "待处理" else itemTime(item))
                    if(item.label.isNotBlank()) SoftTag(item.label, true)
                    item.deadline?.let { SoftTag("${it.date.substring(5)} 截止", true) }
                }
            }
            onPin?.let { IconButton(onClick = it, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PushPin, if(item.pinned) "取消置顶" else "置顶", tint = if(item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp)) } }
            onMove?.let { move -> Column { IconButton(onClick = { move(-1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowUp, "上移", tint = MaterialTheme.colorScheme.outline) }; IconButton(onClick = { move(1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.KeyboardArrowDown, "下移", tint = MaterialTheme.colorScheme.outline) } } }
        }
    }
}

/** Compact checklist entry shared by item cards and the daily board. */
@Composable internal fun ChecklistRow(text: String, checked: Boolean, onToggle: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle?.invoke() }, enabled = onToggle != null, modifier = Modifier.size(36.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if(checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, textDecoration = if(checked) TextDecoration.LineThrough else null)
    }
}

@Composable private fun AlarmCard(item: PlanItem, onEdit: () -> Unit, onLongPress: () -> Unit, onToggle: (Boolean) -> Unit) {
    val active=item.status == ItemStatus.OPEN && item.delivery != DeliveryState.FIRED
    QCard(Modifier.fillMaxWidth(), onClick = onEdit, onLongClick = onLongPress) {
        Row(Modifier.padding(horizontal = QSpace.xl, vertical = QSpace.l), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.schedule?.time ?: "未设时间", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light, color = if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                val repeat = item.schedule?.recurrence?.let(RecurrenceRules::label)
                val clock=java.time.Clock.systemDefaultZone()
                val calendarPending=item.delivery==DeliveryState.FAILED && ScheduleEngine.nextTrigger(item,clock)==null && RecurrenceRules.unavailableMessage(item,clock)!=null
                val delivery = when { item.status != ItemStatus.OPEN -> "已关闭"; item.delivery == DeliveryState.PERMISSION_REQUIRED -> "待开启权限"; item.delivery == DeliveryState.FAILED -> if(calendarPending) "法定日历待更新" else "调度失败"; item.delivery == DeliveryState.FIRED -> "本次已响铃"; item.delivery == DeliveryState.SCHEDULED -> "已启用"; else -> "无后续响铃" }
                val problem=item.delivery == DeliveryState.PERMISSION_REQUIRED || (item.delivery == DeliveryState.FAILED && !calendarPending)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoftTag(repeat ?: "只响一次")
                    SoftTag(if(item.alarm.headphonesOnly) "仅耳机／振动" else if(item.alarm.soundPath != null) "导入音乐" else "系统铃声", true)
                }
                Text(delivery, style = MaterialTheme.typography.labelSmall, color = if(problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(active, onToggle)
        }
    }
}
