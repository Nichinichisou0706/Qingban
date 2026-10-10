package io.github.ming.qingban.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ming.qingban.domain.*
import io.github.ming.qingban.agent.AgentCoordinator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class ItemListViewModel(private val services: Services) : ViewModel() {
    val items = services.items.store.observe().map { list -> list.filterNot { it.deleted } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val result = MutableStateFlow<OperationResult?>(null)
    fun consumeResult(expected: OperationResult) { if(result.value==expected) result.value=null }
    fun save(draft: ItemDraft, item: PlanItem?, scope: EditScope = EditScope.WHOLE_SERIES, date: String? = null) = viewModelScope.launch {
        val patch = if(item?.schedule?.recurrence != null && scope == EditScope.THIS_OCCURRENCE) ItemPatch(schedule = FieldPatch.Set(draft.schedule?.copy(recurrence = null)),checklist=draft.checklist) else ItemPatch(title = draft.title, detail = draft.detail, schedule = FieldPatch.Set(draft.schedule), deadline = FieldPatch.Set(draft.deadline), reminder = FieldPatch.Set(draft.reminder), alarm = draft.alarm, label = draft.label, dependsOn = draft.dependsOn, durationMinutes = FieldPatch.Set(draft.durationMinutes), pinned = draft.pinned, order = draft.order, checklist = draft.checklist)
        result.value = if(item == null) services.items.create(draft) else services.items.update(item.id, item.version, patch, scope, date)
        if(result.value?.status != "error" && (item == null || item.schedule?.time != draft.schedule?.time || item.schedule?.period != draft.schedule?.period)) result.value?.item?.let { services.memory.recordChoice(it.copy(schedule=draft.schedule)) }
    }
    fun complete(item: PlanItem, date: String?) = viewModelScope.launch { result.value = services.items.update(item.id, item.version, ItemPatch(status = if(item.status == ItemStatus.COMPLETED) ItemStatus.OPEN else ItemStatus.COMPLETED), occurrenceDate = if(item.schedule?.recurrence != null) date else null) }
    fun enableAlarm(item: PlanItem, enabled: Boolean) = viewModelScope.launch {
        val current=item.schedule
        val alarmTime=current?.time
        val schedule=if(enabled && current?.recurrence==null && alarmTime!=null) FieldPatch.Set(AlarmSchedules.nextOneShot(alarmTime,services.clock,current?.date)) else FieldPatch.Unchanged
        result.value = services.items.update(item.id, item.version, ItemPatch(status = if(enabled) ItemStatus.OPEN else ItemStatus.CANCELLED,schedule=schedule), scope = EditScope.WHOLE_SERIES)
    }
    fun restore(item: PlanItem) = viewModelScope.launch { result.value=services.items.update(item.id,item.version,ItemPatch(status=ItemStatus.OPEN),EditScope.WHOLE_SERIES) }
    fun cancel(item: PlanItem) = viewModelScope.launch { result.value = services.items.update(item.id, item.version, ItemPatch(status = ItemStatus.CANCELLED), scope = EditScope.WHOLE_SERIES) }
    fun cancel(item: PlanItem, scope: EditScope, date: String?) = viewModelScope.launch { result.value = services.items.update(item.id,item.version,ItemPatch(status=ItemStatus.CANCELLED),scope,date) }
    fun pin(item: PlanItem) = viewModelScope.launch { result.value = services.items.update(item.id,item.version,ItemPatch(pinned = !item.pinned),EditScope.WHOLE_SERIES) }
    fun check(item: PlanItem, index: Int, date: String? = null) = viewModelScope.launch { if(index in item.checklist.indices) result.value=services.items.update(item.id,item.version,ItemPatch(checklist=item.checklist.mapIndexed { i,entry -> if(i==index) entry.copy(completed=!entry.completed) else entry }),if(item.isHabitTask && date!=null) EditScope.THIS_OCCURRENCE else EditScope.WHOLE_SERIES,date) }
    fun move(items: List<PlanItem>, id: String, direction: Int) = viewModelScope.launch {
        if(items.size>50) { result.value=OperationResult("error","列表超过 50 项时请使用置顶整理"); return@launch }
        val list=DependencyPlanner.ordered(items).toMutableList(); val index=list.indexOfFirst { it.id==id }; val target=index+direction
        if(index !in list.indices || target !in list.indices) return@launch
        val item=list.removeAt(index); list.add(target,item)
        result.value=services.items.applyBatch(list.mapIndexed { i,p -> BatchCommand.Update(p.id,p.version,ItemPatch(order=i.toLong()),EditScope.WHOLE_SERIES) })
    }
    fun undo() = viewModelScope.launch { result.value = services.undo() }
    fun delete(items: List<PlanItem>, scope: EditScope, date: String?) = viewModelScope.launch { result.value=services.items.deleteItems(items,scope,date) }
    fun split(item: PlanItem?,drafts: List<ItemDraft>)=viewModelScope.launch {
        val ids=drafts.map { java.util.UUID.randomUUID().toString() }
        val commands=mutableListOf<BatchCommand>()
        if(item!=null) commands+=BatchCommand.Update(item.id,item.version,ItemPatch(status=ItemStatus.CANCELLED),EditScope.WHOLE_SERIES)
        commands+=drafts.mapIndexed { i,d -> BatchCommand.Create(d,ids[i]) }
        if(item!=null) services.items.store.all().filter { it.status!=ItemStatus.CANCELLED && item.id in it.dependsOn }.forEach { next -> commands+=BatchCommand.Update(next.id,next.version,ItemPatch(dependsOn=next.dependsOn-item.id+ids),EditScope.WHOLE_SERIES) }
        result.value=services.items.applyBatch(commands)
    }
}
class DashboardViewModel(services: Services) : ViewModel() {
    val items=services.items.store.observe().map { list -> list.filterNot { it.deleted } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val documents=services.knowledge.store.observe().map { it.size }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val preferences=services.memory.store.preferences().map { it.size }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
}
class AssistantViewModel(val coordinator: AgentCoordinator, private val memory: MemoryService) : ViewModel() {
    val session = coordinator.session
    val busy = coordinator.busy
    val habit = MutableStateFlow<HabitCandidate?>(null)
    private var requestJob: Job? = null
    init { viewModelScope.launch { coordinator.load(); habit.value = memory.nextProactive() } }
    fun acceptHabit() = viewModelScope.launch { habit.value?.let { memory.accept(it) }; habit.value = null }
    fun dismissHabit() = viewModelScope.launch { habit.value?.let { memory.dismiss(it) }; habit.value = null }
    fun send(text: String) { if(requestJob?.isActive!=true) requestJob=viewModelScope.launch { coordinator.send(text) } }
    fun retry(index: Int = -1) { if(requestJob?.isActive!=true) requestJob=viewModelScope.launch { coordinator.retry(index) } }
    fun stop() { requestJob?.cancel() }
    fun clear() = viewModelScope.launch { coordinator.clear() }
    fun approve(id: String, changes: List<io.github.ming.qingban.agent.ProposedAction>? = null) = viewModelScope.launch { coordinator.approveProposal(id,changes) }
    fun resumeLocal(index: Int) = viewModelScope.launch { coordinator.resumeLocal(index) }
    fun resumeRequest(index: Int) = viewModelScope.launch { coordinator.resumeRequest(index) }
    fun undo(id: String) = viewModelScope.launch { coordinator.undo(id) }
}
class LibraryViewModel(private val services: Services) : ViewModel() {
    val sources = services.knowledge.store.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val error = MutableStateFlow<String?>(null)
    val saving=MutableStateFlow(false)
    fun import(title: String, text: String, remote: Boolean, attachment: KnowledgeAttachment? = null, onSaved: ()->Unit) = viewModelScope.launch {
        if(saving.value) return@launch
        saving.value=true; error.value=null
        try { services.knowledge.importText(title,text,remote,attachment); onSaved() }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error.value=e.message ?: "保存未完成，请重试" }
        finally { saving.value=false }
    }
    fun delete(source: KnowledgeSource) = viewModelScope.launch { services.knowledge.delete(source.id) }
    fun remote(source: KnowledgeSource, enabled: Boolean) = viewModelScope.launch { services.knowledge.allowRemote(source.id, enabled) }
    fun update(source: KnowledgeSource, title: String, text: String, enabled: Boolean, from: String?, until: String?, origin: String, reliability: String, onSaved: ()->Unit) = viewModelScope.launch {
        if(saving.value) return@launch
        saving.value=true; error.value=null
        try { services.knowledge.update(source.id,title,text,enabled,from,until,origin,reliability); onSaved() }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error.value=e.message ?: "保存未完成，请重试" }
        finally { saving.value=false }
    }
    fun delete(source: KnowledgeSource, linked: Boolean, platform: PlatformActions? = null) = viewModelScope.launch { runCatching { services.knowledge.delete(source.id,linked); source.attachment?.let { platform?.removeOriginal(it.id) } }.onFailure { error.value=it.message } }
}
class MemoryViewModel(val service: MemoryService) : ViewModel() {
    val error=MutableStateFlow<String?>(null)
    private fun operate(block:suspend ()->Unit)=viewModelScope.launch { try { block() } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error.value=e.message ?: "操作未完成，请重试" } }
    val preferences = service.store.preferences().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val learning = MutableStateFlow(false)
    val candidates = MutableStateFlow<List<HabitCandidate>>(emptyList())
    val useLearned = MutableStateFlow(true)
    val proactive = MutableStateFlow(true)
    val statistics = MutableStateFlow<Map<String,Int>>(emptyMap())
    init { refresh() }
    fun refresh() = viewModelScope.launch { learning.value = service.learningEnabled(); candidates.value = service.candidates(); useLearned.value = service.useLearned(); proactive.value = service.proactiveEnabled(); statistics.value = service.statistics() }
    fun useLearned(enabled: Boolean) = viewModelScope.launch { service.setUseLearned(enabled); useLearned.value = enabled }
    fun proactive(enabled: Boolean) = viewModelScope.launch { service.setProactive(enabled); proactive.value = enabled }
    fun enabled(preference: Preference, value: Boolean) = viewModelScope.launch { service.setEnabled(preference.key,preference.scope,value) }
    fun learning(enabled: Boolean) = viewModelScope.launch { service.setLearning(enabled); learning.value = enabled; candidates.value = service.candidates() }
    fun accept(candidate: HabitCandidate) = viewModelScope.launch { service.accept(candidate); candidates.value = service.candidates() }
    fun dismiss(candidate: HabitCandidate) = viewModelScope.launch { service.dismiss(candidate); candidates.value = service.candidates() }
    fun delete(preference: Preference) = viewModelScope.launch { service.forget(preference.key, preference.scope) }
    fun clearLearning() = viewModelScope.launch { service.store.clearObservations(); candidates.value = emptyList(); statistics.value=emptyMap() }
    fun save(key: String, value: String, scope: String, until: String? = null, sourceId: String? = null, sourceVersion: Int? = null) = operate { service.setPreference(key, value, "用户在偏好页面填写", scope, until, sourceId, sourceVersion) }
}
fun <T : ViewModel> factory(create: () -> T): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}
