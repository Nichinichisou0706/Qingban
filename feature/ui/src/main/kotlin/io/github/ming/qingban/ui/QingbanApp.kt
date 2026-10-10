package io.github.ming.qingban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import java.time.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QingbanApp(deps: UiDependencies, assistantRequest: Int = 0, sharedText: String = "") {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var assistant by rememberSaveable { mutableStateOf(false) }
    var auxiliary by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToDashboard by rememberSaveable { mutableStateOf(false) }
    var calendarDate by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarView by rememberSaveable { mutableStateOf<String?>(null) }
    var hub by rememberSaveable { mutableStateOf(false) }
    var quickKind by rememberSaveable { mutableStateOf<ItemKind?>(null) }
    var focusTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    val storedItems by deps.services.items.store.observe().collectAsStateWithLifecycle(emptyList())
    val all=storedItems.filterNot { it.deleted }
    val navigation by deps.services.ui.events.collectAsStateWithLifecycle()
    val focusState by deps.services.focus.state.collectAsStateWithLifecycle()
    val immersive=(page==4 || auxiliary=="番茄钟") && focusState.settings.immersive && focusState.run?.status in setOf("running","paused")
    var consumedNavigation by rememberSaveable { mutableLongStateOf(0) }
    var handledAssistantRequest by rememberSaveable { mutableIntStateOf(0) }
    var consumedSharedRequest by rememberSaveable { mutableIntStateOf(-1) }
    fun openDashboard() { assistant=false; auxiliary="仪表盘"; returnToDashboard=false }
    fun openAssistant(fromDashboard:Boolean=false) { hub=false; assistant=true; auxiliary=null; returnToDashboard=fromDashboard }
    fun goBack() { assistant=false; auxiliary=if(returnToDashboard) "仪表盘" else null; returnToDashboard=false }
    BackHandler(assistant || auxiliary!=null) { goBack() }
    BackHandler(hub) { hub=false }
    LaunchedEffect(navigation) { navigation?.let { command -> if(command.sequence>consumedNavigation) {
        consumedNavigation=command.sequence; hub=false; assistant=false
        when(command.route) {
            "today","calendar","memo","alarm" -> { page=listOf("today","calendar","memo","alarm").indexOf(command.route); auxiliary=null; calendarDate=command.date; calendarView=command.view }
            "focus" -> { page=4; auxiliary=null; focusTaskId=null }
            else -> auxiliary=when(command.route) { "library" -> "资料库"; "memory" -> "习惯与偏好"; "habits" -> "习惯与督促"; else -> "模型设置" }
        }
        if(!assistant) returnToDashboard=false
    } } }
    LaunchedEffect(assistantRequest) { if(assistantRequest > handledAssistantRequest) { handledAssistantRequest=assistantRequest; openAssistant() } }
    val titles = listOf("今天", "日历", "备忘", "闹钟", "专注")
    val icons = listOf(Icons.Default.WbSunny, Icons.Default.CalendarMonth, Icons.Default.EditNote, Icons.Default.Alarm, Icons.Default.Timer)
    val animations = deps.platform.petSettings()["animations"] != "false"
    SmartCopyProvider(deps.services.clock) { CompositionLocalProvider(LocalPetAnimations provides animations) { QingbanTheme {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            if(!immersive) TopAppBar(title = { Text(auxiliary ?: if(assistant) "GPT 娘" else "轻伴 · ${titles[page]}", fontSize = 19.sp) }, navigationIcon = {
                if(assistant || auxiliary != null) IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                else IconButton(onClick=::openDashboard) { Icon(Icons.Default.SpaceDashboard,"打开仪表盘",tint=MaterialTheme.colorScheme.primary) }
            }, actions = { if((assistant || auxiliary!=null) && auxiliary!="仪表盘") IconButton(onClick=::openDashboard) { Icon(Icons.Default.SpaceDashboard,"打开仪表盘") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            if(!immersive && !assistant && auxiliary == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { titles.forEachIndexed { index, title -> NavigationBarItem(page == index, { page = index; calendarDate=null; calendarView=null; hub=false }, icon = { Icon(icons[index], null) }, label = { Text(title) }) } }
        }, floatingActionButton = {
            if(!assistant && auxiliary == null && page!=4) AssistantQuickControl(hub,{ hub=!hub },{ openAssistant() },{ kind -> hub=false; quickKind=kind },{ hub=false; focusTaskId=null; page=4 },{ hub=false; page=1 })
        }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
            when(auxiliary) {
                "仪表盘" -> DashboardScreen(deps,onPage={ index,date -> page=index; calendarDate=date?.toString(); auxiliary=null; assistant=false; returnToDashboard=false },onFeature={ title -> auxiliary=title; assistant=false; returnToDashboard=true },onAssistant={ openAssistant(true) })
                "资料库" -> LibraryScreen(deps)
                "全部事项" -> AllItemsScreen(deps)
                "习惯与偏好" -> MemoryScreen(deps)
                "习惯与督促" -> HabitScreen(deps)
                "模型设置" -> ModelSettingsScreen(deps.platform)
                "权限与桌面" -> PlatformSettingsScreen(deps)
                "关于与隐私" -> AboutScreen(deps.platform)
                "番茄钟" -> FocusScreen(deps)
                else -> if(assistant) AssistantScreen(deps,if(consumedSharedRequest!=assistantRequest) sharedText else "",assistantRequest,onInitialInputConsumed={ consumedSharedRequest=assistantRequest }) else if(page==4) FocusScreen(deps,focusTaskId) else key(page) { TaskScreen(page, deps, { openAssistant() }, { auxiliary = "权限与桌面"; returnToDashboard=false },calendarDate?.let(LocalDate::parse),calendarView) { task -> focusTaskId=task?.id; page=4; hub=false } }
            }
        } }
        quickKind?.let { kind -> ItemEditor(kind,null,LocalDate.now(deps.services.clock).takeIf { kind!=ItemKind.NOTE },deps.platform,all,{ quickKind=null },{ draft,_,_ -> scope.launch { val result=deps.services.items.create(draft); android.widget.Toast.makeText(context,result.message,android.widget.Toast.LENGTH_LONG).show(); if(result.status!="error") quickKind=null } },{ _,_ -> quickKind=null },onSplit={ drafts -> scope.launch {
            val result=deps.services.items.applyBatch(drafts.map { BatchCommand.Create(it,java.util.UUID.randomUUID().toString()) })
            android.widget.Toast.makeText(context,result.message,android.widget.Toast.LENGTH_LONG).show()
            if(result.status!="error") quickKind=null
        } }) }
    } } }
}
