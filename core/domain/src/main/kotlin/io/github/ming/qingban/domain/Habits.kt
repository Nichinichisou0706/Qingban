package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.*
import java.util.UUID

@Serializable data class Habit(val id: String,val version: Int=1,val title: String,val weekdays: Set<Int> =(1..7).toSet(),val time: String?=null,val remind: Boolean=false,val enabled: Boolean=true,val createdDate: String)
@Serializable data class HabitCheck(val habitId: String,val date: String,val done: Boolean=true)
@Serializable data class HabitState(val habits: List<Habit> = emptyList(),val checks: List<HabitCheck> = emptyList(),val gentle: Boolean=false)
data class HabitProgress(val done: Int,val planned: Int,val streak: Int,val todayDone: Boolean)
interface HabitScheduler { suspend fun reconcile(state: HabitState): String?=null; suspend fun cancel(id: String) {} }
object HabitRules {
    fun validate(state: HabitState) {
        require(state.habits.size<=100 && state.checks.size<=10_000) { "习惯或打卡记录过多" }
        require(state.habits.map { it.id }.distinct().size==state.habits.size && state.checks.map { it.habitId to it.date }.distinct().size==state.checks.size) { "习惯记录重复" }
        state.habits.forEach { h ->
            require(h.id.length in 1..128 && h.version>0 && h.title.isNotBlank() && h.title.length<=80 && h.weekdays.isNotEmpty() && h.weekdays.all { it in 1..7 }) { "习惯名称或重复日期不正确" }
            LocalDate.parse(h.createdDate); h.time?.let(LocalTime::parse); require(!h.remind || h.time!=null) { "习惯提醒需要具体时间" }
        }
        state.checks.forEach { c -> require(state.habits.any { it.id==c.habitId }) { "打卡对应的习惯不存在" }; LocalDate.parse(c.date) }
    }
    fun scheduled(h: Habit,day: LocalDate)=h.enabled && day.toString()>=h.createdDate && day.dayOfWeek.value in h.weekdays
    fun progress(h: Habit,state: HabitState,today: LocalDate): HabitProgress {
        val done=state.checks.filter { it.habitId==h.id && it.done }.map { it.date }.toSet()
        val days=(0L..6L).map(today::minusDays).filter { it.toString()>=h.createdDate && it.dayOfWeek.value in h.weekdays }
        var streak=0; var day=today
        if(day.toString() !in done) day=day.minusDays(1) // Today is still in progress.
        var examined=0
        while(day.toString()>=h.createdDate && examined++<3660) {
            if(day.dayOfWeek.value in h.weekdays) { if(day.toString() !in done) return HabitProgress(days.count { it.toString() in done },days.size,streak,today.toString() in done); streak++ }
            day=day.minusDays(1)
        }
        return HabitProgress(days.count { it.toString() in done },days.size,streak,today.toString() in done)
    }
}
class HabitService(private val store: SessionStore,private val clock: Clock,private val scheduler: HabitScheduler=object: HabitScheduler {}) {
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(HabitState())
    val state=mutable.asStateFlow()
    private var loaded=false
    val notice=MutableStateFlow<String?>(null)
    private suspend fun read() { if(!loaded) { store.read("habit_state")?.let { mutable.value=Json.decodeFromString<HabitState>(it) }; loaded=true } }
    private suspend fun schedule() { notice.value=runCatching { scheduler.reconcile(mutable.value) }.getOrElse { if(it is kotlinx.coroutines.CancellationException) throw it; "设置已保存，通知待检查" } }
    suspend fun load()=mutex.withLock { read(); schedule(); mutable.value }
    suspend fun reload()=mutex.withLock { loaded=false; read(); schedule(); mutable.value }
    suspend fun save(id: String?=null,version: Int?=null,title: String,weekdays: Set<Int> =(1..7).toSet(),time: String?=null,remind: Boolean=false,enabled: Boolean=true,key: String?=null): Habit = mutex.withLock {
        read()
        key?.let { k -> store.read("habit_request:$k")?.let { saved -> return@withLock mutable.value.habits.first { it.id==saved } } }
        val old=id?.let { target -> mutable.value.habits.firstOrNull { it.id==target } ?: error("习惯不存在") }
        require(old==null || version==old.version) { "习惯已变化，请刷新后再修改" }
        if(old==null) require(mutable.value.habits.none { it.enabled && it.title==title.trim() }) { "已有同名习惯，请查询后修改" }
        val habit=Habit(old?.id ?: UUID.randomUUID().toString(),(old?.version ?: 0)+1,title.trim(),weekdays,time,remind,enabled,old?.createdDate ?: LocalDate.now(clock).toString())
        persist(mutable.value.copy(habits=mutable.value.habits.filterNot { it.id==habit.id }+habit),key,habit.id); schedule(); habit
    }
    suspend fun check(id: String,date: String=LocalDate.now(clock).toString(),done: Boolean=true,key: String?=null): HabitState=mutex.withLock {
        read()
        if(key!=null && store.read("habit_request:$key")!=null) return@withLock mutable.value
        val habit=mutable.value.habits.firstOrNull { it.id==id } ?: error("习惯不存在")
        val day=LocalDate.parse(date); val today=LocalDate.now(clock)
        require(day in today.minusDays(6)..today && HabitRules.scheduled(habit,day)) { "只能给近 7 天已开始且计划内的习惯打卡" }
        val next=mutable.value.copy(checks=(mutable.value.checks.filterNot { it.habitId==id && it.date==date }+HabitCheck(id,date,done)).takeLast(10_000))
        persist(next,key,id); schedule(); mutable.value
    }
    suspend fun gentle(enabled: Boolean)=mutex.withLock { read(); persist(mutable.value.copy(gentle=enabled),null,""); schedule() }
    suspend fun delete(id: String,version: Int,key: String?=null)=mutex.withLock {
        read(); if(key!=null && store.read("habit_request:$key")!=null) return@withLock
        val h=mutable.value.habits.firstOrNull { it.id==id } ?: error("习惯不存在")
        require(h.version==version) { "习惯已变化，请刷新后再删除" }
        persist(mutable.value.copy(habits=mutable.value.habits.filterNot { it.id==id },checks=mutable.value.checks.filterNot { it.habitId==id }),key,id)
        scheduler.cancel(id); schedule()
    }
    private suspend fun persist(next: HabitState,key: String?,id: String) {
        HabitRules.validate(next)
        store.transaction { store.write("habit_state",Json.encodeToString(next)); if(key!=null) store.write("habit_request:$key",id) }
        mutable.value=next
    }
}
