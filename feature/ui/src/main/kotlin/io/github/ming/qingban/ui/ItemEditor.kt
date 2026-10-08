package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.ming.qingban.domain.*
import java.time.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ItemEditor(kind: ItemKind, item: PlanItem?, initialDay: LocalDate?, platform: PlatformActions, allItems: List<PlanItem>, onDismiss: () -> Unit, onSave: (ItemDraft, EditScope, String?) -> Unit, onCancel: (EditScope, String?) -> Unit, onSplit: ((List<ItemDraft>)->Unit)? = null) {
    val initialSchedule=if(item?.schedule?.recurrence!=null && initialDay!=null) item.overrides.firstOrNull { it.originalDate==initialDay.toString() || it.schedule?.date==initialDay.toString() }?.schedule ?: item.schedule else item?.schedule
    val occurrenceChecklist=if(item!=null && initialDay!=null) ScheduleEngine.onDate(item,initialDay)?.checklist ?: item.checklist else item?.checklist
    val stateKey=item?.id ?: "new:$kind"
    var title by rememberSaveable(stateKey) { mutableStateOf(item?.title ?: "") }
    var detail by rememberSaveable(stateKey) { mutableStateOf(item?.detail ?: "") }
    val editorClock=Clock.systemDefaultZone()
    val nextOccurrence=if(kind==ItemKind.ALARM && item?.schedule?.recurrence!=null) runCatching {
        ScheduleEngine.nextTrigger(item.copy(status=ItemStatus.OPEN),editorClock)?.atZone(editorClock.zone)?.toLocalDate()?.toString()
    }.getOrNull() else null
    val originalOccurrence=nextOccurrence?.let { next -> item?.overrides?.firstOrNull { it.schedule?.date==next }?.originalDate ?: next }
    var date by rememberSaveable(stateKey) { mutableStateOf(if(initialSchedule?.rangeStart!=null) "" else initialSchedule?.date ?: initialDay?.toString() ?: nextOccurrence ?: "") }
    var time by rememberSaveable(stateKey) { mutableStateOf(initialSchedule?.time ?: if(kind == ItemKind.ALARM) "08:00" else "") }
    var alarmTimeChanged by rememberSaveable(stateKey) { mutableStateOf(false) }
    var period by rememberSaveable(stateKey) { mutableStateOf(initialSchedule?.period ?: "") }
    var deadline by rememberSaveable(stateKey) { mutableStateOf(item?.deadline?.date ?: "") }
    var deadlineTime by rememberSaveable(stateKey) { mutableStateOf(item?.deadline?.time ?: "") }
    var rangeStart by rememberSaveable(stateKey) { mutableStateOf(item?.schedule?.rangeStart ?: "") }
    var rangeEnd by rememberSaveable(stateKey) { mutableStateOf(item?.schedule?.rangeEnd ?: "") }
    var reminderAt by rememberSaveable(stateKey) { mutableStateOf(item?.reminder?.atLocal ?: "") }
    var reminderOffset by rememberSaveable(stateKey) { mutableStateOf(item?.reminder?.offsetMinutes?.toString() ?: "0") }
    var label by rememberSaveable(stateKey) { mutableStateOf(item?.label ?: "") }
    var remind by rememberSaveable(stateKey) { mutableStateOf(item?.reminder != null) }
    var repeat by rememberSaveable(stateKey) { mutableStateOf(item?.schedule?.recurrence != null) }
    var repeatRule by rememberSaveable(stateKey, stateSaver=RecurrenceSaver) { mutableStateOf(item?.schedule?.recurrence ?: RecurrenceSpec(weekdays=setOf(1,2,3,4,5),startDate=LocalDate.now().toString())) }
    var chooseRepeat by rememberSaveable(stateKey) { mutableStateOf(false) }
    var vibrate by rememberSaveable(stateKey) { mutableStateOf(item?.alarm?.vibrate ?: true) }
    var playAudio by rememberSaveable(stateKey) { mutableStateOf(item?.alarm?.playAudio ?: true) }
    var headphones by rememberSaveable(stateKey) { mutableStateOf(item?.alarm?.headphonesOnly ?: false) }
    var sound by rememberSaveable(stateKey) { mutableStateOf(item?.alarm?.soundPath) }
    var more by rememberSaveable(stateKey) { mutableStateOf(kind == ItemKind.ALARM) }
    var pickDate by rememberSaveable(stateKey) { mutableStateOf(false) }
    var error by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var acknowledged by rememberSaveable(stateKey) { mutableStateOf<Int?>(null) }
    var editScope by rememberSaveable(stateKey) { mutableStateOf(if(item?.schedule?.recurrence!=null && initialDay!=null) EditScope.THIS_OCCURRENCE else EditScope.WHOLE_SERIES) }
    var occurrence by rememberSaveable(stateKey) { mutableStateOf(initialDay?.toString() ?: originalOccurrence ?: item?.schedule?.recurrence?.startDate ?: "") }
    var duration by rememberSaveable(stateKey) { mutableStateOf(item?.durationMinutes?.toString() ?: "") }
    var dependencies by rememberSaveable(stateKey) { mutableStateOf(item?.dependsOn ?: emptySet()) }
    var chooseMusic by rememberSaveable(stateKey) { mutableStateOf(false) }
    var pinned by rememberSaveable(stateKey) { mutableStateOf(item?.pinned ?: false) }
    var snooze by rememberSaveable(stateKey) { mutableStateOf(item?.alarm?.snoozeMinutes?.toString() ?: "5") }
    var checklist by rememberSaveable(stateKey) { mutableStateOf(occurrenceChecklist?.joinToString("\n") { it.text } ?: "") }
    var showSplit by rememberSaveable(stateKey) { mutableStateOf(false) }
    val splitPreview=runCatching { PlanBlocks.split(ItemDraft(kind,title,detail,ScheduleSpec(date=date.takeIf { it.isNotBlank() },time=time.takeIf { it.isNotBlank() },recurrence=repeatRule.takeIf { repeat }),item?.deadline,item?.reminder,item?.alarm ?: AlarmSpec(),label,dependencies,duration.toIntOrNull(),pinned,item?.order ?: 0)) }.getOrNull()
    val detailHint=rotatingCopy("editor.detail",CopyCatalog.detailHint,stateKey)
    DisposableEffect(Unit) { onDispose { platform.stopPreview() } }
    val coroutine = rememberCoroutineScope()
    val music = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { coroutine.launch { runCatching { platform.importMusic(it.toString()) }.onSuccess { path -> sound = path }.onFailure { e -> error = e.message } } } }
    // Preview and persistence use the same rule, including its effective date and cycle anchor.
    val recurrence=repeatRule.takeIf { repeat && editScope!=EditScope.THIS_OCCURRENCE }?.let { rule ->
        if(editScope==EditScope.THIS_AND_FUTURE && occurrence.isNotBlank()) rule.copy(startDate=occurrence) else rule
    }
    fun alarmSchedule(): ScheduleSpec {
        if(recurrence!=null) return ScheduleSpec(time=time,recurrence=recurrence)
        // Preserve existing dated alarms when only editing their sound/title or a series occurrence.
        val keepDate=date.takeIf { item!=null && it.isNotBlank() && (!alarmTimeChanged || editScope==EditScope.THIS_OCCURRENCE) }
        return AlarmSchedules.nextOneShot(time,Clock.systemDefaultZone(),keepDate)
    }
    EditorDialog(kind,onDismiss, title = { Text(if(item != null) "编辑${if(kind == ItemKind.ALARM) "闹钟" else "事项"}" else when(kind) { ItemKind.NOTE -> "记一条备忘"; ItemKind.TASK -> "安排一件事"; ItemKind.ALARM -> "设一个闹钟" }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(item?.schedule?.recurrence != null) {
                Text("修改范围")
                FlowRow { listOf(EditScope.THIS_OCCURRENCE to "仅本次",EditScope.THIS_AND_FUTURE to "本次及以后",EditScope.WHOLE_SERIES to "整个系列").forEach { (s,label) -> FilterChip(editScope==s,{ editScope=s },label={ Text(label) }) } }
                if(editScope != EditScope.WHOLE_SERIES && kind!=ItemKind.ALARM) DateWheelField(occurrence,"原发生日期",false) { occurrence=it }
                if(editScope == EditScope.THIS_OCCURRENCE) Text(if(kind==ItemKind.ALARM) "仅修改这一次响铃，其余各次保持系列设置。" else "仅本次保存日期、时间和清单，其余设置保持系列值。")
            }
            EditorSection("内容与分类") {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(),label = { Text("名称") }, singleLine = true)
            OutlinedTextField(detail, { detail = it }, Modifier.fillMaxWidth(),label = { Text("补充内容（可选）") }, placeholder={ Text(detailHint) }, minLines = 2,maxLines=6)
            if(onSplit!=null && splitPreview!=null) OutlinedButton(onClick={ showSplit=true },modifier=Modifier.fillMaxWidth()) { Text("拆成 ${splitPreview.size} 个独立任务") }
            if(kind!=ItemKind.ALARM) {
                OutlinedTextField(label,{ label=it },Modifier.fillMaxWidth(),label={ Text("分栏／分类（可选）") },singleLine=true)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("学习工作","习惯").forEach { category -> FilterChip(if(category=="习惯") repeat && kind==ItemKind.TASK else !repeat && label==category,{ label=category; if(kind==ItemKind.TASK) { repeat=category=="习惯"; if(repeat) { repeatRule=RecurrenceSpec(weekdays=(1..7).toSet(),startDate=(initialDay ?: LocalDate.now()).toString()); rangeStart=""; rangeEnd=""; chooseRepeat=true } } },enabled=editScope!=EditScope.THIS_OCCURRENCE,label={ Text(category) }) } }
                Row { Text("置顶",Modifier.weight(1f)); Switch(pinned,{ pinned=it }) }
            }
            }
            EditorSection(if(kind==ItemKind.ALARM) "响铃时间" else "开始与结束") {
            if(kind == ItemKind.NOTE && date.isBlank()) TextButton(onClick={ pickDate=true }) { Text("安排到某一天") }
            if(kind != ItemKind.NOTE || date.isNotBlank()) {
                if(kind!=ItemKind.ALARM && (!repeat || editScope==EditScope.THIS_OCCURRENCE)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, Modifier.weight(1f)) { Text(if(date.isBlank()) "选择日期" else date.substring(5).replace('-', '月') + "日") }
                    if(kind != ItemKind.ALARM) TextButton(onClick = { date = ""; time = ""; period = ""; rangeStart=""; rangeEnd=""; repeat=false }) { Text("暂不安排") }
                }
                if(kind == ItemKind.ALARM) {
                    TimeWheels(time) { time=it; period=""; alarmTimeChanged=true }
                    if(editScope!=EditScope.THIS_OCCURRENCE) OutlinedButton(onClick={ chooseRepeat=true },modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("重复"); Text("${RecurrenceRules.label(recurrence)}  ›") }
                    }
                    val previewSchedule=alarmSchedule()
                    val next=runCatching { ScheduleEngine.nextTrigger(PlanItem("preview",kind=ItemKind.ALARM,title="预览",schedule=previewSchedule),Clock.systemDefaultZone()) }.getOrNull()
                    Text(next?.let {
                        val local=it.atZone(ZoneId.systemDefault()); val minutes=Duration.between(Instant.now(),it).toMinutes()
                        val days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),local.toLocalDate())
                        val dayLabel=when(days) { 0L -> "今天"; 1L -> "明天"; else -> "${days}天后" }
                        val countdown=if(minutes<1) "不到1分钟后" else "${if(minutes>=1440) "${minutes/1440}天 " else ""}${(minutes%1440)/60}小时 ${minutes%60}分钟后"
                        "下次：$dayLabel ${local.toLocalTime()} · $countdown"
                    } ?: "没有后续响铃，请调整时间或重复规则",color=MaterialTheme.colorScheme.primary)
                    if(recurrence!=null && RecurrenceRules.usesHolidayCalendar(recurrence)) Text("${MainlandHolidayCalendar.COVERAGE} · 后续年份需更新",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } else TimeWheelField(time,if(kind==ItemKind.TASK) "开始时间" else "具体时间") { time=it; if(it.isNotBlank()) { period=""; if(date.isBlank()) date=(initialDay ?: LocalDate.now()).toString() } }
                if(kind==ItemKind.TASK && time.isNotBlank() && editScope!=EditScope.THIS_OCCURRENCE) {
                    TaskEndWheelField(date.ifBlank { recurrence?.startDate ?: LocalDate.now().toString() },time,duration) { duration=it }
                    MinutesWheelField(duration,"预计时长") { duration=it }
                }
                if(kind != ItemKind.ALARM && time.isBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("morning", "afternoon", "evening").forEach { p -> FilterChip(period == p, { period = if(period == p) "" else p }, label = { Text(periodLabel(p)) }) } }
            }
            if(kind==ItemKind.TASK && repeat) Text("长期习惯 · 完成仅作用于当天，之后按重复规则继续出现。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
            if(kind == ItemKind.TASK && editScope!=EditScope.THIS_OCCURRENCE) OutlinedButton(onClick={ chooseRepeat=true },modifier=Modifier.fillMaxWidth()) { Text("重复：${if(repeat) RecurrenceRules.label(recurrence) else "不重复"}  ›") }
            }
            if(kind != ItemKind.ALARM) EditorSection("提醒") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("到点通知"); Switch(remind, { remind = it }) }
                if(remind) { DateTimeWheelField(reminderAt,"独立提醒") { reminderAt=it }; if(reminderAt.isBlank()) MinutesWheelField(reminderOffset,"提前提醒",0..10080,false) { reminderOffset=it } }
            }
            if(kind==ItemKind.TASK) EditorSection("行动清单") {
                Text("不同时间段请分别创建任务；这里填写这件事的检查步骤。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(checklist,{ checklist=it },Modifier.fillMaxWidth(),label={ Text("清单（可选，每行一项）") },minLines=2,maxLines=6)
                if(time.isBlank()) MinutesWheelField(duration,"预计时长") { duration=it }
            }
            TextButton(onClick = { more = !more }) { Text(if(more) "收起更多设置" else "更多设置") }
            if(more) EditorSection(if(kind==ItemKind.ALARM) "铃声与提醒方式" else "截止与先后关系") {
                if(kind != ItemKind.ALARM) {
                    Text("先完成哪些事（可选）")
                    allItems.filter { it.id != item?.id && (it.status!=ItemStatus.CANCELLED || it.id in dependencies) && it.kind != ItemKind.ALARM }.take(30).forEach { prior -> Row { Checkbox(prior.id in dependencies,{ checked -> dependencies=if(checked) dependencies+prior.id else dependencies-prior.id }); Text(prior.title + if(prior.status==ItemStatus.COMPLETED) "（已完成）" else if(prior.status==ItemStatus.CANCELLED) "（已取消）" else "",Modifier.padding(top=12.dp)) } }
                }
                if(kind != ItemKind.ALARM) { DateWheelField(deadline,"截止日期") { deadline=it; if(it.isBlank()) deadlineTime="" }; TimeWheelField(deadlineTime,"截止时间") { deadlineTime=it; if(it.isNotBlank() && deadline.isBlank()) deadline=LocalDate.now().toString() } }
                if(kind != ItemKind.ALARM && !repeat) { DateWheelField(rangeStart,"弹性日期范围：开始") { rangeStart=it; if(it.isNotBlank()) { date=""; time="" } }; DateWheelField(rangeEnd,"弹性日期范围：结束") { rangeEnd=it } }
                if(kind == ItemKind.ALARM) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("播放音乐"); Switch(playAudio, { playAudio = it }) }
                    OutlinedButton(onClick = { music.launch(arrayOf("audio/*")) }) { Text(if(sound == null) "导入喜欢的音乐" else "音乐已导入 · 换一首") }
                    OutlinedButton(onClick={ chooseMusic=true }) { Text("铃声 · 网易云选歌／已缓存音乐") }
                    Row { TextButton(onClick={ runCatching { platform.previewMusic(sound,headphones) }.onFailure { error=it.message } }) { Text("试听 15 秒") }; TextButton(onClick=platform::stopPreview) { Text("停止试听") } }
                    MinutesWheelField(snooze,"稍后提醒",1..60,false) { snooze=it }
                    if(sound != null) TextButton(onClick = { sound = null }) { Text("恢复系统铃声") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("同时振动"); Switch(vibrate, { vibrate = it }) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("仅耳机播放"); Switch(headphones, { headphones = it }) }
                    if(headphones) Text("没连耳机或耳机断开时改为振动", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        try {
            val preserved = if(kind==ItemKind.ALARM) alarmSchedule() else if(date.isBlank() && time.isBlank() && period.isBlank() && rangeStart.isBlank() && rangeEnd.isBlank() && !repeat) null else ScheduleSpec(if(recurrence==null) date.takeIf { it.isNotBlank() } else null, time.takeIf { it.isNotBlank() }, period.takeIf { it.isNotBlank() },rangeStart.takeIf { it.isNotBlank() && !repeat },rangeEnd.takeIf { it.isNotBlank() && !repeat }, recurrence = recurrence)
            require(duration.isBlank() || duration.toIntOrNull()!=null) { "时长需填写整数分钟" }; require(snooze.toIntOrNull()!=null) { "稍后提醒需填写整数分钟" }
            val entries=checklist.lines().map { it.trim() }.filter { it.isNotBlank() }.map { line -> occurrenceChecklist?.firstOrNull { it.text==line } ?: ChecklistEntry(line) }
            val reminder=if(remind && kind!=ItemKind.ALARM) { if(reminderAt.isNotBlank()) ReminderSpec(atLocal=reminderAt) else ReminderSpec(offsetMinutes=reminderOffset.toIntOrNull() ?: error("提前分钟数需填写整数")) } else null
            val draft = ItemDraft(kind, title, detail, preserved, if(deadline.isBlank()) null else DeadlineSpec(deadline,deadlineTime.takeIf { it.isNotBlank() }), reminder, AlarmSpec(sound, vibrate, headphones,snooze.toInt(), playAudio), label,dependencies,duration.toIntOrNull(),pinned,item?.order ?: 0,entries)
            require(PlanBlocks.split(draft)==null) { "多个时段请点击“拆成独立任务”，或分别创建任务" }
            val validation = ScheduleEngine.validate(draft)
            if(validation != null) error = validation else {
                val conflicts=DependencyPlanner.conflicts(PlanItem("preview",kind=kind,title=title,schedule=preserved,durationMinutes=draft.durationMinutes),allItems.filter { it.id!=item?.id })
                val late=preserved?.date?.let { d -> draft.deadline?.date?.let { d>it } } == true
                val warnings=listOfNotNull(if(conflicts.isNotEmpty()) "同时间还有：${conflicts.joinToString("、")}" else null,if(late) "新安排晚于原截止要求，截止日期会保留" else null)
                if(warnings.isNotEmpty() && acknowledged!=draft.hashCode()) { acknowledged=draft.hashCode(); error=warnings.joinToString("；")+"。再次点击保存可保留这个安排。" } else onSave(draft,editScope,occurrence.takeIf { it.isNotBlank() })
            }
        } catch(e: Exception) { error = e.message }
    }) { Text("保存") } }, dismissButton = { Row { if(item!=null) TextButton(onClick={ onCancel(editScope,occurrence.takeIf { it.isNotBlank() }) }) { Text("取消事项") }; TextButton(onClick = onDismiss) { Text("返回") } } })
    if(chooseMusic) MusicPicker(platform,{ chooseMusic=false },{ sound=it; chooseMusic=false })
    if(showSplit && splitPreview!=null && onSplit!=null) AlertDialog(onDismissRequest={ showSplit=false },title={ Text("确认拆分任务") },text={ Column(Modifier.verticalScroll(rememberScrollState())) { Text("原任务归档，新任务可以分别完成、改期和专注；整批可撤销。"); splitPreview.forEach { d -> Text("${d.schedule?.time} · ${d.title} · ${d.durationMinutes} 分钟",Modifier.padding(vertical=8.dp)); HorizontalDivider() } } },confirmButton={ Button(onClick={ onSplit(splitPreview); showSplit=false }) { Text("拆分并保存") } },dismissButton={ TextButton(onClick={ showSplit=false }) { Text("返回") } })
    if(chooseRepeat) AlarmRepeatPicker(recurrence,{ chooseRepeat=false },showDateSettings=kind!=ItemKind.ALARM,isAlarm=kind==ItemKind.ALARM) { rule ->
        if(rule==null && repeat && kind==ItemKind.ALARM) {
            date=""; alarmTimeChanged=true
        }
        repeat=rule!=null; if(rule!=null) { repeatRule=rule; rangeStart=""; rangeEnd="" }; chooseRepeat=false; error=null
    }
    if(pickDate && kind!=ItemKind.ALARM) {
        var selected by rememberSaveable { mutableStateOf(date.ifBlank { (initialDay ?: LocalDate.now()).toString() }) }
        AlertDialog(onDismissRequest={ pickDate=false },title={ Text("安排日期") },text={ Column { Row { TextButton(onClick={ selected=LocalDate.now().toString() }) { Text("今天") }; TextButton(onClick={ selected=LocalDate.now().plusDays(1).toString() }) { Text("明天") } }; DateWheels(selected) { selected=it } } },confirmButton={ TextButton(onClick={ date=selected; rangeStart=""; rangeEnd=""; pickDate=false }) { Text("确定") } },dismissButton={ TextButton(onClick={ pickDate=false }) { Text("返回") } })
    }
}
