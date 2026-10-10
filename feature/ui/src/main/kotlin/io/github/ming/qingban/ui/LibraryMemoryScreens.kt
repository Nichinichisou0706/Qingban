package io.github.ming.qingban.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val AttachmentSaver=Saver<KnowledgeAttachment?,String>(save={ it?.let { value -> Json.encodeToString(value) } },restore={ Json.decodeFromString<KnowledgeAttachment>(it) })

@Composable fun LibraryScreen(deps: UiDependencies) {
    val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(deps.services) })
    val sources by vm.sources.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var manualEntry by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var remote by rememberSaveable { mutableStateOf(false) }
    var attachment by rememberSaveable(stateSaver=AttachmentSaver) { mutableStateOf<KnowledgeAttachment?>(null) }
    var reading by remember { mutableStateOf<KnowledgeSource?>(null) }
    var deleting by remember { mutableStateOf<KnowledgeSource?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<KnowledgeSource?>(null) }
    var linkedCount by remember { mutableIntStateOf(0) }
    var deleteLinked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var webUrl by remember { mutableStateOf("") }
    var calendarImport by remember { mutableStateOf<CalendarImport?>(null) }
    var calendarFingerprint by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<KnowledgeHit>>(emptyList()) }
    LaunchedEffect(deleting?.id) { linkedCount=deleting?.let { deps.services.knowledge.linkedPreferences(it.id) } ?: 0; deleteLinked=false }
    val scope = rememberCoroutineScope()
    val intro=rotatingCopy("library.intro",CopyCatalog.libraryIntro(sources.size))
    val emptyTitle=rotatingCopy("library.empty",CopyCatalog.libraryEmpty)
    val emptyHint=rotatingCopy("library.emptyHint",CopyCatalog.libraryEmptyHint)
    val nameHint=rotatingCopy("library.name",CopyCatalog.libraryNameHint)
    val bodyHint=rotatingCopy("library.body",CopyCatalog.libraryBodyHint)
    fun discardDraft() { attachment?.let { original -> scope.launch { deps.platform.removeOriginal(original.id) } }; attachment=null; adding=false }
    fun startTextDraft() { discardDraft(); title=""; text=""; remote=false; manualEntry=true; vm.error.value=null; adding=true }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { importing=true; runCatching { deps.platform.readText(it.toString(),true) }.onSuccess { imported -> attachment?.let { deps.platform.removeOriginal(it.id) }; attachment=imported.attachment; title = imported.title; text = imported.text; remote=false; manualEntry=false; adding = true }.onFailure { e -> fileError = e.message }; importing=false } } }
    val calendarFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { runCatching { val imported=deps.platform.readText(it.toString()); calendarFingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(imported.text.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }; CalendarImporter.parse(imported.text,deps.services.clock.zone) }.onSuccess { calendarImport=it }.onFailure { fileError=it.message } } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(intro, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=::startTextDraft,modifier=Modifier.fillMaxWidth(),enabled=!importing) { Icon(Icons.Default.EditNote,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("手动输入文字") }
            Text("直接写下资料名称和正文，也可以长按粘贴。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { open.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*", "application/octet-stream")) },modifier=Modifier.fillMaxWidth(),enabled=!importing) { Icon(Icons.Default.UploadFile,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导入文件／图片") }
            if(importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在解析资料，扫描 PDF 和图片识别可能需要一些时间。") }
        } }
        item { OutlinedTextField(webUrl,{ webUrl=it },Modifier.fillMaxWidth(),label={ Text("网页链接（可选）") }); TextButton(onClick={ scope.launch { importing=true; runCatching { deps.platform.fetchWeb(webUrl.trim()) }.onSuccess { discardDraft(); title=it.title; text=it.text; remote=false; manualEntry=false; adding=true }.onFailure { fileError=it.message }; importing=false } },enabled=!importing && webUrl.isNotBlank()) { Text("读取网页后预览") } }
        item { OutlinedButton(onClick={ calendarFile.launch(arrayOf("text/calendar","application/octet-stream")) }) { Text("从 ICS 日历生成事项预览") } }
        item { OutlinedTextField(query,{ query=it },Modifier.fillMaxWidth(),label={ Text("搜索资料片段") }); TextButton(onClick={ scope.launch { hits=deps.services.knowledge.search(query,false,true) } }) { Text("检索（含历史资料）") }; hits.forEach { hit -> Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(12.dp)) { Text("${hit.title} · v${hit.version}"); Text(hit.excerpt) } } } }
        if(sources.isEmpty()) item { EmptyCard(emptyTitle,emptyHint,::startTextDraft) }
        items(sources, key = { it.id }) { source ->
            Card(Modifier.fillMaxWidth().clickable { reading = source }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp)) {
                    Row { Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary); Text(source.title, Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold); IconButton(onClick = { deleting = source }, Modifier.size(44.dp)) { Icon(Icons.Default.DeleteOutline, "删除资料") } }
                    Text(source.text.take(100), maxLines = 3, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("允许模型参考", Modifier.padding(top = 12.dp)); Text("只发送与问题相关的片段", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(source.allowRemote, { vm.remote(source, it) }) }
                    SoftTag("${source.text.length} 字 · v${source.version}${if(!source.enabled) " · 已停用" else ""}")
                    source.validUntil?.let { Text("适用至 $it",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary) }
                    TextButton(onClick={ vm.error.value=null; editingSource=source }) { Text("编辑资料") }
                }
            }
        }
        (error ?: fileError)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
    }
    if(adding) KnowledgeImportDialog(title,text,remote,attachment,manualEntry,nameHint,bodyHint,saving,error,{ title=it },{ text=it },{ remote=it },::discardDraft) { vm.import(title,text,remote,attachment) { attachment=null; adding=false; hits=emptyList() } }
    reading?.let { source -> SourceReadDialog(source,deps.platform,{ reading=null },{ fileError=it }) }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这份资料？") }, text = { Column { Text("《${source.title}》的原文件、正文、历史版本和检索内容将移除。"); if(linkedCount>0) { Text("关联 $linkedCount 项偏好；保留时将标记来源已删除，并停止作为默认依据。"); Row { Checkbox(deleteLinked,{ deleteLinked=it }); Text("同时删除关联偏好") } } } }, confirmButton = { TextButton(onClick = { vm.delete(source,deleteLinked,deps.platform); hits=emptyList(); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } }) }
    editingSource?.let { source -> KnowledgeEditor(source,{ editingSource=null },{ updatedTitle,body,enabled,from,until,origin,reliability -> vm.update(source,updatedTitle,body,enabled,from,until,origin,reliability) { hits=emptyList(); editingSource=null } },saving=saving,errorMessage=error) }
    calendarImport?.let { preview -> AlertDialog(onDismissRequest={ calendarImport=null },title={ Text("确认日历事项") },text={ Column(Modifier.verticalScroll(rememberScrollState())) { Text("最多 50 项；不会导入日历里的闹钟或自动添加提醒。"); preview.drafts.forEach { Text("${it.schedule?.date} ${it.schedule?.time.orEmpty()} · ${it.title}",Modifier.padding(top=8.dp)) }; preview.warnings.forEach { Text(it,color=MaterialTheme.colorScheme.error) } } },confirmButton={ Button(onClick={ scope.launch { val result=deps.services.items.applyBatch(preview.drafts.map { BatchCommand.Create(it) },"calendar:$calendarFingerprint"); fileError=result.message; calendarImport=null } },enabled=preview.drafts.isNotEmpty()) { Text("统一保存") } },dismissButton={ TextButton(onClick={ calendarImport=null }) { Text("返回") } }) }
}
@Composable private fun SourceReadDialog(source:KnowledgeSource,platform:PlatformActions,onDismiss:()->Unit,onError:(String?)->Unit) {
    val original=source.attachment
    AlertDialog(onDismissRequest=onDismiss,title={ Text(source.title) },text={ Text(source.text,Modifier.verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick=onDismiss) { Text("看完了") } },dismissButton={ if(original!=null) TextButton(onClick={ runCatching { platform.openOriginal(original) }.onFailure { onError(it.message) } }) { Text("查看导入时的原文件") } })
}

@Composable fun MemoryScreen(deps: UiDependencies) {
    val vm: MemoryViewModel = viewModel(factory = factory { MemoryViewModel(deps.services.memory) })
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val operationError by vm.error.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()
    val useLearned by vm.useLearned.collectAsStateWithLifecycle()
    val proactive by vm.proactive.collectAsStateWithLifecycle()
    val statistics by vm.statistics.collectAsStateWithLifecycle()
    val sources by deps.services.knowledge.store.observe().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var scoped by remember { mutableStateOf("") }
    var until by remember { mutableStateOf("") }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val intro=rotatingCopy("memory.intro",CopyCatalog.memoryIntro(learning))
    val emptyTitle=rotatingCopy("memory.empty",CopyCatalog.memoryEmpty)
    val emptyHint=rotatingCopy("memory.emptyHint",CopyCatalog.memoryEmptyHint)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(18.dp)) {
            Row { Column(Modifier.weight(1f)) { Text(intro, fontWeight = FontWeight.SemiBold); Text("只观察本应用中你选择的时间", fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }; Switch(learning, { vm.learning(it) }) }
            Text("观察到规律后会先问你，确认后才用于默认建议。", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } } }
        operationError?.let { message -> item { Text(message,color=MaterialTheme.colorScheme.error) } }
        item { Card { Column(Modifier.padding(16.dp)) {
            Row { Text("使用已确认的习惯",Modifier.weight(1f)); Switch(useLearned,{ vm.useLearned(it) }) }
            Row { Text("主动询问习惯（每天最多一次）",Modifier.weight(1f)); Switch(proactive,{ vm.proactive(it) }) }
            Text("近 14 天的时间选择：" + statistics.entries.joinToString("、") { "${periodLabel(it.key)} ${it.value} 次" }.ifBlank { "暂无观察" },fontSize=12.sp)
        } } }
        if(candidates.isNotEmpty()) item { SectionTitle("想和你确认一下") }
        items(candidates, key = { it.id }) { candidate -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(18.dp)) {
            Text("铭哥，最近你在 ${candidate.distinctDays} 天里，${candidate.count} 次为“${candidate.activity}”选择了${periodLabel(candidate.period)}。以后优先推荐这个时段吗？", lineHeight = 23.sp)
            Row { TextButton(onClick = { vm.accept(candidate) }) { Text("采用这个偏好") }; TextButton(onClick = { vm.dismiss(candidate) }) { Text("先不要") } }
        } } }
        item { SectionTitle("已确认的偏好", "随时修改或忘记", trailing = { IconButton(onClick = { key = ""; value = ""; scoped = ""; until=""; sourceId=null; editing = true }) { Icon(Icons.Default.Add, "添加偏好") } }) }
        if(prefs.isEmpty()) item { EmptyCard(emptyTitle,emptyHint) { editing = true } }
        items(prefs, key = { "${it.key}:${it.scope}" }) { pref -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(18.dp)) {
            Row { Text(preferenceLabel(pref), Modifier.weight(1f), fontWeight = FontWeight.Medium); IconButton(onClick = { key = pref.key; value = pref.value; scoped = pref.scope; until=pref.validUntil.orEmpty(); sourceId=pref.sourceId; editing = true }, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, "编辑偏好") }; IconButton(onClick = { vm.delete(pref) }, Modifier.size(28.dp)) { Icon(Icons.Default.DeleteOutline, "忘记偏好") } }
            Text(if(pref.value in setOf("morning", "afternoon", "evening")) periodLabel(pref.value) else pref.value, Modifier.padding(top = 5.dp))
            Text(pref.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if(pref.sourceDeleted) Text("原来源已删除 · 已停止使用",color=MaterialTheme.colorScheme.error,fontSize=11.sp)
            if(pref.sourceId!=null && !pref.sourceDeleted && sources.firstOrNull { it.id==pref.sourceId }?.version!=pref.sourceVersion) Text("资料版本已变化 · 重新核对后才会采用",color=MaterialTheme.colorScheme.secondary,fontSize=11.sp)
            if(pref.validUntil?.let { it<java.time.LocalDate.now(deps.services.clock).toString() }==true) Text("已过期 · 已停止使用",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
            pref.validUntil?.let { Text("有效至 $it",fontSize=11.sp) }
            Row { Text("使用这项偏好",Modifier.weight(1f)); Switch(pref.enabled,{ vm.enabled(pref,it) }) }
        } } }
        item { OutlinedButton(onClick = { vm.clearLearning() }) { Text("清空习惯观察记录") }; Text("已确认的偏好会保留，可单独删除。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if(editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("记住一个偏好") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(key, { key = it }, label = { Text("偏好名称") }, placeholder = { Text("例如：工作日的作息") })
        OutlinedTextField(value, { value = it }, label = { Text("具体偏好") }, placeholder = { Text("例如：早上优先做需要专注的事") })
        OutlinedTextField(scoped, { scoped = it }, label = { Text("适用活动（可选）") })
        DateWheelField(until,"有效至") { until=it }
        Text("关联资料（可选）")
        TextButton(onClick={ sourceId=null }) { Text(if(sourceId==null) "✓ 用户直接声明" else "用户直接声明") }
        sources.forEach { source -> TextButton(onClick={ sourceId=source.id }) { Text("${if(sourceId==source.id) "✓ " else ""}${source.title} · v${source.version}") } }
        editError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } }, confirmButton = { Button(onClick = { if(until.isNotBlank() && runCatching { java.time.LocalDate.parse(until) }.isFailure) editError="有效期请填写 YYYY-MM-DD" else { vm.save(key, value, scoped,until.takeIf { it.isNotBlank() },sourceId,sources.firstOrNull { it.id==sourceId }?.version); editing = false } }, enabled = key.isNotBlank() && key.length<=80 && value.isNotBlank() && value.length<=500 && scoped.length<=240) { Text("记住") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("返回") } })
}
private fun preferenceLabel(p: Preference) = when { p.key == "activity.preferred_period" -> "${p.scope} · 优先时段"; p.key.startsWith("activity.") -> "${p.scope.ifBlank { "活动" }}的安排偏好"; else -> p.key }
