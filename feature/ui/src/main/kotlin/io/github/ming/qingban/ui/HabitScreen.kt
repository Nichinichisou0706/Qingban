package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable internal fun HabitTodayCard(deps: UiDependencies) {
    val state by deps.services.habits.state.collectAsStateWithLifecycle()
    val today=LocalDate.now(deps.services.clock)
    val active=state.habits.filter { HabitRules.scheduled(it,today) }
    val scope=rememberCoroutineScope()
    OutlinedButton(onClick={ scope.launch { deps.services.ui.open("habits") } },modifier=Modifier.fillMaxWidth()) {
        Text(if(active.isEmpty()) "养一个小习惯 · 本地打卡与温和督促" else "小习惯 · 今天 ${active.count { HabitRules.progress(it,state,today).todayDone }}/${active.size} 已打卡")
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun HabitScreen(deps: UiDependencies) {
    val service=deps.services.habits
    val state by service.state.collectAsStateWithLifecycle()
    val notice by service.notice.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val today by produceState(LocalDate.now(deps.services.clock)) { while(true) { value=LocalDate.now(deps.services.clock); kotlinx.coroutines.delay(30_000) } }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPaused by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { service.load() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(18.dp)) {
            Text("每天一点点",style=MaterialTheme.typography.headlineSmall)
            Text("打卡、提醒和统计都在本机运行。漏一天也可以继续，近 7 天可以补记。",style=MaterialTheme.typography.bodySmall)
        } } }
        item { Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row { Text("温和督促",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); Switch(state.gentle,{ enabled -> scope.launch { runCatching { service.gentle(enabled) }.onFailure { error=it.message } } }) }
            Text("只督促已开启通知的未完成任务：到安排结束后 30／60 分钟再提醒，全天最多 2 次，22:00—08:00 不打扰。不会自动勾选或改期。",style=MaterialTheme.typography.bodySmall)
            Text("番茄结束后可选择休息；关闭督促不会关闭原有闹钟。",style=MaterialTheme.typography.bodySmall)
            notice?.let { Text(it,color=MaterialTheme.colorScheme.secondary) }
        } } }
        item { Row { Text("我的小习惯",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge); TextButton(onClick={ editingId=null; editor=true }) { Text("新增习惯") } }; FilterChip(showPaused,{ showPaused=!showPaused },label={ Text("显示暂停的习惯") }) }
        if(state.habits.none { it.enabled || showPaused }) item { Text("从一件很小的事开始，比如阅读、喝水或拉伸。",style=MaterialTheme.typography.bodyMedium) }
        items(state.habits.filter { it.enabled || showPaused },key={ it.id }) { h ->
            val progress=HabitRules.progress(h,state,today)
            Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row { Text(h.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); TextButton(onClick={ editingId=h.id; editor=true }) { Text("编辑习惯") } }
                Text("近 7 天 ${progress.done}/${progress.planned} · 连续 ${progress.streak} 个计划日${if(!h.enabled) " · 已暂停" else ""}",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { DateWindows.centered(today).forEach { day ->
                     val done=state.checks.any { it.habitId==h.id && it.date==day.toString() && it.done }
                    val scheduled=!day.isAfter(today) && HabitRules.scheduled(h,day)
                    Column { Text(if(day==today) "今天" else "${day.monthValue}/${day.dayOfMonth}",style=MaterialTheme.typography.labelSmall); Checkbox(done,{ checked -> scope.launch { runCatching { service.check(h.id,day.toString(),checked) }.onFailure { error=it.message } } },enabled=scheduled,modifier=Modifier.size(38.dp)) }
                } }
                Text(if(HabitRules.scheduled(h,today)) if(progress.todayDone) "今天已打卡，明天继续。" else "今天还可以留一点时间。" else "今天没有打卡计划。",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.bodySmall)
                if(h.remind) Text("${h.time} 轻提醒 · 系统省电时可能延后",style=MaterialTheme.typography.labelSmall)
            } }
        }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error); TextButton(onClick={ error=null }) { Text("知道了") } } }
    }
    if(editor) HabitEditor(state.habits.firstOrNull { it.id==editingId },deps,{ editor=false })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HabitEditor(habit: Habit?,deps: UiDependencies,onDismiss: ()->Unit) {
    val key=habit?.id ?: "new-habit"
    var title by rememberSaveable(key) { mutableStateOf(habit?.title ?: "") }
    var days by rememberSaveable(key) { mutableStateOf(habit?.weekdays ?: (1..7).toSet()) }
    var time by rememberSaveable(key) { mutableStateOf(habit?.time.orEmpty()) }
    var remind by rememberSaveable(key) { mutableStateOf(habit?.remind ?: false) }
    var enabled by rememberSaveable(key) { mutableStateOf(habit?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={ if(!busy) onDismiss() },title={ Text(if(habit==null) "养一个小习惯" else "编辑习惯") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title,{ title=it },label={ Text("习惯名称") },singleLine=true)
        FlowRow { (1..7).forEach { d -> FilterChip(d in days,{ days=if(d in days) days-d else days+d },label={ Text("周${"一二三四五六日"[d-1]}") }) } }
        Row { Text("定时轻提醒",Modifier.weight(1f)); Switch(remind,{ remind=it }) }
        if(remind) TimeWheelField(time,"提醒时间",false) { time=it }
        if(habit!=null) Row { Text("继续养成",Modifier.weight(1f)); Switch(enabled,{ enabled=it }) }
        if(habit!=null) TextButton(onClick={ deleting=true }) { Text("删除习惯与打卡记录",color=MaterialTheme.colorScheme.error) }
        Text("暂停会保留记录，不会新增重复待办。",style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ Button(onClick={ scope.launch { busy=true; runCatching { deps.services.habits.save(habit?.id,habit?.version,title,days,time.takeIf { it.isNotBlank() },remind,enabled) }.onSuccess { onDismiss() }.onFailure { error=it.message }; busy=false } },enabled=!busy) { Text("保存习惯") } },dismissButton={ TextButton(onClick=onDismiss,enabled=!busy) { Text("返回") } })
    if(deleting && habit!=null) AlertDialog(onDismissRequest={ deleting=false },title={ Text("删除这个习惯？") },text={ Text("“${habit.title}”和对应打卡记录会删除，其他习惯保留。") },confirmButton={ TextButton(onClick={ scope.launch { runCatching { deps.services.habits.delete(habit.id,habit.version) }.onSuccess { deleting=false; onDismiss() }.onFailure { deleting=false; error=it.message } } }) { Text("删除",color=MaterialTheme.colorScheme.error) } },dismissButton={ TextButton(onClick={ deleting=false }) { Text("保留") } })
}
