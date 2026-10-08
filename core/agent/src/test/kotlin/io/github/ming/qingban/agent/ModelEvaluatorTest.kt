package io.github.ming.qingban.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ModelEvaluatorTest {
    private val clock=Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"),ZoneId.of("Asia/Shanghai"))
    private fun call(tool:String,args:JsonObject)=buildJsonObject { put("mode","call"); put("tool",tool); put("args",args) }.toString()
    @Test fun multiTurnEvaluationUsesOnlySyntheticStores()=runBlocking {
        var calls=0
        val client=object:ModelClient {
            override suspend fun available()=true
            override suspend fun decide(request:AgentRequest):String {
                calls++; val text=request.context["current_user_request"]!!.jsonPrimitive.content
                val results=request.context["recent_tool_results"]!!.jsonArray
                if(results.isNotEmpty()) return """{"mode":"say","text":"铭哥，已完成操作。"}"""
                if(text=="明早叫我起床") return """{"mode":"ask","question":"几点叫你？","options":[]}"""
                if("用户补充" in text) return call("create_item",buildJsonObject { put("kind","alarm"); put("title","起床"); put("schedule",buildJsonObject { put("date","2026-10-07"); put("time","08:00") }); put("alarm",buildJsonObject { put("headphones_only",true) }) })
                if(text=="明天买牛奶") return call("create_item",buildJsonObject { put("kind","task"); put("title","买牛奶"); put("schedule",buildJsonObject { put("date","2026-10-07") }) })
                if("改到后天" in text) {
                    val item=request.context["available_items"]!!.jsonArray.single().jsonObject
                    return call("update_item",buildJsonObject { put("item_id",item["id"]!!); put("expected_version",item["version"]!!); put("scope","whole_series"); put("patch",buildJsonObject { put("schedule",buildJsonObject { put("date","2026-10-08") }) }) })
                }
                if("撤销" in text) return call("undo_change",buildJsonObject {})
                return call("create_item",buildJsonObject { put("kind","note"); put("title","换键盘") })
            }
        }
        val results=ModelEvaluator(client,"preset",clock).runConversations()
        assertEquals(3,results.size); assertTrue(results.toString(),results.all { it.passed }); assertTrue(calls<=48)
    }
    @Test fun unavailableModelDoesNotStartEvaluation()=runBlocking {
        var called=false
        val client=object:ModelClient { override suspend fun available()=false; override suspend fun decide(request:AgentRequest):String { called=true; error("unexpected") } }
        assertTrue(runCatching { ModelEvaluator(client,"preset",clock).runConversations() }.isFailure); assertFalse(called)
    }
}
