package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    private val create="""{"mode":"call","tool":"create_item","args":{"kind":"task","title":"买牛奶"}}"""
    private val done="""{"mode":"say","text":"铭哥，已保存买牛奶。"}"""
    @Test fun cancellationKeepsRequestAndReleasesBusyState()=runBlocking {
        val h=AgentHarness(); val started=CompletableDeferred<Unit>()
        val c=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String { started.complete(Unit); awaitCancellation() }
        },"preset")
        val job=launch { c.send("明天买牛奶") }; started.await(); job.cancelAndJoin()
        assertFalse(c.busy.value); assertNotNull(c.session.value.activeTurn)
        val restored=h.coordinator(create,done); restored.load(); restored.retry()
        assertEquals(1,h.items.all().size); assertNull(restored.session.value.activeTurn)
        assertEquals(1,restored.session.value.history.count { it.role=="user" })
    }
    @Test fun retryAfterSavedWriteDoesNotCreateTwice()=runBlocking {
        val h=AgentHarness(); var count=0
        val c=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest)=if(count++==0) create else throw java.io.IOException("断网")
        },"preset")
        c.send("记下买牛奶"); assertEquals(1,h.items.all().size)
        assertTrue(c.session.value.history.last().text.contains("部分操作已保存"))
        val restored=h.coordinator(create,done); restored.load(); restored.retry()
        assertEquals(1,h.items.all().size); assertNull(restored.session.value.activeTurn)
    }
    @Test fun persistedInFlightToolIsReplayedWithSameIdentity()=runBlocking {
        val h=AgentHarness(); val call=DecisionParser.parse(create) as Decision.Call
        val id="stable-request"; val key="$id:${call.tool}:${call.args.hashCode()}"
        ToolGateway(h.services).execute(call,key,"记下买牛奶",true)
        h.sessions.write("agent_session",Json.encodeToString(AgentSession(activeTurn=PendingAgentTurn("记下买牛奶",id,inFlight=ProposedAction(call.tool,call.args)))))
        val c=h.coordinator(done); c.load(); c.retry(); assertEquals(1,h.items.all().size)
    }
    @Test fun interruptedTurnCanWaitWhileAnotherRequestIsHandled()=runBlocking {
        val h=AgentHarness(); val first=PendingAgentTurn("记下买牛奶","first")
        h.sessions.write("agent_session",Json.encodeToString(AgentSession(activeTurn=first)))
        val c=h.coordinator("""{"mode":"say","text":"你好，铭哥"}"""); c.load(); c.send("你好")
        assertEquals(first,c.session.value.interruptedTurns.single()); assertNull(c.session.value.activeTurn)
    }
    @Test fun revokedSourceIsRemovedFromRestoredToolContext()=runBlocking {
        val h=AgentHarness(); h.knowledge.save(KnowledgeSource("s","机密资料","健身机密内容",0,allowRemote=true))
        var calls=0
        val c=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String { if(calls++==0) return """{"mode":"call","tool":"search_knowledge","args":{"query":"健身"}}"""; throw java.io.IOException("断网") }
        },"preset")
        c.send("查一下健身资料"); h.knowledge.save(h.knowledge.all().single().copy(allowRemote=false))
        var context=""
        val restored=AgentCoordinator(h.services,object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String { context=request.context.toString(); return """{"mode":"say","text":"资料权限已变更"}""" }
        },"preset")
        restored.load(); restored.retry(); assertFalse(context.contains("健身机密内容")); assertTrue(restored.session.value.history.last().sources.isEmpty())
    }
    @Test fun completedOccurrenceCanBeQueriedByDate()=runBlocking {
        val h=AgentHarness(); val item=PlanItem("s",kind=ItemKind.TASK,title="练琴",schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-01")),overrides=listOf(OccurrenceOverride("2026-10-07",status=ItemStatus.COMPLETED))); h.items.commit(null,item,"seed",0)
        val result=ToolGateway(h.services).execute(Decision.Call("query_items",buildJsonObject { put("date_from","2026-10-07"); put("status","completed") }),"query","查询",true)
        assertEquals("COMPLETED",result["items"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
    }
    @Test fun reversedQueryDatesReturnAnError()=runBlocking {
        val h=AgentHarness(); val result=ToolGateway(h.services).execute(Decision.Call("query_items",buildJsonObject { put("date_from","2026-10-09"); put("date_to","2026-10-01") }),"q","查询",true)
        assertEquals("error",result["status"]!!.jsonPrimitive.content)
    }
    @Test fun inFlightProposalRestoresPreviewWithoutCallingModel()=runBlocking {
        val h=AgentHarness()
        val call=Decision.Call("propose_changes",buildJsonObject {
            put("summary","买牛奶和面包")
            put("changes",buildJsonArray { for(title in listOf("牛奶","面包")) add(buildJsonObject { put("tool","create_item"); put("args",buildJsonObject { put("kind","task"); put("title",title) }) }) })
        })
        val requestId="proposal-recovery"
        val result=ToolGateway(h.services).execute(call,"$requestId:${call.tool}:${call.args.hashCode()}","安排购物",true)
        h.sessions.write("agent_session",Json.encodeToString(AgentSession(activeTurn=PendingAgentTurn("安排购物",requestId,inFlight=ProposedAction(call.tool,call.args)))))
        val c=AgentCoordinator(h.services,object:ModelClient { override suspend fun available()=true; override suspend fun decide(request:AgentRequest):String=error("恢复预览不需要调用模型") },"preset")
        c.load(); c.retry()
        assertEquals(result["proposal_id"]!!.jsonPrimitive.content,c.session.value.proposalId)
        assertNull(c.session.value.activeTurn); assertTrue(h.items.all().isEmpty()); assertTrue(c.session.value.history.last().text.contains("确认后才会保存"))
    }
    @Test fun undoIsNotRepeatedWhenToolReceiptWasLost()=runBlocking {
        val h=AgentHarness(); h.services.items.create(ItemDraft(ItemKind.TASK,"买牛奶"))
        val call=Decision.Call("undo_change",buildJsonObject {}); val requestId="undo-recovery"
        val key="$requestId:${call.tool}:${call.args.hashCode()}"
        ToolGateway(h.services).execute(call,key,"撤销刚才的记录",true)
        h.sessions.remove("tool_result:$key")
        h.sessions.write("agent_session",Json.encodeToString(AgentSession(activeTurn=PendingAgentTurn("撤销刚才的记录",requestId,inFlight=ProposedAction(call.tool,call.args)))))
        val c=h.coordinator("""{"mode":"say","text":"铭哥，已撤销。"}"""); c.load(); c.retry()
        assertEquals(ItemStatus.CANCELLED,h.items.all().single().status); assertEquals(2,h.items.all().single().version); assertNull(c.session.value.activeTurn)
    }
    @Test fun internallyExpandedQuestionCanStillResume()=runBlocking {
        val h=AgentHarness()
        h.sessions.write("agent_session",Json.encodeToString(AgentSession(activeTurn=PendingAgentTurn("原请求"+"字".repeat(8000)+"\n用户补充：明天","long-request"))))
        val c=h.coordinator("""{"mode":"say","text":"铭哥，收到补充。"}"""); c.load(); c.retry()
        assertNull(c.session.value.activeTurn); assertEquals("铭哥，收到补充。",c.session.value.history.last().text)
    }
}
