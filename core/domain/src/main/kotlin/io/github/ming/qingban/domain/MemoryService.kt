package io.github.ming.qingban.domain

import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryService(val store: MemoryStore, private val sessions: SessionStore, private val clock: Clock, private val knowledge: KnowledgeStore? = null) {
    private val mutex = Mutex()
    suspend fun setPreference(key: String, value: String, source: String, scope: String = "", validUntil: String? = null, sourceId: String? = null, sourceVersion: Int? = null, learned: Boolean = false, requestKey: String? = null): MemoryChange = mutex.withLock {
        require(key.isNotBlank() && key.length<=80 && value.isNotBlank() && value.length<=500 && scope.length<=240) { "偏好名称最多 80 字，内容最多 500 字，适用活动最多 240 字" }
        validUntil?.let(LocalDate::parse)
        change(key, scope, Preference(key, value, scope, source, clock.millis(), validUntil = validUntil, sourceId = sourceId, sourceVersion = sourceVersion, learned = learned), requestKey)
    }
    suspend fun forget(key: String, scope: String = "", requestKey: String? = null): MemoryChange = mutex.withLock { change(key, scope, null, requestKey) }
    suspend fun disable(key: String, scope: String = "", requestKey: String? = null): MemoryChange = mutex.withLock { changeForRequest(requestKey)?.let { return@withLock it }; val old = store.listPreferences().firstOrNull { it.key == key && it.scope == scope } ?: error("没有这项偏好"); change(key, scope, old.copy(enabled = false), requestKey) }
    suspend fun setEnabled(key: String, scope: String, enabled: Boolean): MemoryChange = mutex.withLock { val old=store.listPreferences().firstOrNull { it.key==key && it.scope==scope } ?: error("没有这项偏好"); change(key,scope,old.copy(enabled=enabled)) }
    suspend fun changeForRequest(requestKey: String?): MemoryChange? = requestKey?.let { sessions.read("memory_request:$it") }?.let { sessions.read("memory_change:$it") }?.let { Json.decodeFromString<MemoryChange>(it) }
    private suspend fun change(key: String, scope: String, after: Preference?, requestKey: String? = null): MemoryChange = sessions.transaction {
        changeForRequest(requestKey)?.let { return@transaction it }
        val before = store.listPreferences().firstOrNull { it.key == key && it.scope == scope }
        val change = MemoryChange("memory:${UUID.randomUUID()}", key, scope, before, after, clock.millis())
        if(after == null) store.delete(key, scope) else store.save(after)
        sessions.write("memory_change:${change.id}", Json.encodeToString(change)); sessions.write("memory_last_change", change.id)
        sessions.write("last_change_id", change.id)
        if(requestKey != null) sessions.write("memory_request:$requestKey", change.id)
        change
    }
    suspend fun latestChange(): MemoryChange? = sessions.read("memory_last_change")?.let { id -> sessions.read("memory_change:$id")?.let { Json.decodeFromString<MemoryChange>(it) } }
    suspend fun undo(id: String? = null, requestKey: String? = null): OperationResult = mutex.withLock {
        changeForRequest(requestKey)?.let { return@withLock OperationResult("ok", "偏好已撤销，无需重复执行", changeId=it.id) }
        val change = if(id == null) latestChange() else sessions.read("memory_change:$id")?.let { Json.decodeFromString<MemoryChange>(it) }
        if(change == null) return@withLock OperationResult("error", "没有可撤销的偏好变更")
        val current = store.listPreferences().firstOrNull { it.key == change.key && it.scope == change.scope }
        if(current != change.after) return@withLock OperationResult("error", "偏好之后已变化，不能覆盖后续修改", errorCode = "version_conflict")
        val restored = change(change.key, change.scope, change.before, requestKey)
        OperationResult("ok", "偏好已撤销", changeId = restored.id)
    }
    suspend fun activePreferences(remote: Boolean = false): List<Preference> {
        val useLearned = sessions.read("use_learned_preferences") != "false"
        val sources=knowledge?.all()?.associateBy { it.id }
        val today=LocalDate.now(clock).toString()
        return store.listPreferences().filter { p -> p.enabled && !p.sourceDeleted && (!p.learned || useLearned) && (p.validUntil == null || today<=p.validUntil) && (p.sourceId==null || sources==null || sources[p.sourceId]?.let { it.enabled && (!remote || it.allowRemote) && it.version==p.sourceVersion && (it.validFrom==null || today>=it.validFrom) && (it.validUntil==null || today<=it.validUntil) } == true) }
    }
    suspend fun useLearned() = sessions.read("use_learned_preferences") != "false"
    suspend fun setUseLearned(enabled: Boolean) = sessions.write("use_learned_preferences", enabled.toString())
    suspend fun proactiveEnabled() = sessions.read("habit_proactive") != "false"
    suspend fun setProactive(enabled: Boolean) = sessions.write("habit_proactive", enabled.toString())
    suspend fun nextProactive(): HabitCandidate? = mutex.withLock {
        if(!proactiveEnabled() || sessions.read("habit_last_prompt") == LocalDate.now(clock).toString()) return@withLock null
        val candidate = candidates().firstOrNull() ?: return@withLock null
        sessions.write("habit_last_prompt", LocalDate.now(clock).toString()); candidate
    }
    suspend fun statistics(): Map<String, Int> = store.observations().filter { it.origin == "user" && !LocalDate.parse(it.date).isBefore(LocalDate.now(clock).minusDays(13)) && !LocalDate.parse(it.date).isAfter(LocalDate.now(clock)) }.groupingBy { it.period }.eachCount()
    suspend fun learningEnabled() = sessions.read("learning_enabled") == "true"
    suspend fun setLearning(enabled: Boolean) { sessions.write("learning_enabled", enabled.toString()) }
    suspend fun recordChoice(item: PlanItem, origin: String = "user") {
        if (!learningEnabled() || origin != "user") return
        val period = item.schedule?.period ?: item.schedule?.time?.let { when(it.substringBefore(':').toInt()) { in 0..11 -> "morning"; in 12..17 -> "afternoon"; else -> "evening" } } ?: return
        val activity = item.label.ifBlank { item.title }.take(40)
        store.observeChoice(ChoiceObservation(UUID.randomUUID().toString(), activity, period, LocalDate.now(clock).toString(), origin))
    }
    suspend fun candidates(): List<HabitCandidate> {
        if (!learningEnabled()) return emptyList()
        val today = LocalDate.now(clock)
        return store.observations().filter { it.origin == "user" && !LocalDate.parse(it.date).isBefore(today.minusDays(13)) && !LocalDate.parse(it.date).isAfter(today) }
            .groupBy { it.activity }.mapNotNull { (activity, events) ->
                val group = events.groupBy { it.period }.maxByOrNull { it.value.size } ?: return@mapNotNull null
                val days = group.value.map { it.date }.distinct().size
                if (days < 3 || group.value.size.toDouble() / events.size < 0.75) return@mapNotNull null
                val id = "${activity}:${group.key}"
                val cooldown = sessions.read("habit_cooldown:$id")?.let(LocalDate::parse)
                val known = activePreferences().any { it.key == "activity.preferred_period" && it.scope == activity && it.value == group.key }
                if (known || (cooldown != null && today.isBefore(cooldown))) null else HabitCandidate(id, activity, group.key, group.value.size, days)
            }
    }
    suspend fun accept(candidate: HabitCandidate) { setPreference("activity.preferred_period", candidate.period, "用户确认习惯候选：${candidate.count} 次选择", candidate.activity, learned = true) }
    suspend fun dismiss(candidate: HabitCandidate) { sessions.write("habit_cooldown:${candidate.id}", LocalDate.now(clock).plusDays(30).toString()) }
}
