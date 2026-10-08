package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID

@Serializable data class FocusSettings(val focusMinutes: Int = 25, val restMinutes: Int = 5, val longRestMinutes: Int = 15, val longRestEvery: Int = 4, val notify: Boolean = true, val immersive: Boolean = false)
@Serializable data class FocusRun(val id: String, val mode: String = "pomodoro", val phase: String = "focus", val status: String = "running", val taskId: String? = null, val title: String = "自由专注", val targetMillis: Long = 25 * 60_000L, val elapsedMillis: Long = 0, val startedAt: Long? = null, val monotonicStart: Long? = null, val bootId: String? = null) {
    fun spent(now: Long, uptime: Long? = null, boot: String? = null): Long = elapsedMillis + if(startedAt==null) 0 else if(monotonicStart!=null && uptime!=null && bootId!=null && bootId==boot) (uptime-monotonicStart).coerceAtLeast(0) else (now-startedAt).coerceAtLeast(0)
    fun elapsed(now: Long, uptime: Long? = null, boot: String? = null): Long = spent(now,uptime,boot).let { if(mode=="pomodoro") it.coerceAtMost(targetMillis) else it }
    fun endAt(): Long? = if(status=="running" && mode=="pomodoro") startedAt?.plus(targetMillis-elapsedMillis) else null
}
@Serializable data class FocusRecord(val id: String, val taskId: String?, val title: String, val endedAt: Long, val millis: Long, val mode: String, val completed: Boolean = true)
@Serializable data class FocusState(val settings: FocusSettings = FocusSettings(), val run: FocusRun? = null, val records: List<FocusRecord> = emptyList())
@Serializable data class FocusArchive(val settings: FocusSettings = FocusSettings(), val records: List<FocusRecord> = emptyList())
object FocusValidation {
    fun settings(s: FocusSettings) { require(s.focusMinutes in 1..240 && s.restMinutes in 1..60 && s.longRestMinutes in 1..120 && s.longRestEvery in 1..12) { "专注或休息设置超出范围" } }
    fun archive(a: FocusArchive) { settings(a.settings); require(a.records.size<=2000 && a.records.map { it.id }.distinct().size==a.records.size && a.records.all { it.id.length in 1..128 && it.title.length<=240 && it.millis>0 && it.endedAt>=0 && it.mode in setOf("pomodoro","stopwatch") }) { "专注记录不正确" } }
}

/** Android scheduling is an adapter; the timer itself survives a process restart. */
interface FocusScheduler {
    suspend fun reconcile(run: FocusRun?, notify: Boolean): String? = null
    suspend fun completed(run: FocusRun) {}
}
interface ElapsedClock { fun millis(): Long; fun bootId(): String? }
class FocusService(private val store: SessionStore, private val clock: Clock, private val scheduler: FocusScheduler = object: FocusScheduler {}, private val elapsedClock: ElapsedClock? = null) {
    private val mutex=Mutex()
    private var loaded=false
    private val mutable=MutableStateFlow(FocusState())
    val state=mutable.asStateFlow()
    private val mutableNotice=MutableStateFlow<String?>(null)
    val notice=mutableNotice.asStateFlow()
    fun elapsed(run: FocusRun, now: Long = clock.millis()) = run.elapsed(now,elapsedClock?.millis(),elapsedClock?.bootId())
    private fun start(run: FocusRun)=run.copy(startedAt=clock.millis(),monotonicStart=elapsedClock?.millis(),bootId=elapsedClock?.bootId())
    private suspend fun read() {
        if(loaded) return
        store.read("focus_state")?.let { mutable.value=Json.decodeFromString<FocusState>(it) }
        loaded=true
    }
    private suspend fun save() { store.write("focus_state",Json.encodeToString(mutable.value)) }
    private suspend fun schedule() { mutableNotice.value=try { scheduler.reconcile(mutable.value.run,mutable.value.settings.notify) } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; "计时已保存，后台提醒待检查：${e.message}" } }
    private suspend fun settle(): Boolean {
        val run=mutable.value.run ?: return false
        if(run.status!="running" || run.mode!="pomodoro") return false
        val spent=run.spent(clock.millis(),elapsedClock?.millis(),elapsedClock?.bootId())
        if(spent<run.targetMillis) return false
        val end=clock.millis()-(spent-run.targetMillis)
        val records=if(run.phase=="focus") (mutable.value.records+FocusRecord(run.id,run.taskId,run.title,end,run.targetMillis,run.mode)).distinctBy { it.id }.takeLast(2000) else mutable.value.records
        mutable.value=mutable.value.copy(run=run.copy(status="finished",elapsedMillis=run.targetMillis,startedAt=null,monotonicStart=null),records=records)
        save(); schedule()
        if(mutable.value.settings.notify) runCatching { scheduler.completed(run) }
        return true
    }
    suspend fun load() = mutex.withLock { read(); settle(); schedule(); mutable.value }
    suspend fun reload() = mutex.withLock { loaded=false; read(); settle(); schedule(); mutable.value }
    suspend fun tick() = mutex.withLock { read(); settle(); mutable.value }
    suspend fun command(action: String, mode: String = "pomodoro", minutes: Int? = null, taskId: String? = null, title: String = "自由专注", settings: FocusSettings? = null, requestKey: String? = null): FocusState = withContext(NonCancellable) { mutex.withLock {
        read()
        if(requestKey!=null && store.read("focus_request:$requestKey")!=null) return@withLock mutable.value
        settle()
        val s=mutable.value
        val run=s.run
        when(action) {
            "start" -> {
                require(mode in setOf("pomodoro","stopwatch")) { "计时模式不正确" }
                require(run?.status!="running" && run?.status!="paused") { "已有计时，请先暂停后结束或重置" }
                require(minutes==null || minutes in 1..240) { "专注时长需为 1–240 分钟" }
                mutable.value=s.copy(run=start(FocusRun(UUID.randomUUID().toString(),mode=mode,taskId=taskId,title=title.take(240),targetMillis=(minutes ?: s.settings.focusMinutes)*60_000L)))
            }
            "pause" -> { require(run?.status=="running") { "没有运行中的计时" }; mutable.value=s.copy(run=run!!.copy(status="paused",elapsedMillis=elapsed(run),startedAt=null,monotonicStart=null)) }
            "resume" -> { require(run?.status=="paused") { "没有暂停的计时" }; mutable.value=s.copy(run=start(run!!.copy(status="running"))) }
            "reset" -> mutable.value=s.copy(run=null)
            "finish" -> {
                require(run!=null && run.status in setOf("running","paused")) { "没有可结束的计时" }
                val elapsed=elapsed(run!!)
                // Keep actual focus minutes, but count only full tomatoes as completed.
                val records=if(run.phase=="focus" && elapsed>0) (s.records+FocusRecord(run.id,run.taskId,run.title,clock.millis(),elapsed,run.mode,completed=run.mode=="stopwatch")).takeLast(2000) else s.records
                mutable.value=s.copy(run=run.copy(status="finished",elapsedMillis=elapsed,startedAt=null,monotonicStart=null),records=records)
            }
            "rest" -> {
                require(run?.status=="finished" && run.phase=="focus" && run.mode=="pomodoro" && run.elapsedMillis==run.targetMillis) { "完成一个番茄后才能开始休息" }
                val count=s.records.count { it.mode=="pomodoro" && it.completed }
                val duration=if(count%s.settings.longRestEvery==0) s.settings.longRestMinutes else s.settings.restMinutes
                mutable.value=s.copy(run=start(FocusRun(UUID.randomUUID().toString(),phase="rest",taskId=run.taskId,title=run.title,targetMillis=duration*60_000L)))
            }
            "settings" -> {
                require(settings!=null); FocusValidation.settings(settings!!)
                mutable.value=s.copy(settings=settings!!)
            }
            else -> error("未知计时操作")
        }
        try { store.transaction { save(); if(requestKey!=null) store.write("focus_request:$requestKey","done") } }
        catch(e: Exception) { mutable.value=s; loaded=false; throw e }
        schedule(); mutable.value
    } }
}
