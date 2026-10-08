package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.*

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun FocusScreen(deps: UiDependencies, initialTaskId: String? = null) {
    val service=deps.services.focus
    val state by service.state.collectAsStateWithLifecycle()
    val notice by service.notice.collectAsStateWithLifecycle()
    val items by deps.services.items.store.observe().collectAsStateWithLifecycle(emptyList())
    val scope=rememberCoroutineScope()
    var now by remember { mutableLongStateOf(deps.services.clock.millis()) }
    var mode by rememberSaveable { mutableStateOf("pomodoro") }
    var selectedId by rememberSaveable(initialTaskId) { mutableStateOf(initialTaskId) }
    var chooseTask by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var stats by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service) { service.load(); while(true) { service.tick(); now=deps.services.clock.millis(); delay(250) } }
    val run=state.run
    LaunchedEffect(run?.id) { run?.let { mode=it.mode } }
    val active=run?.status in setOf("running","paused")
    val view=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(active,state.settings.immersive) {
        val was=view.keepScreenOn
        if(active && state.settings.immersive) view.keepScreenOn=true
        onDispose { view.keepScreenOn=was }
    }
    val elapsed=run?.let { service.elapsed(it,now) } ?: 0L
    val shown=if((run?.mode ?: mode)=="stopwatch") elapsed else ((run?.targetMillis ?: state.settings.focusMinutes*60_000L)-elapsed).coerceAtLeast(0)
    val seconds=shown/1000
    val today=LocalDate.now(deps.services.clock)
    val todayRecords=state.records.filter { Instant.ofEpochMilli(it.endedAt).atZone(deps.services.clock.zone).toLocalDate()==today }
    val selected=items.firstOrNull { it.id==selectedId }
    fun act(action: String) { scope.launch { runCatching { service.command(action,mode=mode,taskId=selected?.id ?: run?.taskId,title=selected?.title ?: run?.title ?: "自由专注") }.onFailure { error=it.message } } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        if(!state.settings.immersive) item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip((run?.mode ?: mode)=="pomodoro",{ if(!active) { mode="pomodoro"; act("reset") } },enabled=!active,label={ Text("番茄钟") }); FilterChip((run?.mode ?: mode)=="stopwatch",{ if(!active) { mode="stopwatch"; act("reset") } },enabled=!active,label={ Text("正计时") }) } }
        if(!active && (run?.mode ?: mode)=="pomodoro" && !state.settings.immersive) item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(25,45,60).forEach { n -> AssistChip(onClick={ scope.launch { service.command("reset"); service.command("settings",settings=state.settings.copy(focusMinutes=n)) } },label={ Text("$n 分钟") }) } } }
        item {
            Text(if(run?.phase=="rest") "休息一下" else if(active) "把这一刻交给自己" else "从一小段专注开始",fontSize=22.sp,fontWeight=FontWeight.Medium)
        }
        notice?.let { item { Row(verticalAlignment=Alignment.CenterVertically) { Text(it,Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.secondary); TextButton(onClick={ deps.platform.requestPermission(if(deps.platform.permissions()["notifications"]!=true) "notifications" else "exact_alarm") }) { Text("提醒设置") } } } }
        item {
            Box(Modifier.size(260.dp),contentAlignment=Alignment.Center) {
                CircularProgressIndicator(progress={ if(run==null || run.mode=="stopwatch") 1f else (1f-elapsed.toFloat()/run.targetMillis).coerceIn(0f,1f) },modifier=Modifier.fillMaxSize(),strokeWidth=7.dp,trackColor=MaterialTheme.colorScheme.secondaryContainer)
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${seconds/60}:${(seconds%60).toString().padStart(2,'0')}",fontSize=52.sp,fontWeight=FontWeight.Light)
                    Text(when(run?.status) { "running" -> if(run.phase=="rest") "休息中" else "专注中"; "paused" -> "已暂停"; "finished" -> "本轮已结束"; else -> "准备好了就开始" },color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { OutlinedButton(onClick={ chooseTask=true },enabled=!active,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.CheckCircleOutline,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(active) run!!.title else selected?.title ?: run?.title ?: "选择关联事项 · 自由专注") } }
        item {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                when(run?.status) {
                    "running" -> Button(onClick={ act("pause") },shape=CircleShape) { Text("暂停") }
                    "paused" -> Button(onClick={ act("resume") },shape=CircleShape) { Text("继续") }
                    else -> Button(onClick={ act("start") },shape=CircleShape) { Text(if(run==null) "开始" else "开始新一轮") }
                }
                if(active) OutlinedButton(onClick={ act("finish") }) { Text("结束本轮") }
                if(run!=null) TextButton(onClick={ act("reset") }) { Text("重置") }
                if(run?.status=="finished" && run.phase=="focus" && run.mode=="pomodoro" && run.elapsedMillis==run.targetMillis) OutlinedButton(onClick={ act("rest") }) { Text("开始休息") }
            }
        }
        if(!state.settings.immersive) {
            item { Text("今天完成 ${todayRecords.count { it.mode=="pomodoro" && it.completed }} 个番茄 · 专注 ${todayRecords.sumOf { it.millis }/60_000} 分钟",color=MaterialTheme.colorScheme.primary) }
            item { Row { TextButton(onClick={ stats=true }) { Icon(Icons.Default.BarChart,null); Text("专注统计") }; TextButton(onClick={ showSettings=true }) { Icon(Icons.Default.Tune,null); Text("专注设置") } } }
            item { Text("倒计时结束后记录一轮；提前结束保留专注时长，完成番茄数不增加。正计时结束时记录已专注时长。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        } else item { TextButton(onClick={ showSettings=true }) { Text("退出沉浸／设置") } }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
    }
    if(chooseTask) ModalBottomSheet(onDismissRequest={ chooseTask=false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=500.dp),contentPadding=PaddingValues(20.dp)) {
            item { Text("这次专注做什么？",style=MaterialTheme.typography.titleLarge); TextButton(onClick={ selectedId=null; scope.launch { service.command("reset") }; chooseTask=false }) { Text("自由专注") }; HorizontalDivider(); Text("今天的事项优先") }
            val available=items.filter { it.kind!=ItemKind.ALARM && it.status==ItemStatus.OPEN }.sortedBy { if(ScheduleEngine.onDate(it,today)!=null) 0 else 1 }
            items(available,key={ it.id }) { task -> TextButton(onClick={ selectedId=task.id; scope.launch { service.command("reset") }; chooseTask=false },modifier=Modifier.fillMaxWidth()) { Text("${task.schedule?.time ?: "待安排"} · ${task.title}") } }
        }
    }
    if(showSettings) {
        var settings by remember { mutableStateOf(state.settings) }
        AlertDialog(onDismissRequest={ showSettings=false },title={ Text("专注设置") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MinutesWheelField(settings.focusMinutes.toString(),"专注时长",1..240,false) { settings=settings.copy(focusMinutes=it.toInt()) }
            MinutesWheelField(settings.restMinutes.toString(),"短休息",1..60,false) { settings=settings.copy(restMinutes=it.toInt()) }
            MinutesWheelField(settings.longRestMinutes.toString(),"长休息",1..120,false) { settings=settings.copy(longRestMinutes=it.toInt()) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("每几轮长休息",Modifier.weight(1f)); NumberWheel(settings.longRestEvery,1..12,"轮",Modifier.width(90.dp)) { settings=settings.copy(longRestEvery=it) } }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("结束通知",Modifier.weight(1f)); Switch(settings.notify,{ settings=settings.copy(notify=it) }) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("沉浸界面",Modifier.weight(1f)); Switch(settings.immersive,{ settings=settings.copy(immersive=it) }) }
            Text("新时长从下一轮开始生效。",fontSize=12.sp)
        } },confirmButton={ TextButton(onClick={ scope.launch { service.command("settings",settings=settings); showSettings=false } }) { Text("保存") } },dismissButton={ TextButton(onClick={ showSettings=false }) { Text("返回") } })
    }
    if(stats) AlertDialog(onDismissRequest={ stats=false },title={ Text("七天概览") },text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { DateWindows.centered(today).forEach { date ->
         val records=state.records.filter { Instant.ofEpochMilli(it.endedAt).atZone(deps.services.clock.zone).toLocalDate()==date }
        Text("${date.monthValue}/${date.dayOfMonth} · ${records.count { it.mode=="pomodoro" && it.completed }} 个番茄 · ${records.sumOf { it.millis }/60_000} 分钟")
    }; state.records.takeLast(5).reversed().forEach { Text("${it.title} · ${it.millis/60_000} 分钟${if(!it.completed) " · 提前结束" else ""}",fontSize=12.sp) } } },confirmButton={ TextButton(onClick={ stats=false }) { Text("知道了") } })
}
