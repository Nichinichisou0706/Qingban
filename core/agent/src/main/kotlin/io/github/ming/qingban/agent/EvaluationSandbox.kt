package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.util.UUID

/** Synthetic evaluation data only. Never delegates to the application's stores or scheduler. */
internal class EvaluationSandbox(clock: Clock) {
    val itemRows=MutableStateFlow<List<PlanItem>>(emptyList())
    private val changes=mutableListOf<StoredChange>(); private val batches=mutableListOf<StoredBatch>()
    private val itemStore=object:ItemStore {
        override fun observe()=itemRows
        override suspend fun all()=itemRows.value
        override suspend fun get(id:String)=all().firstOrNull { it.id==id }
        override suspend fun changeForRequest(requestKey:String)=changes.firstOrNull { it.requestKey==requestKey }
        override suspend fun latestChange()=changes.lastOrNull()
        override suspend fun getChange(id:String)=changes.firstOrNull { it.id==id }
        override suspend fun commit(before:PlanItem?,after:PlanItem,requestKey:String,timestamp:Long):StoredChange? {
            changeForRequest(requestKey)?.let { return it }
            if(get(after.id)?.version!=before?.version) return null
            return StoredChange(UUID.randomUUID().toString(),before,after,requestKey,timestamp).also { changes+=it; itemRows.value=all().filterNot { it.id==after.id }+after }
        }
        override suspend fun setDelivery(id:String,version:Int,state:DeliveryState) { itemRows.value=all().map { if(it.id==id && it.version==version) it.copy(delivery=state) else it } }
        override suspend fun batchForRequest(requestKey:String)=batches.firstOrNull { it.requestKey==requestKey }
        override suspend fun getBatch(id:String)=batches.firstOrNull { it.id==id }
        override suspend fun commitBatch(entries:List<Pair<PlanItem?,PlanItem>>,requestKey:String,timestamp:Long):StoredBatch? {
            batchForRequest(requestKey)?.let { return it }
            if(entries.any { get(it.second.id)?.version!=it.first?.version }) return null
            val saved=entries.mapIndexed { index,(before,after) -> StoredChange(UUID.randomUUID().toString(),before,after,"$requestKey:$index",timestamp) }
            val ids=saved.map { it.after.id }.toSet(); itemRows.value=all().filterNot { it.id in ids }+saved.map { it.after }; changes+=saved
            return StoredBatch("batch:${UUID.randomUUID()}",saved,requestKey,timestamp).also { batches+=it }
        }
    }
    private val sessions=object:SessionStore {
        val values=mutableMapOf<String,String>()
        override suspend fun read(key:String)=values[key]
        override suspend fun write(key:String,value:String) { values[key]=value }
        override suspend fun remove(key:String) { values.remove(key) }
    }
    private val sources=MutableStateFlow<List<KnowledgeSource>>(emptyList())
    private val knowledge=object:KnowledgeStore {
        override fun observe()=sources
        override suspend fun all()=sources.value
        override suspend fun save(source:KnowledgeSource) { sources.value=all().filterNot { it.id==source.id }+source }
        override suspend fun delete(id:String) { sources.value=all().filterNot { it.id==id } }
    }
    private val preferences=MutableStateFlow<List<Preference>>(emptyList())
    private val memory=object:MemoryStore {
        override fun preferences()=preferences
        override suspend fun listPreferences()=preferences.value
        override suspend fun save(preference:Preference) { preferences.value=listPreferences().filterNot { it.key==preference.key && it.scope==preference.scope }+preference }
        override suspend fun delete(key:String,scope:String) { preferences.value=listPreferences().filterNot { it.key==key && it.scope==scope } }
        override suspend fun observations()=emptyList<ChoiceObservation>()
        override suspend fun observeChoice(observation:ChoiceObservation)=Unit
        override suspend fun clearObservations()=Unit
    }
    val services=Services(ItemService(itemStore,object:ReminderScheduler { override suspend fun reconcile(item:PlanItem)=ScheduleResult(DeliveryState.SCHEDULED) },clock,sessions),KnowledgeService(knowledge,clock,memory),MemoryService(memory,sessions,clock,knowledge),sessions,clock)
}
