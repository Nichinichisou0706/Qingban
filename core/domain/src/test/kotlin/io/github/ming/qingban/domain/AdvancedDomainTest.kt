package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

private class TransactionalItems : ItemStore {
    val rows=mutableMapOf<String,PlanItem>(); val changes=mutableListOf<StoredChange>(); val batches=mutableListOf<StoredBatch>(); var conflict=false
    override fun observe()=MutableStateFlow(rows.values.toList())
    override suspend fun all()=rows.values.toList()
    override suspend fun get(id: String)=rows[id]
    override suspend fun commit(before: PlanItem?,after: PlanItem,requestKey: String,timestamp: Long): StoredChange? {
        if(conflict || rows[after.id]?.version!=before?.version) return null
        return StoredChange("c${changes.size}",before,after,requestKey,timestamp).also { rows[after.id]=after; changes+=it }
    }
    override suspend fun changeForRequest(requestKey: String)=changes.firstOrNull { it.requestKey==requestKey }
    override suspend fun latestChange()=changes.lastOrNull()
    override suspend fun getChange(id: String)=changes.firstOrNull { it.id==id }
    override suspend fun setDelivery(id: String,version: Int,state: DeliveryState) { rows[id]?.takeIf { it.version==version }?.let { rows[id]=it.copy(delivery=state) } }
    override suspend fun getBatch(id: String)=batches.firstOrNull { it.id==id }
    override suspend fun batchForRequest(requestKey: String)=batches.firstOrNull { it.requestKey==requestKey }
    override suspend fun commitBatch(entries: List<Pair<PlanItem?,PlanItem>>,requestKey: String,timestamp: Long): StoredBatch? {
        batchForRequest(requestKey)?.let { return it }
        if(conflict || entries.any { rows[it.second.id]?.version!=it.first?.version }) return null
        val saved=entries.mapIndexed { i,(before,after) -> StoredChange("c${changes.size}-$i",before,after,"$requestKey:$i",timestamp) }
        saved.forEach { rows[it.after.id]=it.after }; changes+=saved
        return StoredBatch("batch:${batches.size}",saved,requestKey,timestamp).also { batches+=it }
    }
}
class AdvancedDomainTest {
    private val clock=Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"),ZoneId.of("Asia/Shanghai"))
    private val scheduler=object : ReminderScheduler { override suspend fun reconcile(item: PlanItem)=ScheduleResult(DeliveryState.NONE) }
    private fun service(store: TransactionalItems)=ItemService(store,scheduler,clock)
    @Test fun invalidBatchDoesNotSaveAnyItem()=runBlocking { val store=TransactionalItems(); val result=service(store).applyBatch(listOf(BatchCommand.Create(ItemDraft(ItemKind.TASK,"A")),BatchCommand.Create(ItemDraft(ItemKind.ALARM,"B")))); assertEquals("error",result.status); assertTrue(store.rows.isEmpty()); assertTrue(store.changes.isEmpty()) }
    @Test fun batchDeduplicatesAcrossRepeatedApprovals()=runBlocking { val store=TransactionalItems(); val s=service(store); val actions=listOf(BatchCommand.Create(ItemDraft(ItemKind.TASK,"A")),BatchCommand.Create(ItemDraft(ItemKind.TASK,"B"))); s.applyBatch(actions,"same"); s.applyBatch(actions,"same"); assertEquals(2,store.rows.size); assertEquals(1,store.batches.size) }
    @Test fun wholeBatchUndoRestoresEveryItem()=runBlocking { val store=TransactionalItems(); val s=service(store); val result=s.createSequence(listOf(ItemDraft(ItemKind.TASK,"写方案"),ItemDraft(ItemKind.TASK,"发客户"))); assertEquals("ok",s.undo(result.changeId).status); assertTrue(store.rows.values.all { it.status==ItemStatus.CANCELLED }) }
    @Test fun laterChangeBlocksWholeBatchUndo()=runBlocking { val store=TransactionalItems(); val s=service(store); val result=s.createSequence(listOf(ItemDraft(ItemKind.TASK,"A"),ItemDraft(ItemKind.TASK,"B"))); val a=store.rows.values.first(); s.update(a.id,1,ItemPatch(title="changed")); assertEquals("version_conflict",s.undo(result.changeId).errorCode); assertTrue(store.rows.values.all { it.status==ItemStatus.OPEN }) }
    @Test fun databaseConflictDoesNotScheduleUnsavedBatch()=runBlocking { val store=TransactionalItems().apply { conflict=true }; var count=0; val s=ItemService(store,object : ReminderScheduler { override suspend fun reconcile(item: PlanItem): ScheduleResult { count++; return ScheduleResult(DeliveryState.NONE) } },clock); assertEquals("error",s.applyBatch(listOf(BatchCommand.Create(ItemDraft(ItemKind.TASK,"A")))).status); assertEquals(0,count); assertTrue(store.rows.isEmpty()) }
    @Test fun cyclicDependenciesAreRejectedBeforeSaving()=runBlocking { val store=TransactionalItems(); val result=service(store).applyBatch(listOf(BatchCommand.Create(ItemDraft(ItemKind.TASK,"A",dependsOn=setOf("b")),"a"),BatchCommand.Create(ItemDraft(ItemKind.TASK,"B",dependsOn=setOf("a")),"b"))); assertEquals("invalid_dependency",result.errorCode); assertTrue(store.rows.isEmpty()) }
    @Test fun dependencyMustReferToExistingItem()=runBlocking { assertEquals("invalid_dependency",service(TransactionalItems()).create(ItemDraft(ItemKind.TASK,"A",dependsOn=setOf("missing"))).errorCode) }
    @Test fun dependencyOrderWinsOverPinnedCard() { val a=PlanItem("a",kind=ItemKind.TASK,title="A"); val b=PlanItem("b",kind=ItemKind.TASK,title="B",dependsOn=setOf("a"),pinned=true); assertEquals(listOf("a","b"),DependencyPlanner.ordered(listOf(b,a)).map { it.id }) }
    @Test fun explicitDurationDetectsOverlap() { val a=PlanItem("a",kind=ItemKind.TASK,title="A",schedule=ScheduleSpec("2026-10-07","10:00"),durationMinutes=60); val b=a.copy(id="b",title="B",schedule=ScheduleSpec("2026-10-07","10:30")); assertEquals(listOf("B"),DependencyPlanner.conflicts(a,listOf(b))) }
    @Test fun pointTimesDoNotInventDurationConflict() { val a=PlanItem("a",kind=ItemKind.TASK,title="A",schedule=ScheduleSpec("2026-10-07","10:00")); val b=a.copy(id="b",schedule=ScheduleSpec("2026-10-07","10:01")); assertTrue(DependencyPlanner.conflicts(a,listOf(b)).isEmpty()) }
    @Test fun futureSeriesSplitKeepsEarlierOccurrences()=runBlocking { val store=TransactionalItems(); val s=service(store); val item=s.create(ItemDraft(ItemKind.TASK,"练琴",schedule=ScheduleSpec(time="18:00",recurrence=RecurrenceSpec(weekdays=setOf(2,4),startDate="2026-10-01")))).item!!; val changed=s.update(item.id,item.version,ItemPatch(schedule=FieldPatch.Set(item.schedule!!.copy(time="20:00"))),EditScope.THIS_AND_FUTURE,"2026-10-08"); assertEquals("ok",changed.status); val old=store.rows[item.id]!!; val next=store.rows.values.single { it.id!=item.id }; assertTrue(ScheduleEngine.occursOn(old,LocalDate.parse("2026-10-06"))); assertFalse(ScheduleEngine.occursOn(old,LocalDate.parse("2026-10-08"))); assertEquals("20:00",next.schedule!!.time); assertTrue(ScheduleEngine.occursOn(next,LocalDate.parse("2026-10-08"))); assertEquals("ok",s.undo(changed.changeId).status); assertTrue(ScheduleEngine.occursOn(store.rows[item.id]!!,LocalDate.parse("2026-10-08"))) }
    @Test fun cancelFutureSeriesKeepsPastOpen()=runBlocking { val store=TransactionalItems(); val s=service(store); val item=s.create(ItemDraft(ItemKind.TASK,"A",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(2,4),startDate="2026-10-01")))).item!!; assertEquals("ok",s.update(item.id,1,ItemPatch(status=ItemStatus.CANCELLED),EditScope.THIS_AND_FUTURE,"2026-10-08").status); assertTrue(ScheduleEngine.occursOn(store.rows[item.id]!!,LocalDate.parse("2026-10-06"))); assertTrue(store.rows.values.any { it.status==ItemStatus.CANCELLED }) }
    @Test fun singleOccurrenceCanBeReopened()=runBlocking { val store=TransactionalItems(); val s=service(store); val item=s.create(ItemDraft(ItemKind.TASK,"A",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(2),startDate="2026-10-01")))).item!!; val done=s.update(item.id,1,ItemPatch(status=ItemStatus.COMPLETED),occurrenceDate="2026-10-06").item!!; assertEquals("ok",s.update(done.id,done.version,ItemPatch(status=ItemStatus.OPEN),occurrenceDate="2026-10-06").status); assertTrue(ScheduleEngine.occursOn(store.rows[item.id]!!,LocalDate.parse("2026-10-06"))) }
    @Test fun overnightDependencyUsesDatesAsWellAsClockTime() { val a=PlanItem("a",kind=ItemKind.TASK,title="A",schedule=ScheduleSpec("2026-10-07","23:30"),durationMinutes=60); val b=PlanItem("b",kind=ItemKind.TASK,title="B",schedule=ScheduleSpec("2026-10-08","00:10"),dependsOn=setOf("a")); assertNotNull(DependencyPlanner.validate(mapOf("a" to a,"b" to b))); assertNull(DependencyPlanner.validate(mapOf("a" to a,"b" to b.copy(schedule=ScheduleSpec("2026-10-08","00:30"))))) }
    @Test fun checklistCanBeUndoneWithoutCompletingWholeNote()=runBlocking { val s=service(TransactionalItems()); val item=s.create(ItemDraft(ItemKind.NOTE,"采购",checklist=listOf(ChecklistEntry("牛奶"),ChecklistEntry("面包")))).item!!; val changed=s.update(item.id,1,ItemPatch(checklist=listOf(ChecklistEntry("牛奶",true),ChecklistEntry("面包")))); assertEquals(ItemStatus.OPEN,changed.item!!.status); assertTrue(changed.item.checklist.first().completed); assertFalse(s.undo(changed.changeId).item!!.checklist.first().completed) }
    @Test fun reschedulingOverdueWorkKeepsOriginalDeadline()=runBlocking { val s=service(TransactionalItems()); val item=s.create(ItemDraft(ItemKind.TASK,"补交报告",deadline=DeadlineSpec("2026-10-05"))).item!!; val result=s.update(item.id,1,ItemPatch(schedule=FieldPatch.Set(ScheduleSpec("2026-10-07")))); assertEquals("ok",result.status); assertEquals("2026-10-05",result.item!!.deadline!!.date) }
    @Test fun skippedWeeksDoNotDisableRemainingSeries() { val item=PlanItem("a",kind=ItemKind.ALARM,title="起床",schedule=ScheduleSpec(time="08:00",recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-01")),overrides=listOf("2026-10-07","2026-10-14","2026-10-21").map { OccurrenceOverride(it,status=ItemStatus.CANCELLED) }); assertEquals(Instant.parse("2026-10-28T00:00:00Z"),ScheduleEngine.nextTrigger(item,clock)) }
    @Test fun pinnedMemoDoesNotReverseScheduledTimeOrder() { val early=PlanItem("a",kind=ItemKind.TASK,title="早",schedule=ScheduleSpec("2026-10-07","09:00")); val late=early.copy(id="b",title="晚",schedule=ScheduleSpec("2026-10-07","18:00"),pinned=true); assertEquals(listOf("a","b"),DependencyPlanner.ordered(listOf(late,early)).map { it.id }) }
    @Test fun undoCannotBreakALaterDependency()=runBlocking { val store=TransactionalItems(); val s=service(store); val a=s.create(ItemDraft(ItemKind.TASK,"A",schedule=ScheduleSpec("2026-10-10"))).item!!; val moved=s.update(a.id,a.version,ItemPatch(schedule=FieldPatch.Set(ScheduleSpec("2026-10-07")))); s.create(ItemDraft(ItemKind.TASK,"B",schedule=ScheduleSpec("2026-10-08"),dependsOn=setOf(a.id))); assertEquals("error",s.undo(moved.changeId).status); assertEquals("2026-10-07",store.rows[a.id]!!.schedule!!.date) }
    @Test fun restoringSchedulesDoesNotEraseFiredReceipt()=runBlocking { val store=TransactionalItems(); val s=service(store); val item=s.create(ItemDraft(ItemKind.ALARM,"起床",schedule=ScheduleSpec("2026-10-07","08:00"))).item!!; store.setDelivery(item.id,item.version,DeliveryState.FIRED); s.restoreSchedules(); assertEquals(DeliveryState.FIRED,store.get(item.id)!!.delivery) }
    @Test fun splitFutureShiftPreservesPhaseAndUndoRestoresWholeSeries() = runBlocking {
        val store=TransactionalItems(); val s=service(store)
        val rule=RecurrenceSpec(frequency=RecurrenceRules.SHIFT,startDate="2026-10-01",workDays=2,restDays=2,cycleAnchor="2026-10-01")
        val original=s.create(ItemDraft(ItemKind.ALARM,"轮班",schedule=ScheduleSpec(time="08:00",recurrence=rule))).item!!
        val result=s.update(original.id,original.version,ItemPatch(schedule=FieldPatch.Set(original.schedule!!.copy(time="09:00"))),EditScope.THIS_AND_FUTURE,"2026-10-06")
        assertEquals("ok",result.status)
        val later=store.rows.values.single { it.id!=original.id }
        assertEquals("2026-10-01",later.schedule!!.recurrence!!.cycleAnchor)
        assertTrue(ScheduleEngine.occursOn(later,LocalDate.parse("2026-10-06")))
        assertFalse(ScheduleEngine.occursOn(later,LocalDate.parse("2026-10-07")))
        assertTrue(ScheduleEngine.occursOn(later,LocalDate.parse("2026-10-09")))
        assertEquals("ok",s.undo(result.changeId).status)
        assertTrue(ScheduleEngine.occursOn(store.rows[original.id]!!,LocalDate.parse("2026-10-06")))
    }
    @Test fun deleteBatchHidesItemsRepairsDependenciesAndUndoRestoresAll()=runBlocking {
        val store=TransactionalItems(); val s=service(store)
        val a=s.create(ItemDraft(ItemKind.TASK,"前置")).item!!
        val b=s.create(ItemDraft(ItemKind.NOTE,"后续",dependsOn=setOf(a.id))).item!!
        val r=s.deleteItems(listOf(a)); assertEquals("ok",r.status)
        assertTrue(store.get(a.id)!!.deleted); assertEquals(ItemStatus.CANCELLED,store.get(a.id)!!.status)
        assertTrue(store.get(b.id)!!.dependsOn.isEmpty())
        assertEquals("not_found",s.update(a.id,2,ItemPatch(title="覆盖删除")).errorCode)
        assertEquals("ok",s.undo(r.changeId).status)
        assertFalse(store.get(a.id)!!.deleted); assertEquals(setOf(a.id),store.get(b.id)!!.dependsOn)
    }
    @Test fun staleDeleteVersionDoesNotDeleteOtherSelectedItems()=runBlocking {
        val store=TransactionalItems(); val s=service(store); val a=s.create(ItemDraft(ItemKind.TASK,"A")).item!!; val b=s.create(ItemDraft(ItemKind.TASK,"B")).item!!
        s.update(b.id,b.version,ItemPatch(title="B变化"))
        assertEquals("version_conflict",s.deleteItems(listOf(a,b)).errorCode)
        assertTrue(store.all().none { it.deleted }); assertEquals(1,store.get(a.id)!!.version)
    }
    @Test fun selectAllDeletionAboveFiftyIsOneUndoableTransaction()=runBlocking {
        val store=TransactionalItems(); val s=service(store); repeat(60) { s.create(ItemDraft(ItemKind.TASK,"任务$it")) }
        val r=s.deleteItems(store.all()); assertEquals("ok",r.status); assertEquals(60,store.all().count { it.deleted })
        assertEquals("ok",s.undo(r.changeId).status); assertTrue(store.all().none { it.deleted })
    }
    @Test fun deletingOneHabitOccurrenceLeavesTomorrowAndCanBeUndone()=runBlocking {
        val store=TransactionalItems(); val s=service(store)
        val h=s.create(ItemDraft(ItemKind.TASK,"阅读",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=(1..7).toSet(),startDate="2026-10-06")))).item!!
        val day=LocalDate.parse("2026-10-06"); val r=s.deleteItems(listOf(h),EditScope.THIS_OCCURRENCE,day.toString())
        assertEquals("ok",r.status); val current=store.get(h.id)!!; assertFalse(current.deleted)
        assertNull(ScheduleEngine.onDate(current,day)); assertTrue(ScheduleEngine.onDate(current,day.plusDays(1))!!.isHabitTask)
        assertEquals("ok",s.undo(r.changeId).status); assertNotNull(ScheduleEngine.onDate(store.get(h.id)!!,day))
    }
    @Test fun deletingWholeHabitStopsFutureReminders()=runBlocking {
        val store=TransactionalItems(); val s=service(store)
        val h=s.create(ItemDraft(ItemKind.TASK,"阅读",schedule=ScheduleSpec(time="20:00",recurrence=RecurrenceSpec(weekdays=(1..7).toSet(),startDate="2026-10-06")),reminder=ReminderSpec(offsetMinutes=0))).item!!
        assertNotNull(ScheduleEngine.nextTrigger(h,clock)); s.deleteItems(listOf(h))
        assertNull(ScheduleEngine.nextTrigger(store.get(h.id)!!,clock)); assertNull(ScheduleEngine.onDate(store.get(h.id)!!,LocalDate.parse("2026-10-07"),true))
    }
    @Test fun legacyItemsAreVisibleAndNewBackupPreservesDeletion() {
        val legacy=kotlinx.serialization.json.Json.decodeFromString(PlanItem.serializer(),"""{"id":"old","kind":"TASK","title":"旧事项"}""")
        assertFalse(legacy.deleted)
        val deleted=legacy.copy(deleted=true,status=ItemStatus.CANCELLED)
        val archive=BackupCodec.decode(BackupCodec.encode(BackupArchive(items=listOf(deleted),knowledge=emptyList(),preferences=emptyList())))
        assertTrue(archive.items.single().deleted)
    }
    @Test fun centeredDatesCrossMonthAndYearWithoutChangingTodayPosition() {
        val day=LocalDate.parse("2027-01-01"); val days=DateWindows.centered(day)
        assertEquals(day,days[3]); assertEquals(LocalDate.parse("2026-12-29"),days.first()); assertEquals(LocalDate.parse("2027-01-04"),days.last())
    }

    @Test fun habitChecklistCompletionOnlyChangesSelectedOccurrence()=runBlocking {
        val store=TransactionalItems(); val s=service(store)
        val h=s.create(ItemDraft(ItemKind.TASK,"阅读",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=(1..7).toSet(),startDate="2026-10-06")),checklist=listOf(ChecklistEntry("读十页")))).item!!
        val r=s.update(h.id,h.version,ItemPatch(checklist=listOf(ChecklistEntry("读十页",true))),EditScope.THIS_OCCURRENCE,"2026-10-06")
        assertEquals("ok",r.status); val current=store.get(h.id)!!
        assertTrue(ScheduleEngine.onDate(current,LocalDate.parse("2026-10-06"))!!.checklist.single().completed)
        assertFalse(ScheduleEngine.onDate(current,LocalDate.parse("2026-10-07"))!!.checklist.single().completed)
    }

}
