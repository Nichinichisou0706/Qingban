package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.*

internal class AgentHarness {
    val sessions = object : SessionStore {
        val values = mutableMapOf<String,String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    val items = object : ItemStore {
        val rows = mutableMapOf<String,PlanItem>()
        val changes = mutableListOf<StoredChange>()
        val batches = mutableListOf<StoredBatch>()
        override suspend fun getBatch(id: String) = batches.firstOrNull { it.id == id }
        override suspend fun batchForRequest(requestKey: String) = batches.firstOrNull { it.requestKey == requestKey }
        override suspend fun commitBatch(entries: List<Pair<PlanItem?, PlanItem>>, requestKey: String, timestamp: Long): StoredBatch? {
            batchForRequest(requestKey)?.let { return it }
            if(entries.any { rows[it.second.id]?.version != it.first?.version }) return null
            val saved = entries.mapIndexed { i, (before, after) -> StoredChange("c${changes.size}-$i", before, after, "$requestKey:$i", timestamp) }
            saved.forEach { rows[it.after.id] = it.after }; changes += saved
            return StoredBatch("batch:${batches.size}", saved, requestKey, timestamp).also { batches += it }
        }
        override fun observe() = MutableStateFlow(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun get(id: String) = rows[id]
        override suspend fun commit(before: PlanItem?, after: PlanItem, requestKey: String, timestamp: Long): StoredChange? {
            if(rows[after.id]?.version != before?.version) return null
            return StoredChange("c${changes.size}", before, after, requestKey, timestamp).also { changes += it; rows[after.id] = after }
        }
        override suspend fun changeForRequest(requestKey: String) = changes.firstOrNull { it.requestKey == requestKey }
        override suspend fun latestChange() = changes.lastOrNull()
        override suspend fun getChange(id: String) = changes.firstOrNull { it.id == id }
        override suspend fun setDelivery(id: String, version: Int, state: DeliveryState) { rows[id]?.let { rows[id] = it.copy(delivery = state) } }
    }
    val knowledge = object : KnowledgeStore {
        val rows=mutableMapOf<String,KnowledgeSource>()
        override fun observe() = MutableStateFlow(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun save(source: KnowledgeSource) { rows[source.id]=source }
        override suspend fun delete(id: String) { rows.remove(id) }
    }
    private val memory = object : MemoryStore {
        val rows = mutableListOf<Preference>()
        override fun preferences() = MutableStateFlow(rows.toList())
        override suspend fun listPreferences() = rows.toList()
        override suspend fun save(preference: Preference) { rows.removeAll { it.key == preference.key && it.scope == preference.scope }; rows += preference }
        override suspend fun delete(key: String, scope: String) { rows.removeAll { it.key == key && it.scope == scope } }
        override suspend fun observations() = emptyList<ChoiceObservation>()
        override suspend fun observeChoice(observation: ChoiceObservation) = Unit
        override suspend fun clearObservations() = Unit
    }
    private val clock = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
    val services = Services(ItemService(items, object : ReminderScheduler { override suspend fun reconcile(item: PlanItem) = ScheduleResult(DeliveryState.NONE) }, clock, sessions), KnowledgeService(knowledge, clock), MemoryService(memory, sessions, clock), sessions, clock)
    fun coordinator(vararg output: String): AgentCoordinator {
        var index = 0
        return AgentCoordinator(services, object : ModelClient { override suspend fun available() = true; override suspend fun decide(request: AgentRequest) = output[minOf(index++, output.lastIndex)] }, "test preset")
    }
}
class CoordinatorTest {
    @Test fun fakeSuccessIsBlockedWithoutWrite() = runBlocking { val h = AgentHarness(); val c = h.coordinator("{\"mode\":\"say\",\"text\":\"已保存\"}"); c.send("明天买牛奶"); assertTrue(h.items.all().isEmpty()); assertTrue(c.session.value.history.last().text.contains("还没有成功执行")) }
    @Test fun repeatedModelCallDoesNotDuplicateItem() = runBlocking { val h = AgentHarness(); val call = "{\"mode\":\"call\",\"tool\":\"create_item\",\"args\":{\"kind\":\"task\",\"title\":\"买牛奶\"}}"; val c = h.coordinator(call, call, "{\"mode\":\"say\",\"text\":\"已保存\"}"); c.send("记下买牛奶"); assertEquals(1, h.items.all().size) }
    @Test fun unknownToolAndFieldsCannotWrite() = runBlocking { val h = AgentHarness(); val gateway = ToolGateway(h.services); val unknown = gateway.execute(Decision.Call("delete_all", buildJsonObject {}), "a", "操作", false); assertEquals("error", unknown["status"]!!.jsonPrimitive.content); val bad = gateway.execute(Decision.Call("create_item", buildJsonObject { put("kind", "task"); put("title", "A"); put("hidden_delete", true) }), "b", "记录", false); assertEquals("error", bad["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().isEmpty()) }
    @Test fun observationCannotBePromotedByModelClaim() = runBlocking { val h = AgentHarness(); val result = ToolGateway(h.services).execute(Decision.Call("write_memory", buildJsonObject { put("operation", "set"); put("key", "作息"); put("value", "早起"); put("basis", "confirmed_suggestion") }), "a", "好", true); assertEquals("error", result["status"]!!.jsonPrimitive.content); assertTrue(h.services.memory.activePreferences().isEmpty()) }
    @Test fun explicitPreferenceCanBeSavedAndUndone() = runBlocking { val h = AgentHarness(); val gateway = ToolGateway(h.services); val result = gateway.execute(Decision.Call("write_memory", buildJsonObject { put("operation", "set"); put("key", "时段"); put("value", "evening"); put("basis", "user_declared") }), "a", "记住，以后优先晚上", false); assertEquals("ok", result["status"]!!.jsonPrimitive.content); h.services.undo(); assertTrue(h.services.memory.activePreferences().isEmpty()) }
    @Test fun proposalNeedsActualApproval() = runBlocking {
        val h = AgentHarness()
        val gateway = ToolGateway(h.services)
        val action = buildJsonObject {
            put("tool", "create_item")
            put("args", buildJsonObject { put("kind", "task"); put("title", "A") })
        }
        val arguments = buildJsonObject { put("summary", "记事"); put("changes", buildJsonArray { add(action) }) }
        val preview = gateway.execute(Decision.Call("propose_changes", arguments), "a", "安排", false)
        val id = preview["proposal_id"]!!.jsonPrimitive.content
        val apply = Decision.Call("apply_proposal", buildJsonObject { put("proposal_id", id) })
        assertEquals("error", gateway.execute(apply, "b", "模型声称用户同意", false)["status"]!!.jsonPrimitive.content)
        assertTrue(h.items.all().isEmpty())
        gateway.approve(id)
        assertEquals("ok", gateway.execute(apply, "c", "确认", false)["status"]!!.jsonPrimitive.content)
        gateway.execute(apply, "d", "重试", false)
        assertEquals(1, h.items.all().size)
    }
    @Test fun packagedPresetIsPresent() { val prompt = AgentCoordinator::class.java.getResourceAsStream("/agent-system.txt")!!.bufferedReader().use { it.readText() }; assertTrue(prompt.contains("长期记忆")); assertTrue(prompt.contains("必须提问")) }
    @Test fun headphonesPolicyCanBeExpressedWithoutFilePaths() = runBlocking { val h = AgentHarness(); val result = ToolGateway(h.services).execute(Decision.Call("create_item", buildJsonObject { put("kind", "alarm"); put("title", "起床"); put("schedule", buildJsonObject { put("date", "2026-10-07"); put("time", "08:00") }); put("alarm", buildJsonObject { put("headphones_only", true) }) }), "a", "明早八点别外放", false); assertEquals("ok", result["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().single().alarm.headphonesOnly) }
    @Test fun repeatedMemoryToolHasOneUndoableEffect() = runBlocking { val h = AgentHarness(); val gateway = ToolGateway(h.services); val call = Decision.Call("write_memory", buildJsonObject { put("operation", "set"); put("key", "时段"); put("value", "evening"); put("basis", "user_declared") }); val first = gateway.execute(call, "same-memory", "以后记住晚上", false); val second = gateway.execute(call, "same-memory", "以后记住晚上", false); assertEquals(first, second); h.services.undo(); assertTrue(h.services.memory.activePreferences().isEmpty()) }
    @Test fun newTaskDoesNotDiscardUnansweredAlarm() = runBlocking { val h = AgentHarness(); val c = AgentCoordinator(h.services, object : ModelClient { override suspend fun available() = false; override suspend fun decide(request: AgentRequest) = error("offline") }, "preset"); c.send("明早叫我起床"); c.send("明天买牛奶"); assertEquals("起床", c.session.value.pausedLocal.single().title); c.resumeLocal(0); c.send("八点"); assertEquals(2, h.items.all().size); assertTrue(c.session.value.pausedLocal.isEmpty()) }
    @Test fun editedPreviewStillNeedsProgramApproval()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services); val action=ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title","A") })
        val preview=g.execute(Decision.Call("propose_changes",buildJsonObject { put("summary","安排"); put("changes",Json.encodeToJsonElement(listOf(action))) }),"preview","安排",false)
        val id=preview["proposal_id"]!!.jsonPrimitive.content; g.approve(id); g.editPreview(id,listOf(action.copy(args=JsonObject(action.args+("title" to JsonPrimitive("B"))))))
        val call=Decision.Call("apply_proposal",buildJsonObject { put("proposal_id",id) }); assertEquals("error",g.execute(call,"unapproved","执行",false)["status"]!!.jsonPrimitive.content); assertTrue(h.items.all().isEmpty())
        g.approve(id); assertEquals("ok",g.execute(call,"approved","确认",false)["status"]!!.jsonPrimitive.content); assertEquals("B",h.items.all().single().title)
    }
    @Test fun withdrawnSourceIsNotResentFromChatHistory()=runBlocking {
        val h=AgentHarness(); val source=h.services.knowledge.importText("私有资料","私密内容",true)
        val history=listOf(ChatLine("assistant","从资料得知的私密内容",sources=listOf("私有资料"),sourceIds=listOf(source.id),sourceVersions=mapOf(source.id to 1)))
        h.sessions.write("agent_session",kotlinx.serialization.json.Json.encodeToString(AgentSession(history=history))); h.services.knowledge.allowRemote(source.id,false)
        var captured: AgentRequest?=null
        val c=AgentCoordinator(h.services,object : ModelClient { override suspend fun available()=true; override suspend fun decide(request: AgentRequest): String { captured=request; return "{\"mode\":\"say\",\"text\":\"铭哥，你好\"}" } },"preset")
        c.send("你好"); assertTrue(captured!!.history.none { "私密内容" in it.text })
    }
    @Test fun offlineSequenceSavesRealDependencyIds()=runBlocking { val h=AgentHarness(); val c=AgentCoordinator(h.services,object : ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("offline") },"preset"); c.send("先写方案再发客户"); val a=h.items.all().single { it.title=="写方案" }; val b=h.items.all().single { it.title=="发客户" }; assertEquals(setOf(a.id),b.dependsOn); c.undo(c.session.value.history.last().changeId!!); assertTrue(h.items.all().all { it.status==ItemStatus.CANCELLED }) }
    @Test fun newRemoteTopicKeepsUnansweredRequest()=runBlocking { val h=AgentHarness(); val c=h.coordinator("{\"mode\":\"ask\",\"question\":\"几点？\",\"options\":[]}","{\"mode\":\"say\",\"text\":\"可以先商量安排\"}"); c.send("明早叫我起床"); c.send("再记一件事，明天买牛奶"); assertEquals(listOf("明早叫我起床"),c.session.value.pausedRequests); c.resumeRequest(0); assertEquals("明早叫我起床",c.session.value.pendingUserRequest); assertTrue(c.session.value.pausedRequests.isEmpty()) }
}
