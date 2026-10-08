package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HabitTaskDeletionTest {
    private fun offline(h: AgentHarness)=AgentCoordinator(h.services,object: ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("offline") },"preset")
    @Test fun dailyHabitCreatesOneRecurringTaskWithoutStandaloneDuplicate()=runBlocking {
        val h=AgentHarness(); offline(h).send("建立每天阅读习惯")
        val item=h.items.all().single(); assertEquals("阅读",item.title); assertTrue(item.isHabitTask); assertNull(item.reminder)
        assertEquals((1..7).toSet(),item.schedule!!.recurrence!!.weekdays); assertNull(item.schedule!!.recurrence!!.endDate)
        assertTrue(h.services.habits.state.value.habits.isEmpty())
    }
    @Test fun vagueHabitAsksFrequencyAndDailyReplyPreservesName()=runBlocking {
        val h=AgentHarness(); val c=offline(h); c.send("建立阅读习惯")
        assertTrue(h.items.all().isEmpty()); assertTrue(c.session.value.history.last().text.contains("每周哪天"))
        c.send("每天"); val item=h.items.all().single(); assertEquals("阅读",item.title); assertTrue(item.isHabitTask)
    }
    @Test fun habitReminderAsksForTimeInsteadOfInventingClock()=runBlocking {
        val h=AgentHarness(); val c=offline(h); c.send("每天提醒我阅读习惯")
        assertTrue(h.items.all().isEmpty()); c.send("晚上八点")
        val item=h.items.all().single(); assertEquals("20:00",item.schedule!!.time); assertNotNull(item.reminder); assertTrue(item.isHabitTask)
    }
    @Test fun deleteRequiresPreviewApprovalAndUndoRestoresHiddenTarget()=runBlocking {
        val h=AgentHarness(); val item=h.services.items.create(ItemDraft(ItemKind.NOTE,"临时备忘")).item!!; val g=ToolGateway(h.services)
        val call=Decision.Call("delete_item",buildJsonObject { put("item_id",item.id); put("expected_version",item.version); put("scope","whole_series") })
        assertEquals("error",g.execute(call,"unauthorized","查询备忘",true)["status"]!!.jsonPrimitive.content)
        assertEquals("approval_pending",g.execute(call,"delete","删除临时备忘",true)["status"]!!.jsonPrimitive.content)
        assertFalse(h.items.get(item.id)!!.deleted); val p=g.pendingProposal()!!
        val apply=Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",p.id) })
        assertEquals("error",g.execute(apply,"unapproved","确认",true)["status"]!!.jsonPrimitive.content)
        g.approve(p.id); val r=g.execute(apply,"approved","确认",true); assertEquals("ok",r["status"]!!.jsonPrimitive.content)
        val query=g.execute(Decision.Call("query_items",buildJsonObject { put("status","cancelled") }),"query","查询",true)
        assertTrue(query["items"]!!.jsonArray.isEmpty())
        assertEquals("error",g.execute(Decision.Call("get_item",buildJsonObject { put("item_id",item.id) }),"get","查询",true)["status"]!!.jsonPrimitive.content)
        h.services.undo(r["change_id"]!!.jsonPrimitive.content); assertFalse(h.items.get(item.id)!!.deleted)
    }
    @Test fun completingHabitOccurrenceDoesNotCompleteSeries()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services); val day=LocalDate.now(h.services.clock)
        val item=h.services.items.create(ItemDraft(ItemKind.TASK,"阅读",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=(1..7).toSet(),startDate=day.toString())))).item!!
        val r=g.execute(Decision.Call("update_item",buildJsonObject { put("item_id",item.id); put("expected_version",item.version); put("scope","this_occurrence"); put("occurrence_date",day.toString()); put("patch",buildJsonObject { put("status","completed") }) }),"complete","今天阅读习惯完成了",true)
        assertEquals("ok",r["status"]!!.jsonPrimitive.content); val current=h.items.get(item.id)!!
        assertEquals(ItemStatus.OPEN,current.status); assertEquals(ItemStatus.COMPLETED,ScheduleEngine.onDate(current,day)!!.status); assertEquals(ItemStatus.OPEN,ScheduleEngine.onDate(current,day.plusDays(1))!!.status)
    }
    @Test fun modelCannotSaveHabitLabelWithoutRepeatRule()=runBlocking {
        val h=AgentHarness(); val r=ToolGateway(h.services).execute(Decision.Call("create_item",buildJsonObject { put("kind","task"); put("title","阅读"); put("label","习惯") }),"bad-habit","创建阅读习惯",true)
        assertEquals("error",r["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().isEmpty())
    }

}
