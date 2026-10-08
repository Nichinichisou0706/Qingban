package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class FeatureToolsTest {
    private fun call(tool: String,args: String="{}")=Decision.Call(tool,Json.parseToJsonElement(args).jsonObject)
    private fun offline(h: AgentHarness)=AgentCoordinator(h.services,object: ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("local only") },"preset")
    @Test fun remoteTimerStartsAndRetryIsIdempotent()=runBlocking { val h=AgentHarness(); val g=ToolGateway(h.services); val c=call("focus_control","""{"action":"start","minutes":45}"""); val a=g.execute(c,"same","开始45分钟专注",true); val b=g.execute(c,"same","开始45分钟专注",true); assertEquals(a,b); assertEquals(45*60_000L,h.services.focus.state.value.run!!.targetMillis) }
    @Test fun pureQueryDoesNotAuthorizeStartingTimer()=runBlocking { val h=AgentHarness(); val result=ToolGateway(h.services).execute(call("focus_control","""{"action":"start"}"""),"k","番茄钟有什么功能",true); assertEquals("error",result["status"]!!.jsonPrimitive.content); assertNull(h.services.focus.state.value.run) }
    @Test fun invalidTaskIdIsRejected()=runBlocking { val h=AgentHarness(); val result=ToolGateway(h.services).execute(call("focus_control","""{"action":"start","task_id":"imaginary"}"""),"k","开始专注",true); assertEquals("error",result["status"]!!.jsonPrimitive.content); assertNull(h.services.focus.state.value.run) }
    @Test fun timerSettingsOnlyChangeProvidedFields()=runBlocking { val h=AgentHarness(); ToolGateway(h.services).execute(call("focus_settings","""{"rest_minutes":10}"""),"k","短休息改成10分钟",true); assertEquals(FocusSettings(restMinutes=10),h.services.focus.state.value.settings) }
    @Test fun timerStatusContainsDefaultSettingsAndStatuses()=runBlocking { val h=AgentHarness(); val g=ToolGateway(h.services); g.execute(call("focus_control","""{"action":"start"}"""),"k","开始专注",true); val result=g.execute(call("focus_status"),"q","还剩多久",true); assertEquals("running",result["run"]!!.jsonObject["status"]!!.jsonPrimitive.content); assertEquals(25,result["settings"]!!.jsonObject["focusMinutes"]!!.jsonPrimitive.int) }
    @Test fun navigateStoresActualCalendarView()=runBlocking { val h=AgentHarness(); ToolGateway(h.services).execute(call("navigate","""{"page":"calendar","view":"three_day","date":"2026-10-08"}"""),"k","日历切三日视图",true); assertEquals("three_day",h.sessions.read("calendar_view")); assertEquals("2026-10-08",h.services.ui.events.value!!.date) }
    @Test fun invalidCalendarViewCannotPartiallyNavigate()=runBlocking { val h=AgentHarness(); val r=ToolGateway(h.services).execute(call("navigate","""{"page":"calendar","view":"year"}"""),"k","切换日历",true); assertEquals("error",r["status"]!!.jsonPrimitive.content); assertNull(h.services.ui.events.value) }
    @Test fun navigationSequenceSurvivesProcessRestart()=runBlocking { val h=AgentHarness(); h.services.ui.open("calendar"); val before=h.services.ui.events.value!!.sequence; val restored=UiCommands(h.sessions); restored.open("focus"); assertTrue(restored.events.value!!.sequence>before) }
    @Test fun arbitrarySoundPathIsNotAccepted()=runBlocking { val h=AgentHarness(); val result=ToolGateway(h.services).execute(call("set_alarm_sound","""{"item_id":"x","expected_version":1,"sound_path":"/secret"}"""),"k","换铃声",true); assertEquals("error",result["status"]!!.jsonPrimitive.content) }
    @Test fun cachedSoundUsesTrustedLibraryAndVersion()=runBlocking {
        val h=AgentHarness(); val alarm=h.services.items.create(ItemDraft(ItemKind.ALARM,"起床",schedule=ScheduleSpec("2026-10-08","08:00"))).item!!
        val services=h.services.copy(sounds=object: SoundLibrary { override suspend fun list()=listOf(SoundChoice("real","歌曲")); override suspend fun path(id: String)=if(id=="real") "/trusted/audio" else null })
        val r=ToolGateway(services).execute(call("set_alarm_sound","""{"item_id":"${alarm.id}","expected_version":1,"sound_id":"real"}"""),"k","起床闹钟换铃声",true)
        assertEquals("ok",r["status"]!!.jsonPrimitive.content); assertEquals("/trusted/audio",h.items.get(alarm.id)!!.alarm.soundPath)
    }
    @Test fun privateKnowledgeCannotBeEnabledByRemoteAgent()=runBlocking { val h=AgentHarness(); h.knowledge.save(KnowledgeSource("private","私人","正文",0,allowRemote=false)); val r=ToolGateway(h.services).execute(call("manage_knowledge","""{"operation":"enable","source_id":"private"}"""),"k","启用资料",true); assertEquals("error",r["status"]!!.jsonPrimitive.content) }
    @Test fun offlineTimerDoesNotCreateOrdinaryTask()=runBlocking { val h=AgentHarness(); val c=offline(h); c.send("开始一个二十五分钟的番茄钟"); assertTrue(h.items.all().isEmpty()); assertEquals(25*60_000L,h.services.focus.state.value.run!!.targetMillis); c.send("暂停专注"); assertEquals("paused",h.services.focus.state.value.run!!.status); c.send("继续专注"); assertEquals("running",h.services.focus.state.value.run!!.status) }
    @Test fun offlineCalendarCommandNavigatesInsteadOfSavingMemo()=runBlocking { val h=AgentHarness(); offline(h).send("日历切到三日视图看明天"); assertTrue(h.items.all().isEmpty()); assertEquals("three_day",h.services.ui.events.value!!.view) }
    @Test fun offlineRestSettingsWorkBeforeAnyTimerHasStarted()=runBlocking { val h=AgentHarness(); offline(h).send("短休息改成十分钟"); assertTrue(h.items.all().isEmpty()); assertEquals(10,h.services.focus.state.value.settings.restMinutes) }
    @Test fun fakeTimerSuccessIsBlockedWithoutTool()=runBlocking { val h=AgentHarness(); val c=h.coordinator("""{"mode":"say","text":"已开始专注"}"""); c.send("开始专注"); assertTrue(c.session.value.history.last().text.contains("还没有成功执行")); assertNull(h.services.focus.state.value.run) }
    @Test fun successfulTimerToolCanReportActualSuccess()=runBlocking { val h=AgentHarness(); val c=h.coordinator("""{"mode":"call","tool":"focus_control","args":{"action":"start"}}""","""{"mode":"say","text":"铭哥，已开始专注。"}"""); c.send("开始专注"); assertEquals("铭哥，已开始专注。",c.session.value.history.last().text); assertNotNull(h.services.focus.state.value.run) }
}
