package io.github.ming.qingban.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import io.github.ming.qingban.agent.Proposal
import io.github.ming.qingban.agent.ProposedAction
import kotlinx.serialization.json.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.RecognizerIntent
import io.github.ming.qingban.domain.HabitCandidate

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AssistantScreen(deps: UiDependencies, initialInput: String = "", inputVersion: Int = 0, onInitialInputConsumed: () -> Unit = {}) {
    val vm: AssistantViewModel = viewModel(factory = factory { AssistantViewModel(deps.assistant,deps.services.memory) })
    val session by vm.session.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val habit by vm.habit.collectAsStateWithLifecycle()
    LaunchedEffect(inputVersion) { if(initialInput.isNotBlank()) { input = initialInput; onInitialInputConsumed() } }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it.take(8000); voiceMessage = "识别内容已填入，检查后点击发送" }
    }
    var proposal by remember { mutableStateOf<Proposal?>(null) }
    var proposalNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var previewChanges by remember { mutableStateOf<List<ProposedAction>>(emptyList()) }
    var previewSelected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val coroutine = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val waiting=session.pendingLocal!=null || session.pendingUserRequest!=null
    val mood=if(busy) AssistantMood.BUSY else if(waiting) AssistantMood.WAITING else AssistantMood.IDLE
    val greeting=rotatingCopy("assistant.greeting",CopyCatalog.assistantGreeting(LocalCopyTime.current))
    val support=rotatingCopy("assistant.support",CopyCatalog.assistantSupport(mood,session.history.isNotEmpty()))
    val inputHint=rotatingCopy("assistant.input",CopyCatalog.assistantInput(mood))
    val busyHint=rotatingCopy("assistant.busy",CopyCatalog.busy)
    val examples=listOf(rotatingCopy("assistant.example.task",CopyCatalog.taskExamples),rotatingCopy("assistant.example.alarm",CopyCatalog.alarmExamples),rotatingCopy("assistant.example.note",CopyCatalog.noteExamples))
    LaunchedEffect(scroll) { snapshotFlow { scroll.layoutInfo.totalItemsCount }.collect { count -> if(count>0) scroll.animateScrollToItem(count-1) } }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GptPet(thinking = busy, waiting = waiting, modifier = Modifier.width(if(session.history.isEmpty()) 86.dp else 56.dp).height(if(session.history.isEmpty()) 120.dp else 80.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(greeting,style=MaterialTheme.typography.titleLarge)
                        if(session.history.isEmpty()) Text(support,style=MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        SoftTag(if(deps.platform.modelSummary()["has_key"] == "true") "模型助手" else "离线记录模式")
                    }
                }
                if(session.history.isEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { examples.forEach { text -> SuggestionChip(onClick = { input=text }, label = { Text(text) }) } }
            }
            habit?.let { candidate -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(16.dp)) {
                Text("铭哥，最近 ${candidate.distinctDays} 天里，你 ${candidate.count} 次把“${candidate.activity}”安排在${periodLabel(candidate.period)}。以后优先推荐这个时段吗？")
                Row { TextButton(onClick = vm::acceptHabit) { Text("采用") }; TextButton(onClick = vm::dismissHabit) { Text("先不要") } }
                Text("主动习惯提议每天最多一次，可在偏好页关闭。", fontSize = 11.sp)
            } } } }
            item { DailyReviewCard(deps) }
            itemsIndexed(session.history) { index, line ->
                val user = line.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if(user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
                    if(!user) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.gpt_icon), null, Modifier.padding(end = 8.dp).size(30.dp).clip(androidx.compose.foundation.shape.CircleShape))
                    Surface(Modifier.widthIn(max = 310.dp), shape = RoundedCornerShape(if(user) 20.dp else 6.dp, if(user) 6.dp else 20.dp, 20.dp, 20.dp), color = if(user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = if(user) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(15.dp)) {
                            SelectionContainer { Text(line.text,style=MaterialTheme.typography.bodyLarge) }
                            if(line.sources.isNotEmpty()) Text("参考：${line.sources.joinToString("、")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 7.dp))
                            if(index == session.history.lastIndex && !busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { line.options.forEach { option -> AssistChip(onClick = {
                                if(option == "查看并确认方案") coroutine.launch { proposal = vm.coordinator.proposal() } else vm.send(option)
                            }, label = { Text(option) }) } }
                            line.changeId?.let { id -> TextButton(onClick = { vm.undo(id) }, enabled = !busy) { Text("撤销这次操作") } }
                        }
                    }
                }
            }
            if(busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(busyHint, Modifier.weight(1f).padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); TextButton(onClick=vm::stop) { Text("停止") } } }
            if(!busy) {
                session.activeTurn?.let { turn -> item { OutlinedButton(onClick={ vm.retry() },modifier=Modifier.fillMaxWidth()) { Text("继续：${turn.request.take(36)}",maxLines=2,overflow=TextOverflow.Ellipsis) } } }
                session.interruptedTurns.forEachIndexed { index,turn -> item { TextButton(onClick={ vm.retry(index) }) { Text("继续：${turn.request.take(36)}") } } }
            }
            if(session.pausedLocal.isNotEmpty()) item { Text("还可以继续安排", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); FlowRow { session.pausedLocal.forEachIndexed { index, draft -> AssistChip(onClick = { vm.resumeLocal(index) }, enabled = !busy, label = { Text(draft.title) }) } } }
            if(session.pausedRequests.isNotEmpty()) item { Text("还有待答的问题",fontSize=12.sp); session.pausedRequests.forEachIndexed { index,request -> AssistChip(onClick={ vm.resumeRequest(index) },enabled=!busy,label={ Text(request.take(45)) }) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { runCatching { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_PROMPT,inputHint)) }.onFailure { voiceMessage = "手机未提供语音识别服务，可以继续输入文字" } }, enabled = !busy) { Icon(androidx.compose.material.icons.Icons.Default.Mic,"语音输入") }
            OutlinedTextField(input, { input = it.take(8000) }, Modifier.weight(1f), placeholder = { Text(inputHint,maxLines=1,overflow=TextOverflow.Ellipsis) }, shape = RoundedCornerShape(24.dp), maxLines = 4, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if(input.isNotBlank()) { vm.send(input); input = ""; voiceMessage=null; keyboard?.hide() } }, enabled = input.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
        }
        voiceMessage?.let { Text(it,fontSize=11.sp,modifier=Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(session.history.isNotEmpty()) TextButton(onClick = { clearing=true }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("清空当前对话",style=MaterialTheme.typography.labelSmall) }
    }
    if(clearing) AlertDialog(onDismissRequest={ clearing=false },title={ Text("清空当前对话？") },text={ Text("已保存的事项和资料会保留，未完成的对话请求将移除。") },confirmButton={ TextButton(onClick={ vm.clear(); clearing=false },enabled=!busy) { Text("清空") } },dismissButton={ TextButton(onClick={ clearing=false }) { Text("保留") } })
    LaunchedEffect(proposal?.id) { proposal?.let { p -> proposalNames = deps.services.items.store.all().associate { it.id to it.title }; previewChanges=p.changes; previewSelected=p.changes.indices.toSet() } }
    proposal?.let { p -> AlertDialog(onDismissRequest = { proposal = null }, title = { Text("确认安排") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(p.summary); previewChanges.forEachIndexed { index, action ->
        Row { Checkbox(index in previewSelected,{ checked -> previewSelected=if(checked) previewSelected+index else previewSelected-index }); Text("${index+1}. ${describeChange(action, proposalNames)}", Modifier.weight(1f).padding(top = 10.dp)) }
        if(index in previewSelected && action.tool !in setOf("cancel_item","delete_item")) ProposalFields(action) { updated -> previewChanges=previewChanges.toMutableList().also { it[index]=updated } }
    } } }, confirmButton = { Button(onClick = { vm.approve(p.id,previewChanges.filterIndexed { i,_ -> i in previewSelected }); proposal = null },enabled=previewSelected.isNotEmpty()) { Text("按这个保存") } }, dismissButton = { TextButton(onClick = { proposal = null }) { Text("再想想") } }) }
}
@Composable private fun ProposalFields(action: ProposedAction, onChange: (ProposedAction) -> Unit) {
    val patch=action.args["patch"]?.jsonObject; val fields=patch ?: action.args
    val schedule=fields["schedule"]?.takeUnless { it==JsonNull }?.jsonObject ?: buildJsonObject { }
    fun write(field: String,value: String) {
        val changed=if(field=="title") JsonObject(fields+(field to JsonPrimitive(value))) else JsonObject(fields+("schedule" to JsonObject(schedule+(field to if(value.isBlank()) JsonNull else JsonPrimitive(value)))))
        onChange(action.copy(args=if(patch==null) changed else JsonObject(action.args+("patch" to changed))))
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        fields["detail"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        fields["checklist"]?.jsonArray?.forEach { entry -> Text("□ ${entry.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()}",style=MaterialTheme.typography.bodySmall) }
        if(fields["title"]!=null) OutlinedTextField(fields["title"]!!.jsonPrimitive.content,{ write("title",it) },label={ Text("名称") })
        if(schedule["date"]!=null) DateWheelField(schedule["date"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content.orEmpty(),"日期") { write("date",it) }
        if(schedule["time"]!=null) TimeWheelField(schedule["time"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content.orEmpty(),"时间") { write("time",it) }
        fields["duration_minutes"]?.takeUnless { it==JsonNull }?.let { value -> MinutesWheelField(value.jsonPrimitive.content,"时长") { minutes -> val updated=JsonObject(fields+("duration_minutes" to if(minutes.isBlank()) JsonNull else JsonPrimitive(minutes.toInt()))); onChange(action.copy(args=if(patch==null) updated else JsonObject(action.args+("patch" to updated)))) } }
    }
}
private fun describeChange(action: ProposedAction, names: Map<String, String>): String {
    val args = action.args
    val fields = args["patch"]?.jsonObject ?: args
    val title = fields["title"]?.jsonPrimitive?.content ?: args["item_id"]?.jsonPrimitive?.content?.let { names[it] } ?: "事项"
    val verb = when(action.tool) { "create_item" -> "新增"; "cancel_item" -> "取消"; "delete_item" -> "删除"; else -> "调整" }
    val schedule = fields["schedule"]?.takeUnless { it == JsonNull }?.jsonObject
    val whenText = listOfNotNull(schedule?.get("date")?.jsonPrimitive?.content, schedule?.get("time")?.jsonPrimitive?.content, schedule?.get("period")?.jsonPrimitive?.content?.let(::periodLabel)).joinToString(" ")
    val minutes=fields["duration_minutes"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.intOrNull
    val end=minutes?.let { duration -> schedule?.get("time")?.takeUnless { it==JsonNull }?.jsonPrimitive?.content?.let { time -> val start=java.time.LocalTime.parse(time); " – ${start.plusMinutes(duration.toLong())}${if(start.toSecondOfDay()+duration*60>=86400) "（次日）" else ""} · $duration 分钟" } }.orEmpty()
    val range=if(action.tool=="delete_item") if(args["scope"]?.jsonPrimitive?.content=="this_occurrence") " · 仅 ${args["occurrence_date"]?.jsonPrimitive?.content.orEmpty()} 这次" else " · 整个系列" else ""
    return "$verb $title${if(whenText.isBlank()) "" else " · $whenText"}$end$range"
}
