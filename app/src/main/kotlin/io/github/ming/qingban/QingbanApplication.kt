package io.github.ming.qingban

import android.app.Application
import android.app.Activity
import android.os.Bundle
import io.github.ming.qingban.agent.*
import io.github.ming.qingban.data.*
import io.github.ming.qingban.domain.*
import io.github.ming.qingban.model.CompatibleModelClient
import io.github.ming.qingban.platform.*
import kotlinx.coroutines.*

class QingbanApplication : Application(), AndroidDependencyOwner {
    private val foreground=kotlinx.coroutines.flow.MutableStateFlow(false)
    override val appForeground: kotlinx.coroutines.flow.StateFlow<Boolean> get()=foreground
    private var startedActivities=0
    lateinit var container: AppContainer
        private set
    override val services get() = container.services
    override val petDrawable get() = io.github.ming.qingban.ui.R.drawable.gpt_idle
    override val petDrawables get() = mapOf(PetVisual.IDLE to io.github.ming.qingban.ui.R.drawable.gpt_idle,PetVisual.THINKING to io.github.ming.qingban.ui.R.drawable.gpt_thinking,PetVisual.WAITING to io.github.ming.qingban.ui.R.drawable.gpt_waiting,PetVisual.SUCCESS to io.github.ming.qingban.ui.R.drawable.gpt_welcome,PetVisual.REMINDER to io.github.ming.qingban.ui.R.drawable.gpt_welcome,PetVisual.ERROR to io.github.ming.qingban.ui.R.drawable.gpt_waiting)
    override val petState get() = kotlinx.coroutines.flow.combine(container.assistant.busy,container.assistant.session) { busy,session -> when { busy -> PetVisual.THINKING; session.pendingLocal!=null || session.pendingUserRequest!=null -> PetVisual.WAITING; session.history.lastOrNull()?.changeId!=null -> PetVisual.SUCCESS; else -> PetVisual.IDLE } }
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity:Activity) { startedActivities++; foreground.value=true }
            override fun onActivityStopped(activity:Activity) { startedActivities=(startedActivities-1).coerceAtLeast(0); if(startedActivities==0 && !activity.isChangingConfigurations) foreground.value=false }
            override fun onActivityCreated(activity:Activity,state:Bundle?) = Unit
            override fun onActivityResumed(activity:Activity) = Unit
            override fun onActivityPaused(activity:Activity) = Unit
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) = Unit
            override fun onActivityDestroyed(activity:Activity) = Unit
        })
        container.scope.launch {
            container.originals.prune(services.knowledge.store.all().mapNotNull { it.attachment?.id }.toSet())
            services.items.restoreSchedules()
            services.focus.load()
            services.habits.load()
            DailyReviewScheduler.schedule(this@QingbanApplication)
            services.items.store.observe().collect { services.review.refresh(); GentleTaskScheduler.reconcile(this@QingbanApplication,services.habits.state.value.gentle,it); QingbanWidget.updateAll(this@QingbanApplication) }
        }
    }
}
class AppContainer(context: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = DeviceClock()
    val local = LocalStores(context)
    val secrets = SecureModelSettings(context)
    val music = MusicImporter(context)
    val backups = BackupManager(local)
    val portable = PortableBackup(context, local, backups)
    val documents = DocumentImporter(context)
    val originals = OriginalDocumentVault(context)
    val services = Services(ItemService(local.items, AndroidReminderScheduler(context, clock), clock, local.sessions), KnowledgeService(local.knowledge, clock, local.memory), MemoryService(local.memory, local.sessions, clock, local.knowledge), local.sessions, clock, FocusService(local.sessions,clock,AndroidFocusScheduler(context),AndroidElapsedClock(context)), sounds=object: SoundLibrary {
        override suspend fun list()=music.library().map { SoundChoice(java.io.File(it.first).name,it.second) }
        override suspend fun path(id: String)=music.library().firstOrNull { java.io.File(it.first).name==id }?.first
    },habits=HabitService(local.sessions,clock,AndroidHabitScheduler(context)))
    private val client = CompatibleModelClient(CredentialProvider {
        secrets.activeConnection()?.let { (profile, key) -> ModelConfiguration(profile.baseUrl, profile.model, key, profile.jsonMode) }
    })
    private val prompt = AgentCoordinator::class.java.getResourceAsStream("/agent-system.txt")?.bufferedReader()?.use { it.readText() } ?: error("Missing packaged agent preset")
    val assistant = AgentCoordinator(services, client, prompt)
    suspend fun evaluateModel(conversations: Boolean=false,scenario: String?=null): String {
        val evaluator=ModelEvaluator(client,prompt,clock)
        val results=if(scenario!=null) evaluator.runDetailedConversations(scenario) else if(conversations) evaluator.runConversations()+evaluator.runDetailedConversations() else evaluator.run()
        return "${results.count { it.passed }}/${results.size} 个${if(conversations) "多轮" else "首步"}场景通过\n"+results.joinToString("\n") { "${if(it.passed) "✓" else "×"} ${it.name}：${it.detail}" }+"\n只使用独立测试数据，不读取或修改你的事项和资料；结果不代表手机运行验收。"
    }
}
