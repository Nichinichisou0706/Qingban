package io.github.ming.qingban.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class ItemService(val store: ItemStore, private val scheduler: ReminderScheduler, private val clock: Clock, private val sessions: SessionStore? = null) {
    private val mutex = Mutex()
    private class Rejected(val code: String, message: String) : IllegalArgumentException(message)
    private fun reject(code: String, message: String): Nothing = throw Rejected(code, message)
    private fun PlanItem.draft() = ItemDraft(kind, title, detail, schedule, deadline, reminder, alarm, label, dependsOn, durationMinutes, pinned, order, checklist)
    private fun make(d: ItemDraft, id: String) = PlanItem(id, kind = d.kind, title = d.title.trim(), detail = d.detail, schedule = d.schedule, deadline = d.deadline, reminder = d.reminder, alarm = d.alarm, label = d.label, createdAt = clock.millis(), dependsOn = d.dependsOn, durationMinutes = d.durationMinutes, pinned = d.pinned, order = d.order, checklist = d.checklist)
    private fun validate(item: PlanItem, future: Boolean = true) {
        ScheduleEngine.validate(item.draft())?.let { reject("invalid_item", it) }
        if (future && item.status == ItemStatus.OPEN && (item.kind == ItemKind.ALARM || item.reminder != null) && ScheduleEngine.nextTrigger(item, clock) == null) {
            RecurrenceRules.unavailableMessage(item, clock)?.let { reject("calendar_unavailable", it) }
            reject("past_time", "没有后续可响铃日期，请调整生效日期或重复规则")
        }
    }
    suspend fun create(draft: ItemDraft, requestKey: String = UUID.randomUUID().toString()): OperationResult = mutex.withLock {
        store.changeForRequest(requestKey)?.let { return@withLock OperationResult("ok", "已保存，无需重复创建", store.get(it.after.id), it.id) }
        guarded {
            val item = make(draft, UUID.randomUUID().toString()); validate(item)
            DependencyPlanner.validate(store.all().associateBy { it.id } + (item.id to item))?.let { reject("invalid_dependency", it) }
            persist(null, item, requestKey)
        }
    }
    suspend fun update(id: String, expectedVersion: Int, patch: ItemPatch, scope: EditScope = EditScope.THIS_OCCURRENCE, occurrenceDate: String? = null, requestKey: String = UUID.randomUUID().toString()): OperationResult = mutex.withLock {
        store.changeForRequest(requestKey)?.let { return@withLock OperationResult("ok", "已处理", store.get(it.after.id), it.id) }
        store.batchForRequest(requestKey)?.let { return@withLock OperationResult("ok", "已处理", changeId = it.id) }
        guarded {
            val before = store.get(id) ?: reject("not_found", "没找到这件事")
            val entries = prepare(before, expectedVersion, patch, scope, occurrenceDate)
            val snapshot = store.all().associateBy { it.id }.toMutableMap(); entries.forEach { snapshot[it.second.id] = it.second }
            DependencyPlanner.validate(snapshot)?.let { reject("invalid_dependency", it) }
            if(entries.size == 1) persist(entries[0].first, entries[0].second, requestKey) else persistBatch(entries, requestKey)
        }
    }
    private fun prepare(before: PlanItem, expectedVersion: Int, p: ItemPatch, scope: EditScope, occurrenceDate: String?): List<Pair<PlanItem?, PlanItem>> {
        if(before.deleted) reject("not_found", "事项已删除，可撤销删除后再修改")
        if(before.version != expectedVersion) reject("version_conflict", "事项已经变化，请刷新后再修改")
        val recurrence = before.schedule?.recurrence
        if(recurrence != null && scope == EditScope.THIS_AND_FUTURE) {
            val date = occurrenceDate?.let(LocalDate::parse) ?: reject("missing_occurrence_date", "请选择此后系列的起始日期")
            if(!ScheduleEngine.occursOn(before, date)) reject("invalid_occurrence", "这一天没有该事项")
            if(date == LocalDate.parse(recurrence.startDate)) return prepare(before, expectedVersion, p, EditScope.WHOLE_SERIES, null)
            val old = before.copy(version = before.version + 1, schedule = before.schedule!!.copy(recurrence = recurrence.copy(endDate = date.minusDays(1).toString())), overrides = before.overrides.filter { LocalDate.parse(it.originalDate).isBefore(date) }, delivery = DeliveryState.NONE)
            val supplied = (p.schedule as? FieldPatch.Set)?.value
            if(p.schedule is FieldPatch.Set && supplied?.recurrence == null) reject("invalid_item", "此后系列请保留重复规则，或选择取消此后各次")
            val newSchedule = (supplied ?: before.schedule).copy(date = null, recurrence = (supplied?.recurrence ?: recurrence).copy(startDate = date.toString()))
            val next = patched(before.copy(id = UUID.randomUUID().toString(), version = 0, schedule = newSchedule, overrides = before.overrides.filter { !LocalDate.parse(it.originalDate).isBefore(date) }, delivery = DeliveryState.NONE), p.copy(schedule = FieldPatch.Set(newSchedule)))
            validate(old, false); validate(next)
            return listOf(before to old, null to next)
        }
        val after = if(recurrence != null && scope == EditScope.THIS_OCCURRENCE) {
            val date = occurrenceDate ?: reject("missing_occurrence_date", "请选择要修改的那一次日期")
            if(!ScheduleEngine.occursOn(before, LocalDate.parse(date)) && before.overrides.none { (it.originalDate == date || it.schedule?.date == date) && it.status != ItemStatus.OPEN }) reject("invalid_occurrence", "这一天没有该事项")
            if(p.title != null || p.detail != null || p.deadline !is FieldPatch.Unchanged || p.reminder !is FieldPatch.Unchanged || p.alarm != null || p.label != null || p.dependsOn != null || p.durationMinutes !is FieldPatch.Unchanged || p.pinned != null || p.order != null) reject("unsupported_patch", "单次修改支持日期、时间、清单与完成状态；其他字段请选择整个系列")
            val previous = before.overrides.firstOrNull { it.originalDate == date || it.schedule?.date == date }
            val original = previous?.originalDate ?: date
            val replacement = resolve(p.schedule, previous?.schedule)
            replacement?.let { validate(before.copy(schedule = it, overrides = emptyList()), p.status != ItemStatus.CANCELLED && p.status != ItemStatus.COMPLETED) }
            p.checklist?.let { ScheduleEngine.validate(before.draft().copy(checklist=it))?.let { message -> reject("invalid_item",message) } }
            val override = OccurrenceOverride(original, replacement, p.status ?: previous?.status ?: ItemStatus.OPEN,p.checklist ?: previous?.checklist)
            before.copy(version = before.version + 1, overrides = before.overrides.filterNot { it.originalDate == original } + override, delivery = DeliveryState.NONE)
        } else patched(before, p)
        validate(after, p.schedule is FieldPatch.Set || (p.status == ItemStatus.OPEN && before.status != ItemStatus.OPEN))
        return listOf(before to after)
    }
    private fun patched(before: PlanItem, p: ItemPatch) = before.copy(version = before.version + 1, title = p.title?.trim() ?: before.title, detail = p.detail ?: before.detail, schedule = resolve(p.schedule, before.schedule), deadline = resolve(p.deadline, before.deadline), reminder = resolve(p.reminder, before.reminder), alarm = p.alarm ?: before.alarm, label = p.label ?: before.label, status = p.status ?: before.status, delivery = DeliveryState.NONE, dependsOn = p.dependsOn ?: before.dependsOn, durationMinutes = resolve(p.durationMinutes, before.durationMinutes), pinned = p.pinned ?: before.pinned, order = p.order ?: before.order, checklist = p.checklist ?: before.checklist)
    suspend fun applyBatch(commands: List<BatchCommand>, requestKey: String = UUID.randomUUID().toString()): OperationResult = mutex.withLock {
        store.batchForRequest(requestKey)?.let { return@withLock OperationResult("ok", "方案已保存，无需重复执行", changeId = it.id) }
        guarded {
            require(commands.isNotEmpty() && (commands.size <= 50 || commands.all { it is BatchCommand.Delete })) { "一次最多保存 50 项" }
            val snapshot = store.all().associateBy { it.id }.toMutableMap()
            val entries = mutableListOf<Pair<PlanItem?, PlanItem>>()
            commands.forEach { command ->
                val prepared = when(command) {
                    is BatchCommand.Create -> { require(command.id !in snapshot) { "事项 ID 已存在" }; val item = make(command.draft, command.id); validate(item); listOf(null to item) }
                    is BatchCommand.Update -> prepare(snapshot[command.id] ?: reject("not_found", "事项不存在"), command.version, command.patch, command.scope, command.occurrenceDate)
                    is BatchCommand.Delete -> {
                        val before = snapshot[command.id] ?: reject("not_found", "事项不存在")
                        if(before.deleted) reject("not_found", "事项已删除，请刷新列表")
                        if(before.version != command.version) reject("version_conflict", "事项已经变化，全部未删除，请刷新列表")
                        if(before.schedule?.recurrence != null && command.scope == EditScope.THIS_OCCURRENCE)
                            prepare(before,command.version,ItemPatch(status=ItemStatus.CANCELLED),command.scope,command.occurrenceDate)
                        else {
                            require(before.schedule?.recurrence==null || command.scope == EditScope.WHOLE_SERIES) { "删除请选择仅本次或整个系列" }
                            listOf(before to before.copy(version=before.version+1,status=ItemStatus.CANCELLED,deleted=true,delivery=DeliveryState.NONE))
                        }
                    }
                }
                require(prepared.none { p -> entries.any { it.second.id == p.second.id } }) { "同一方案中不能重复修改同一事项" }
                prepared.forEach { snapshot[it.second.id] = it.second }; entries += prepared
            }
            val removed = entries.map { it.second }.filter { it.deleted }.map { it.id }.toSet()
            if(removed.isNotEmpty()) snapshot.values.toList().filter { !it.deleted && it.dependsOn.any(removed::contains) }.forEach { item ->
                val replacement=item.copy(dependsOn=item.dependsOn-removed)
                val existing=entries.indexOfFirst { it.second.id==item.id }
                if(existing>=0) entries[existing]=entries[existing].first to replacement
                else entries+=item to replacement.copy(version=item.version+1,delivery=DeliveryState.NONE)
                snapshot[item.id]=entries.lastOrNull { it.second.id==item.id }!!.second
            }
            DependencyPlanner.validate(snapshot)?.let { reject("invalid_dependency", it) }
            persistBatch(entries, requestKey)
        }
    }
    suspend fun deleteItems(items: List<PlanItem>, scope: EditScope = EditScope.WHOLE_SERIES, occurrenceDate: String? = null): OperationResult =
        applyBatch(items.map { BatchCommand.Delete(it.id,it.version,scope,occurrenceDate) })
    suspend fun createSequence(drafts: List<ItemDraft>): OperationResult {
        val ids = drafts.map { UUID.randomUUID().toString() }
        return applyBatch(drafts.mapIndexed { i, d -> BatchCommand.Create(d.copy(dependsOn = d.dependsOn + if(i == 0) emptySet() else setOf(ids[i-1])), ids[i]) })
    }
    suspend fun undo(changeId: String? = null, requestKey: String = UUID.randomUUID().toString()): OperationResult = mutex.withLock {
        store.changeForRequest(requestKey)?.let { return@withLock OperationResult("ok", "已撤销，无需重复执行", store.get(it.after.id), it.id) }
        store.batchForRequest(requestKey)?.let { return@withLock OperationResult("ok", "已撤销，无需重复执行", changeId=it.id) }
        guarded {
            if(changeId?.startsWith("batch:") == true) {
                val batch = store.getBatch(changeId) ?: reject("not_found", "没有可撤销的方案")
                val entries = batch.changes.map { change ->
                    val current = store.get(change.after.id) ?: reject("not_found", "事项已不存在")
                    if(current.version != change.after.version) reject("version_conflict", "方案中的事项之后已修改，不能覆盖后续操作")
                    current to (change.before?.copy(version = current.version + 1, delivery = DeliveryState.NONE) ?: current.copy(version = current.version + 1, status = ItemStatus.CANCELLED, delivery = DeliveryState.NONE))
                }
                val snapshot=store.all().associateBy { it.id }.toMutableMap(); entries.forEach { snapshot[it.second.id]=it.second }
                DependencyPlanner.validate(snapshot)?.let { reject("invalid_dependency","不能撤销：$it") }
                return@guarded persistBatch(entries, requestKey)
            }
            val change = (if(changeId == null) store.latestChange() else store.getChange(changeId)) ?: reject("not_found", "没有可撤销的操作")
            val current = store.get(change.after.id) ?: reject("not_found", "事项已不存在")
            if(current.version != change.after.version) reject("version_conflict", "事项之后已修改，不能覆盖后续操作")
            val restored = change.before?.copy(version = current.version + 1, delivery = DeliveryState.NONE) ?: current.copy(version = current.version + 1, status = ItemStatus.CANCELLED, delivery = DeliveryState.NONE)
            DependencyPlanner.validate(store.all().associateBy { it.id }+(restored.id to restored))?.let { reject("invalid_dependency","不能撤销：$it") }
            persist(current, restored, requestKey)
        }
    }
    suspend fun restoreSchedules() = mutex.withLock { store.all().forEach { schedule(it) } }
    suspend fun reconcileCurrent(id: String, expectedVersion: Int) = mutex.withLock { store.get(id)?.takeIf { it.version == expectedVersion }?.let { schedule(it) } }
    private suspend fun schedule(item: PlanItem): ScheduleResult {
        val result = try { scheduler.reconcile(item) } catch(e: Exception) { if(e is CancellationException) throw e; ScheduleResult(DeliveryState.FAILED, "调度失败，请重试") }
        val effective=if(result.state==DeliveryState.NONE && item.delivery==DeliveryState.FIRED && item.schedule?.recurrence==null) result.copy(state=DeliveryState.FIRED) else result
        store.setDelivery(item.id, item.version, effective.state); return effective
    }
    private suspend fun persist(before: PlanItem?, after: PlanItem, requestKey: String): OperationResult {
        val change = store.commit(before, after, requestKey, clock.millis()) ?: reject("version_conflict", "事项已变化，请重试")
        sessions?.write("last_change_id", change.id)
        val delivery = schedule(after); val partial = delivery.state in setOf(DeliveryState.FAILED, DeliveryState.PERMISSION_REQUIRED)
        return OperationResult(if(partial) "partial" else "ok", if(partial) "已保存，${delivery.message}" else "已保存", after.copy(delivery = delivery.state), change.id)
    }
    private suspend fun persistBatch(entries: List<Pair<PlanItem?, PlanItem>>, requestKey: String): OperationResult {
        val batch = store.commitBatch(entries, requestKey, clock.millis()) ?: reject("version_conflict", "方案目标已变化，全部未保存，请刷新方案")
        sessions?.write("last_change_id", batch.id)
        val results = batch.changes.map { schedule(it.after) }
        val failed = results.count { it.state in setOf(DeliveryState.FAILED, DeliveryState.PERMISSION_REQUIRED) }
        return OperationResult(if(failed > 0) "partial" else "ok", "已统一保存 ${entries.size} 项${if(failed > 0) "，其中 $failed 项提醒待启用" else "，可一起撤销"}", changeId = batch.id)
    }
    private suspend fun guarded(block: suspend () -> OperationResult): OperationResult = try { block() } catch(e: Exception) { if(e is CancellationException) throw e; OperationResult("error", e.message ?: "参数不正确", errorCode = (e as? Rejected)?.code ?: "invalid_item") }
    private fun <T> resolve(patch: FieldPatch<T>, original: T?): T? = when(patch) { FieldPatch.Unchanged -> original; is FieldPatch.Set -> patch.value }
}
