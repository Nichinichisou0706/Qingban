package io.github.ming.qingban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch

private data class Tab(val title: String, val selected: ImageVector, val idle: ImageVector)
private val tabs = listOf(
    Tab("今天", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    Tab("日历", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    Tab("备忘", Icons.Filled.EditNote, Icons.Outlined.EditNote),
    Tab("闹钟", Icons.Filled.Alarm, Icons.Outlined.Alarm),
    Tab("我的", Icons.Filled.Person, Icons.Outlined.Person),
)
private const val MINE = 4
private const val FOCUS = "番茄钟"

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QingbanApp(deps: UiDependencies, assistantRequest: Int = 0, sharedText: String = "") {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var assistant by rememberSaveable { mutableStateOf(false) }
    var auxiliary by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarDate by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarView by rememberSaveable { mutableStateOf<String?>(null) }
    var hub by rememberSaveable { mutableStateOf(false) }
    var quickKind by rememberSaveable { mutableStateOf<ItemKind?>(null) }
    var focusTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    val storedItems by deps.services.items.store.observe().collectAsStateWithLifecycle(emptyList())
    val all=storedItems.filterNot { it.deleted }
    val navigation by deps.services.ui.events.collectAsStateWithLifecycle()
    val focusState by deps.services.focus.state.collectAsStateWithLifecycle()
    val immersive=auxiliary==FOCUS && focusState.settings.immersive && focusState.run?.status in setOf("running","paused")
    var consumedNavigation by rememberSaveable { mutableLongStateOf(0) }
    var handledAssistantRequest by rememberSaveable { mutableIntStateOf(0) }
    var consumedSharedRequest by rememberSaveable { mutableIntStateOf(-1) }
    fun openAssistant() { hub=false; assistant=true; auxiliary=null }
    fun openFocus(taskId: String?) { hub=false; assistant=false; focusTaskId=taskId; auxiliary=FOCUS }
    // 返回时回到进入前的底栏页面（例如从“我的”进入的工具会回到“我的”）
    fun goBack() { assistant=false; auxiliary=null }
    BackHandler(assistant || auxiliary!=null) { goBack() }
    BackHandler(!assistant && auxiliary==null && page!=0) { page=0; calendarDate=null; calendarView=null }
    BackHandler(hub) { hub=false }
    LaunchedEffect(navigation) { navigation?.let { command -> if(command.sequence>consumedNavigation) {
        consumedNavigation=command.sequence; hub=false; assistant=false
        when(command.route) {
            "today","calendar","memo","alarm" -> { page=listOf("today","calendar","memo","alarm").indexOf(command.route); auxiliary=null; calendarDate=command.date; calendarView=command.view }
            "focus" -> openFocus(null)
            else -> auxiliary=when(command.route) { "library" -> "资料库"; "memory" -> "习惯与偏好"; "habits" -> "习惯与督促"; else -> "模型设置" }
        }
    } } }
    LaunchedEffect(assistantRequest) { if(assistantRequest > handledAssistantRequest) { handledAssistantRequest=assistantRequest; openAssistant() } }
    val animations = deps.platform.petSettings()["animations"] != "false"
    SmartCopyProvider(deps.services.clock) { CompositionLocalProvider(LocalPetAnimations provides animations, LocalSnackbar provides snackbar) { QingbanTheme {
        val nested=assistant || auxiliary!=null
        Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if(!immersive) TopAppBar(title = { Text(auxiliary ?: if(assistant) "GPT 娘" else tabs[page].title, style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
                if(nested) IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            if(!immersive && !nested) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) { tabs.forEachIndexed { index, tab ->
                NavigationBarItem(page == index, { page = index; calendarDate=null; calendarView=null; hub=false }, icon = { Icon(if(page==index) tab.selected else tab.idle, null) }, label = { Text(tab.title) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
            } }
        }, floatingActionButton = {
            if(!nested) AssistantQuickControl(hub,{ hub=!hub },{ openAssistant() },{ kind -> hub=false; quickKind=kind },{ openFocus(null) },{ hub=false; page=1 })
        }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
            when(auxiliary) {
                "资料库" -> LibraryScreen(deps)
                "全部事项" -> AllItemsScreen(deps)
                "习惯与偏好" -> MemoryScreen(deps)
                "习惯与督促" -> HabitScreen(deps)
                "模型设置" -> ModelSettingsScreen(deps.platform)
                "权限与桌面" -> PlatformSettingsScreen(deps)
                "关于与隐私" -> AboutScreen(deps.platform)
                FOCUS -> key(focusTaskId) { FocusScreen(deps,focusTaskId) }
                else -> when {
                    assistant -> AssistantScreen(deps,if(consumedSharedRequest!=assistantRequest) sharedText else "",assistantRequest,onInitialInputConsumed={ consumedSharedRequest=assistantRequest })
                    page==MINE -> DashboardScreen(deps,onPage={ index,date -> page=index; calendarDate=date?.toString() },onFeature={ title -> if(title==FOCUS) openFocus(null) else auxiliary=title },onAssistant={ openAssistant() })
                    else -> key(page) { TaskScreen(page, deps, { openAssistant() }, { auxiliary = "权限与桌面" },calendarDate?.let(LocalDate::parse),calendarView) { task -> openFocus(task?.id) } }
                }
            }
            // 展开快捷入口时用浅色遮罩，点空白处收起
            if(hub && !nested) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.18f)).clickable(interactionSource=remember { MutableInteractionSource() },indication=null) { hub=false })
        } }
        quickKind?.let { kind -> ItemEditor(kind,null,LocalDate.now(deps.services.clock).takeIf { kind!=ItemKind.NOTE },deps.platform,all,{ quickKind=null },{ draft,_,_ -> scope.launch { val result=deps.services.items.create(draft); if(result.status!="error") quickKind=null; snackbar.showSnackbar(result.message) } },{ _,_ -> quickKind=null },onSplit={ drafts -> scope.launch {
            val result=deps.services.items.applyBatch(drafts.map { BatchCommand.Create(it,java.util.UUID.randomUUID().toString()) })
            if(result.status!="error") quickKind=null
            snackbar.showSnackbar(result.message)
        } }) }
    } } }
}
