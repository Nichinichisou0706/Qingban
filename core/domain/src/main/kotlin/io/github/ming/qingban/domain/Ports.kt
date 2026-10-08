package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.Flow
import java.time.Clock

interface ItemStore {
    fun observe(): Flow<List<PlanItem>>
    suspend fun all(): List<PlanItem>
    suspend fun get(id: String): PlanItem?
    suspend fun commit(before: PlanItem?, after: PlanItem, requestKey: String, timestamp: Long): StoredChange?
    suspend fun changeForRequest(requestKey: String): StoredChange?
    suspend fun latestChange(): StoredChange?
    suspend fun getChange(id: String): StoredChange?
    suspend fun setDelivery(id: String, version: Int, state: DeliveryState)
    suspend fun commitBatch(entries: List<Pair<PlanItem?, PlanItem>>, requestKey: String, timestamp: Long): StoredBatch? = error("存储不支持事务")
    suspend fun batchForRequest(requestKey: String): StoredBatch? = null
    suspend fun getBatch(id: String): StoredBatch? = null
}
interface ReminderScheduler { suspend fun reconcile(item: PlanItem): ScheduleResult }
interface KnowledgeStore {
    fun observe(): Flow<List<KnowledgeSource>>
    suspend fun all(): List<KnowledgeSource>
    suspend fun save(source: KnowledgeSource)
    suspend fun delete(id: String)
}
interface MemoryStore {
    fun preferences(): Flow<List<Preference>>
    suspend fun listPreferences(): List<Preference>
    suspend fun save(preference: Preference)
    suspend fun delete(key: String, scope: String = "")
    suspend fun observations(): List<ChoiceObservation>
    suspend fun observeChoice(observation: ChoiceObservation)
    suspend fun clearObservations()
}
interface SessionStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
    /** Persistent adapters share this transaction with their associated MemoryStore. */
    suspend fun <T> transaction(block: suspend () -> T): T = block()
}
data class Services(val items: ItemService, val knowledge: KnowledgeService, val memory: MemoryService, val sessions: SessionStore, val clock: Clock, val focus: FocusService = FocusService(sessions,clock), val ui: UiCommands = UiCommands(sessions), val sounds: SoundLibrary = object: SoundLibrary {}, val review: DailyReviewService = DailyReviewService(items,sessions,clock),val habits: HabitService=HabitService(sessions,clock)) {
    suspend fun undo(changeId: String? = null, requestKey: String? = null): OperationResult {
        if(requestKey != null) {
            items.store.changeForRequest(requestKey)?.let { return OperationResult("ok", "已撤销，无需重复执行", items.store.get(it.after.id), it.id) }
            items.store.batchForRequest(requestKey)?.let { return OperationResult("ok", "已撤销，无需重复执行", changeId=it.id) }
            memory.changeForRequest(requestKey)?.let { return OperationResult("ok", "偏好已撤销，无需重复执行", changeId=it.id) }
        }
        val target = changeId ?: sessions.read("last_change_id")
        if(target?.startsWith("memory:") == true) return memory.undo(target, requestKey)
        if(target != null) return items.undo(target, requestKey ?: java.util.UUID.randomUUID().toString())
        return if((memory.latestChange()?.timestamp ?: 0) > (items.store.latestChange()?.timestamp ?: 0)) memory.undo(requestKey=requestKey) else items.undo(requestKey=requestKey ?: java.util.UUID.randomUUID().toString())
    }
}
