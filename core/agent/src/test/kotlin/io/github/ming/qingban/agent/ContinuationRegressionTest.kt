package io.github.ming.qingban.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContinuationRegressionTest {
    private val request = "明天到周天，帮我安排一下。通原第三、四章学习和高数强化567章"
    private fun create(title: String) = """{"mode":"call","tool":"create_item","args":{"kind":"task","title":"$title"}}"""
    private val done = """{"mode":"say","text":"铭哥，任务已保存。"}"""

    @Test fun typedContinueKeepsOriginalRequestAndSavedProgress() = runBlocking {
        val h = AgentHarness()
        var count = 0
        var resumedContext: JsonObject? = null
        val c = AgentCoordinator(h.services, object : ModelClient {
            override suspend fun available() = true
            override suspend fun decide(request: AgentRequest): String = when(count++) {
                0 -> create("通原第三章")
                1 -> throw IOException("临时断网")
                else -> { resumedContext = request.context; done }
            }
        }, "preset")
        c.send(request)
        c.send("继续")
        assertEquals(request, resumedContext!!["current_user_request"]!!.jsonPrimitive.content)
        assertEquals(1, resumedContext!!["recent_tool_results"]!!.jsonArray.size)
        assertEquals(1, h.items.all().size)
        assertTrue(c.session.value.interruptedTurns.isEmpty())
        assertNull(c.session.value.activeTurn)
    }

    @Test fun legacyContinueTurnCanRecoverOriginalInterruptedRequest() = runBlocking {
        val h = AgentHarness()
        val original = PendingAgentTurn(request, "original-request")
        h.sessions.write("agent_session", Json.encodeToString(AgentSession(
            activeTurn = PendingAgentTurn("继续", "accidental-new-request"),
            interruptedTurns = listOf(original)
        )))
        var seen = ""
        val c = AgentCoordinator(h.services, object : ModelClient {
            override suspend fun available() = true
            override suspend fun decide(request: AgentRequest): String {
                seen = request.context["current_user_request"]!!.jsonPrimitive.content
                return """{"mode":"say","text":"铭哥，继续核对原来的安排。"}"""
            }
        }, "preset")
        c.load(); c.retry()
        assertEquals(request, seen)
        assertTrue(c.session.value.interruptedTurns.isEmpty())
        assertNull(c.session.value.activeTurn)
    }

    @Test fun callLimitKeepsNextDecisionForRetryWithoutRepeatingSavedTasks() = runBlocking {
        val h = AgentHarness()
        val outputs = (1..7).map { create("章节$it") } + done
        val c = h.coordinator(*outputs.toTypedArray())
        c.send(request)
        assertEquals(6, h.items.all().size)
        val pending = c.session.value.activeTurn!!
        assertEquals("章节7", pending.inFlight!!.args["title"]!!.jsonPrimitive.content)
        c.retry()
        assertEquals(7, h.items.all().size)
        assertEquals(7, h.items.all().map { it.title }.distinct().size)
        assertNull(c.session.value.activeTurn)
    }

    @Test fun formatRepairIsPersistedAndExplainsProtocolShapeToModel() = runBlocking {
        val h = AgentHarness()
        val c = h.coordinator("""{"text":"普通回答，不是决策"}""")
        c.send(request)
        assertEquals(3, c.session.value.activeTurn!!.results.size)
        assertTrue(c.session.value.activeTurn!!.results.all { it["error_code"] != null })
        var resumedContext: JsonObject? = null
        val restored = AgentCoordinator(h.services, object : ModelClient {
            override suspend fun available() = true
            override suspend fun decide(request: AgentRequest): String {
                resumedContext = request.context
                return """{"mode":"ask","question":"铭哥，这几天每天可以学习多久？","options":[]}"""
            }
        }, "preset")
        restored.load(); restored.retry()
        assertEquals(3, resumedContext!!["recent_tool_results"]!!.jsonArray.size)
        assertEquals(request, restored.session.value.pendingUserRequest)
    }

    @Test fun toolErrorsAreShownInsteadOfOnlyGenericOperationLimit() = runBlocking {
        val h = AgentHarness()
        val c = h.coordinator("""{"mode":"call","tool":"query_items","args":{"date":"2026-10-07"}}""")
        c.send(request)
        assertTrue(h.items.all().isEmpty())
        assertTrue(c.session.value.history.last().text.contains("未知字段"))
        assertTrue(c.session.value.history.last().text.contains("date"))
    }

    @Test fun repeatedReadWithoutNewProgressStopsInsteadOfSpendingWholeBudget() = runBlocking {
        val h=AgentHarness()
        var requests=0
        val c=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String {
                requests++
                return """{"mode":"call","tool":"query_items","args":{}}"""
            }
        },"preset")
        c.send(request)
        assertEquals(3,requests)
        assertTrue(c.session.value.history.last().text.contains("没有新进展"))
        assertEquals(request,c.session.value.activeTurn!!.request)
    }

    @Test fun formatRepairDoesNotStealCompletionStepAfterSixCalls() = runBlocking {
        val h=AgentHarness()
        val output=listOf("bad JSON")+(1..6).map { create("章节$it") }+listOf("bad JSON",done)
        val c=h.coordinator(*output.toTypedArray())
        c.send(request)
        assertEquals(6,h.items.all().size)
        assertNull(c.session.value.activeTurn)
        assertTrue(c.session.value.history.last().text.contains("任务已保存"))
    }

    @Test fun truncatedResponseGetsCompactRepairAndKeepsItsDiagnostic() = runBlocking {
        val h=AgentHarness()
        var calls=0
        var context:JsonObject?=null
        val c=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String {
                if(calls++==0) throw ModelOutputException("model_output_truncated","模型输出被长度上限截断")
                context=request.context
                return """{"mode":"ask","question":"铭哥，每天可以学习多久？","options":[]}"""
            }
        },"preset")
        c.send(request)
        assertEquals("model_output_truncated",context!!["recent_tool_results"]!!.jsonArray.single().jsonObject["error_code"]!!.jsonPrimitive.content)
        assertEquals(request,c.session.value.pendingUserRequest)
        assertTrue(h.items.all().isEmpty())
    }

    @Test fun answerContinueToClarifyingQuestionIsNotMisreadAsRetry() = runBlocking {
        val h=AgentHarness()
        val c=h.coordinator("""{"mode":"ask","question":"继续原来的专注吗？","options":["继续","结束"]}""",
            """{"mode":"say","text":"铭哥，收到选择。"}""")
        c.send("帮我处理专注")
        assertEquals("帮我处理专注",c.session.value.pendingUserRequest)
        c.send("继续")
        assertNull(c.session.value.activeTurn)
        assertNull(c.session.value.pendingUserRequest)
        assertTrue(c.session.value.interruptedTurns.isEmpty())
    }
}
