package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlanningReviewTest {
    private fun draft(detail: String)=ItemDraft(ItemKind.TASK,"全天计划",detail,ScheduleSpec("2026-10-07"),label="学习工作")
    @Test fun blocksBecomeSeparateTimedActions() {
        val rows=PlanBlocks.split(draft("09:00 - 11:00 高数：第五章续\n14:30 - 17:00 通原：第二章续\n18:00 - 21:30 概率论：第一章起步\n880线代题"))!!
        assertEquals(3,rows.size); assertEquals(listOf(120,150,210),rows.map { it.durationMinutes }); assertEquals(listOf("09:00","14:30","18:00"),rows.map { it.schedule?.time }); assertEquals("880线代题",rows.last().detail)
    }
    @Test fun commaSeparatedBlocksAndMidnightAreSupported() {
        val rows=PlanBlocks.split(draft("9:00-10:00复习，22:30-00:00整理"))!!
        assertEquals(90,rows.last().durationMinutes); assertEquals("09:00",rows.first().schedule?.time)
    }
    @Test fun splitDropsDateAndSeparatorsButKeepsMeaningfulNotes() {
        val rows=PlanBlocks.split(draft("明天，9:00-10:00阅读，11:00-12:00练习"))!!
        assertTrue(rows.all { it.detail.isEmpty() })
        val noted=PlanBlocks.split(draft("带好教材\n9:00-10:00阅读\n做笔记\n11:00-12:00练习"))!!
        assertEquals("带好教材\n做笔记",noted.first().detail)
    }
    @Test fun memoTimeTableIsPreserved() { assertNull(PlanBlocks.split(draft("9:00-10:00A\n11:00-12:00B").copy(kind=ItemKind.NOTE))) }
    @Test fun ambiguousMultiDatePlanIsRejected() { assertTrue(runCatching { PlanBlocks.split(draft("2026-10-07\n9:00-10:00A\n2026-10-08\n11:00-12:00B")) }.isFailure) }
    @Test fun independentReminderCannotBeCopiedToEveryBlock() { assertTrue(runCatching { PlanBlocks.split(draft("9:00-10:00A\n11:00-12:00B").copy(reminder=ReminderSpec(atLocal="2026-10-07T08:00"))) }.isFailure) }
    @Test fun bundledModelOutputProducesPreviewWithoutWriting()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val result=g.execute(Decision.Call("create_item",buildJsonObject { put("kind","task"); put("title","全天计划"); put("detail","9:00-10:00A\n11:00-12:00B"); put("schedule",buildJsonObject { put("date","2026-10-07") }) }),"plan","安排明天",true)
        assertEquals("approval_pending",result["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().isEmpty())
        val p=g.pendingProposal()!!; assertEquals(2,p.changes.size); g.approve(p.id)
        val saved=g.execute(Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",p.id) }),"save","确认",true)
        assertEquals("ok",saved["status"]!!.jsonPrimitive.content); assertEquals(2,h.items.all().size)
        h.services.undo(saved["change_id"]!!.jsonPrimitive.content); assertTrue(h.items.all().all { it.status==ItemStatus.CANCELLED })
    }
    @Test fun proposedReferencesBecomeRealDependencyIds()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val actions=listOf(ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title","写方案"); put("plan_key","write") }),ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title","发客户"); put("depends_on",buildJsonArray { add("@write") }) }))
        g.execute(Decision.Call("propose_changes",buildJsonObject { put("summary","先写再发"); put("changes",Json.encodeToJsonElement(actions)) }),"preview","安排",true)
        val p=g.pendingProposal()!!; g.approve(p.id)
        val r=g.execute(Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",p.id) }),"apply","确认",true)
        assertEquals(r.toString(),"ok",r["status"]!!.jsonPrimitive.content); assertEquals(setOf(h.items.all().single { it.title=="写方案" }.id),h.items.all().single { it.title=="发客户" }.dependsOn)
    }
    @Test fun incompleteReferenceSelectionCannotPartiallyWrite()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val a=ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title","A"); put("plan_key","a") }); val b=ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title","B"); put("depends_on",buildJsonArray { add("@a") }) })
        g.execute(Decision.Call("propose_changes",buildJsonObject { put("summary","计划"); put("changes",Json.encodeToJsonElement(listOf(a,b))) }),"preview","安排",true)
        val id=g.pendingProposal()!!.id; g.editPreview(id,listOf(b)); g.approve(id)
        assertEquals("error",g.execute(Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",id) }),"apply","确认",true)["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().isEmpty())
    }
    @Test fun oldDailyTaskSplitsAtomicallyAndUpdatesSuccessors()=runBlocking {
        val h=AgentHarness(); val old=h.services.items.create(draft("9:00-10:00A\n11:00-12:00B")).item!!
        val successor=h.services.items.create(ItemDraft(ItemKind.TASK,"复盘",ScheduleSpec("2026-10-08").let { "" },ScheduleSpec("2026-10-08"),dependsOn=setOf(old.id))).item!!
        val g=ToolGateway(h.services); val p=g.execute(Decision.Call("split_task",buildJsonObject { put("item_id",old.id); put("expected_version",old.version) }),"split","把任务拆开",true)
        assertEquals("approval_pending",p["status"]!!.jsonPrimitive.content); assertEquals(2,h.items.all().size)
        val id=g.pendingProposal()!!.id; g.approve(id); val r=g.execute(Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",id) }),"save","确认",true)
        assertEquals(r.toString(),"ok",r["status"]!!.jsonPrimitive.content); assertEquals(ItemStatus.CANCELLED,h.items.get(old.id)!!.status); assertEquals(2,h.items.get(successor.id)!!.dependsOn.size)
        h.services.undo(r["change_id"]!!.jsonPrimitive.content); assertEquals(setOf(old.id),h.items.get(successor.id)!!.dependsOn); assertEquals(ItemStatus.OPEN,h.items.get(old.id)!!.status)
    }
    @Test fun oneShotAlarmWithoutDateChoosesNextOccurrence()=runBlocking {
        val h=AgentHarness(); val r=ToolGateway(h.services).execute(Decision.Call("create_item",buildJsonObject { put("kind","alarm"); put("title","起床"); put("schedule",buildJsonObject { put("time","08:00") }) }),"alarm","设八点闹钟",true)
        assertEquals(r.toString(),"ok",r["status"]!!.jsonPrimitive.content); assertEquals("2026-10-07",h.items.all().single().schedule?.date)
    }
    @Test fun reviewDoesNotMutateAndApprovalCanBeUndone()=runBlocking {
        val h=AgentHarness(); val old=h.services.items.create(ItemDraft(ItemKind.TASK,"补做",schedule=ScheduleSpec("2026-10-05","09:00"),durationMinutes=60,deadline=DeadlineSpec("2026-10-05"))).item!!
        val p=h.services.review.refresh()!!; assertEquals(old.schedule,h.items.get(old.id)!!.schedule); assertTrue(p.moves.single().reason.contains("截止"))
        val r=h.services.review.approve(p.id,setOf(old.id)); assertEquals(r.toString(),"ok",r.status); assertEquals("2026-10-06",h.items.get(old.id)!!.schedule?.date); assertEquals(old.deadline,h.items.get(old.id)!!.deadline)
        assertEquals(r.changeId,h.services.review.approve(p.id,setOf(old.id)).changeId); h.services.undo(r.changeId); assertEquals(old.schedule,h.items.get(old.id)!!.schedule)
    }
    @Test fun reviewIsDurableAndDismissIsNotApproval()=runBlocking {
        val h=AgentHarness(); val old=h.services.items.create(ItemDraft(ItemKind.TASK,"过期",schedule=ScheduleSpec("2026-10-05"))).item!!
        val p=h.services.review.refresh()!!; val restored=DailyReviewService(h.services.items,h.sessions,h.services.clock); assertEquals(p,restored.refresh()); restored.dismiss(p.id)
        assertNull(restored.refresh()); assertEquals(old.schedule,h.items.get(old.id)!!.schedule)
    }
    @Test fun explicitReviewRefreshIncludesNewTasksWithoutMovingThem()=runBlocking {
        val h=AgentHarness(); h.services.items.create(ItemDraft(ItemKind.TASK,"已有",schedule=ScheduleSpec("2026-10-05")))
        val cached=h.services.review.refresh()!!
        val added=h.services.items.create(ItemDraft(ItemKind.TASK,"刚加的",schedule=ScheduleSpec("2026-10-05"))).item!!
        assertEquals(cached,h.services.review.refresh())
        val fresh=h.services.review.refresh(force=true)!!
        assertEquals(2,fresh.moves.size); assertTrue(fresh.moves.any { it.id==added.id }); assertEquals("2026-10-05",h.items.get(added.id)!!.schedule?.date)
    }
    @Test fun approvalRejectsNewBookingsAndRefreshFindsAnotherSlot()=runBlocking {
        val h=AgentHarness(); val old=h.services.items.create(ItemDraft(ItemKind.TASK,"补做",schedule=ScheduleSpec("2026-10-05","15:00"),durationMinutes=60)).item!!
        val p=h.services.review.refresh()!!
        h.services.items.create(ItemDraft(ItemKind.TASK,"新会议",schedule=p.moves.single().after,durationMinutes=60))
        assertEquals("error",h.services.review.approve(p.id,setOf(old.id)).status)
        assertEquals(old.schedule,h.items.get(old.id)!!.schedule)
        val fresh=h.services.review.refresh(force=true)!!
        assertEquals("16:00",fresh.moves.single { it.id==old.id }.after.time)
        assertEquals("ok",h.services.review.approve(fresh.id,setOf(old.id)).status)
    }
    @Test fun reviewRejectsStaleVersionsWithoutMovingAnyTask()=runBlocking {
        val h=AgentHarness(); val a=h.services.items.create(ItemDraft(ItemKind.TASK,"A",schedule=ScheduleSpec("2026-10-05"))).item!!; val b=h.services.items.create(ItemDraft(ItemKind.TASK,"B",schedule=ScheduleSpec("2026-10-05"))).item!!
        val p=h.services.review.refresh()!!; h.services.items.update(a.id,a.version,ItemPatch(title="已编辑"),EditScope.WHOLE_SERIES)
        assertEquals("error",h.services.review.approve(p.id,setOf(a.id,b.id)).status); assertEquals("2026-10-05",h.items.get(b.id)!!.schedule?.date)
    }
    @Test fun reviewAvoidsBookedAndCrossMidnightSlots()=runBlocking {
        val h=AgentHarness(); h.services.items.create(ItemDraft(ItemKind.TASK,"固定安排",schedule=ScheduleSpec("2026-10-06","09:00"),durationMinutes=180))
        val a=h.services.items.create(ItemDraft(ItemKind.TASK,"补做",schedule=ScheduleSpec("2026-10-05","09:00"),durationMinutes=60)).item!!
        val p=h.services.review.refresh()!!; assertEquals("12:00",p.moves.single { it.id==a.id }.after.time)
    }
    @Test fun reviewMovesRelatedSuccessorAndRetainsDeadline()=runBlocking {
        val h=AgentHarness(); val a=h.services.items.create(ItemDraft(ItemKind.TASK,"A",schedule=ScheduleSpec("2026-10-05","15:00"),durationMinutes=60)).item!!
        val b=h.services.items.create(ItemDraft(ItemKind.TASK,"B",schedule=ScheduleSpec("2026-10-06","10:30"),durationMinutes=30,dependsOn=setOf(a.id))).item!!
        val p=h.services.review.refresh()!!; assertEquals(2,p.moves.size); assertEquals("16:00",p.moves.single { it.id==b.id }.after.time); assertEquals("ok",h.services.review.approve(p.id,setOf(a.id,b.id)).status)
    }
    @Test fun reviewNeverMovesMemosAlarmsOrCompletedTasks()=runBlocking {
        val h=AgentHarness(); h.services.items.create(ItemDraft(ItemKind.NOTE,"备忘",schedule=ScheduleSpec("2026-10-05")))
        h.services.items.create(ItemDraft(ItemKind.ALARM,"闹钟",schedule=ScheduleSpec("2026-10-07","08:00")))
        val done=h.services.items.create(ItemDraft(ItemKind.TASK,"完成",schedule=ScheduleSpec("2026-10-05"))).item!!; h.services.items.update(done.id,done.version,ItemPatch(status=ItemStatus.COMPLETED),EditScope.WHOLE_SERIES)
        assertNull(h.services.review.refresh())
    }
    @Test fun reviewRecurringTaskMovesOnlyPreviousOccurrence()=runBlocking {
        val h=AgentHarness(); val original=h.services.items.create(ItemDraft(ItemKind.TASK,"每日复习",schedule=ScheduleSpec(time="09:00",recurrence=RecurrenceSpec(weekdays=(1..7).toSet(),startDate="2026-10-01")),durationMinutes=60)).item!!
        val p=h.services.review.refresh()!!; assertEquals("2026-10-05",p.moves.single().occurrence); val r=h.services.review.approve(p.id,setOf(original.id)); assertEquals(r.toString(),"ok",r.status)
        val saved=h.items.get(original.id)!!; assertEquals(original.schedule,saved.schedule); assertEquals(1,saved.overrides.size); assertTrue(ScheduleEngine.occursOn(saved,LocalDate.parse("2026-10-07")))
    }
    @Test fun offlinePlanHasSeparatePreviewAndAsksUnknownDay()=runBlocking {
        val h=AgentHarness(); val c=AgentCoordinator(h.services,object: ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("offline") },"preset")
        c.send("9:00-10:00阅读\n14:00-15:00练习"); assertNotNull(c.session.value.pendingUserRequest); assertTrue(h.items.all().isEmpty())
        c.send("明天"); assertEquals(2,c.proposal()!!.changes.size); assertTrue(h.items.all().isEmpty())
    }
}
