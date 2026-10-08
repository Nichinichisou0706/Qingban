package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecurrenceToolsTest {
    private fun call(frequency: String, extra: String="") = Decision.Call("create_item",Json.parseToJsonElement("""{"kind":"alarm","title":"起床","schedule":{"time":"08:00","recurrence":{"frequency":"$frequency","start_date":"2026-10-01"$extra}}}""").jsonObject)
    @Test fun remoteLegalRulePassesThroughToolsAndCalendarQuery()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val result=g.execute(call("cn_workday"),"legal","设法定工作日闹钟",true)
        assertEquals("ok",result["status"]!!.jsonPrimitive.content)
        val item=h.items.all().single()
        assertEquals("cn_workday",item.schedule!!.recurrence!!.frequency)
        assertFalse(ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-07")))
        assertTrue(ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-10")))
        val query=Decision.Call("query_items",Json.parseToJsonElement("""{"date_from":"2026-10-10","date_to":"2026-10-10"}""").jsonObject)
        assertEquals(1,g.execute(query,"query","十号有什么闹钟",true)["items"]!!.jsonArray.size)
    }
    @Test fun remoteShiftParametersPersistAndRetryDoesNotDuplicate()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val c=call("shift" ,""", "work_days":2,"rest_days":2,"cycle_anchor":"2026-10-01"""")
        val a=g.execute(c,"shift","设上两天休两天的轮班闹钟",true)
        assertEquals("ok",a["status"]!!.jsonPrimitive.content)
        assertEquals(a,g.execute(c,"shift","设轮班闹钟",true))
        assertEquals(1,h.items.all().size)
        assertEquals("2026-10-01",h.items.all().single().schedule!!.recurrence!!.cycleAnchor)
    }
    @Test fun missingShiftAnchorCannotBeSavedByAgent()=runBlocking {
        val h=AgentHarness(); val result=ToolGateway(h.services).execute(call("shift",""", "work_days":2,"rest_days":2"""),"bad","设置轮班闹钟",true)
        assertEquals("error",result["status"]!!.jsonPrimitive.content)
        assertTrue(h.items.all().isEmpty())
    }
}
