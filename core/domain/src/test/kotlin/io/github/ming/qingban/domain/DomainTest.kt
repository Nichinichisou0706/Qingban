package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

private class FakeItems : ItemStore {
    val rows = mutableMapOf<String, PlanItem>()
    val changes = mutableListOf<StoredChange>()
    override fun observe() = MutableStateFlow(rows.values.toList())
    override suspend fun all() = rows.values.toList()
    override suspend fun get(id: String) = rows[id]
    override suspend fun commit(before: PlanItem?, after: PlanItem, requestKey: String, timestamp: Long): StoredChange? {
        if (rows[after.id]?.version != before?.version) return null
        val change = StoredChange("change-${changes.size}", before, after, requestKey, timestamp)
        rows[after.id] = after; changes += change; return change
    }
    override suspend fun changeForRequest(requestKey: String) = changes.firstOrNull { it.requestKey == requestKey }
    override suspend fun latestChange() = changes.lastOrNull()
    override suspend fun getChange(id: String) = changes.firstOrNull { it.id == id }
    override suspend fun setDelivery(id: String, version: Int, state: DeliveryState) { rows[id]?.takeIf { it.version == version }?.let { rows[id] = it.copy(delivery = state) } }
}
class DomainTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val ready = object : ReminderScheduler { override suspend fun reconcile(item: PlanItem) = ScheduleResult(if (ScheduleEngine.nextTrigger(item, clock) != null) DeliveryState.SCHEDULED else DeliveryState.NONE) }
    @Test fun plainTodoNeedsNoReminder() = runBlocking {
        val result = ItemService(FakeItems(), ready, clock).create(ItemDraft(ItemKind.TASK, "买牛奶", schedule = ScheduleSpec(date = "2026-10-07")))
        assertEquals("ok", result.status); assertNull(result.item!!.reminder); assertEquals(DeliveryState.NONE, result.item.delivery)
    }
    @Test fun incompleteAlarmCannotBeSaved() = runBlocking { val store = FakeItems(); assertEquals("error", ItemService(store, ready, clock).create(ItemDraft(ItemKind.ALARM, "起床", schedule = ScheduleSpec(date = "2026-10-07"))).status); assertTrue(store.rows.isEmpty()) }
    @Test fun retriesDoNotCreateDuplicates() = runBlocking { val store = FakeItems(); val service = ItemService(store, ready, clock); repeat(2) { service.create(ItemDraft(ItemKind.TASK, "同一请求"), "same") }; assertEquals(1, store.rows.size) }
    @Test fun reschedulePreservesDeadline() = runBlocking {
        val service = ItemService(FakeItems(), ready, clock)
        val created = service.create(ItemDraft(ItemKind.TASK, "报告", schedule = ScheduleSpec(date = "2026-10-07"), deadline = DeadlineSpec("2026-10-09"))).item!!
        val result = service.update(created.id, created.version, ItemPatch(schedule = FieldPatch.Set(ScheduleSpec(date = "2026-10-08"))))
        assertEquals("2026-10-09", result.item!!.deadline!!.date)
    }
    @Test fun staleVersionCannotOverwrite() = runBlocking { val service = ItemService(FakeItems(), ready, clock); val item = service.create(ItemDraft(ItemKind.TASK, "A")).item!!; service.update(item.id, 1, ItemPatch(title = "B")); assertEquals("version_conflict", service.update(item.id, 1, ItemPatch(title = "C")).errorCode) }
    @Test fun permissionFailureIsPartial() = runBlocking { val blocked = object : ReminderScheduler { override suspend fun reconcile(item: PlanItem) = ScheduleResult(DeliveryState.PERMISSION_REQUIRED, "尚未启用") }; val result = ItemService(FakeItems(), blocked, clock).create(ItemDraft(ItemKind.ALARM, "起床", schedule = ScheduleSpec(date = "2026-10-07", time = "08:00"))); assertEquals("partial", result.status); assertNotNull(result.item) }
    @Test fun undoDoesNotOverwriteLaterEdit() = runBlocking { val service = ItemService(FakeItems(), ready, clock); val created = service.create(ItemDraft(ItemKind.TASK, "A")); service.update(created.item!!.id, 1, ItemPatch(title = "B")); assertEquals("version_conflict", service.undo(created.changeId).errorCode) }
    @Test fun cancelStopsScheduling() = runBlocking { val service = ItemService(FakeItems(), ready, clock); val item = service.create(ItemDraft(ItemKind.ALARM, "起床", schedule = ScheduleSpec(date = "2026-10-07", time = "08:00"))).item!!; assertEquals(DeliveryState.NONE, service.update(item.id, 1, ItemPatch(status = ItemStatus.CANCELLED)).item!!.delivery) }
    @Test fun weeklyAlarmUsesNextCorrectDay() { val item = PlanItem("a", kind = ItemKind.ALARM, title = "起床", schedule = ScheduleSpec(time = "08:00", recurrence = RecurrenceSpec(weekdays = setOf(1, 3, 5), startDate = "2026-10-01"))); assertEquals(Instant.parse("2026-10-07T00:00:00Z"), ScheduleEngine.nextTrigger(item, clock)) }
    @Test fun repeatedOccurrenceCanBeCompletedWithoutCompletingSeries() = runBlocking { val service = ItemService(FakeItems(), ready, clock); val item = service.create(ItemDraft(ItemKind.TASK, "运动", schedule = ScheduleSpec(recurrence = RecurrenceSpec(weekdays = setOf(2, 4), startDate = "2026-10-01")))).item!!; val changed = service.update(item.id, 1, ItemPatch(status = ItemStatus.COMPLETED), occurrenceDate = "2026-10-06").item!!; assertEquals(ItemStatus.OPEN, changed.status); assertFalse(ScheduleEngine.occursOn(changed, LocalDate.parse("2026-10-06"))); assertTrue(ScheduleEngine.occursOn(changed, LocalDate.parse("2026-10-08"))) }
    @Test fun firedOneOffCannotRearmAfterRestart() { val item = PlanItem("a", kind = ItemKind.ALARM, title = "起床", schedule = ScheduleSpec(date = "2026-10-07", time = "08:00"), delivery = DeliveryState.FIRED); assertNull(ScheduleEngine.nextTrigger(item, clock)) }
    @Test fun invalidPeriodCannotSlipThrough() { assertNotNull(ScheduleEngine.validate(ItemDraft(ItemKind.TASK, "A", schedule = ScheduleSpec(date = "2026-10-07", period = "random")))) }
}
