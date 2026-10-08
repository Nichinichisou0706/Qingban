package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.time.*

class HabitTest {
    @Test fun habitWorksWithoutModelAndDoesNotDuplicateTasks()=runBlocking {
        val h=AgentHarness(); val c=AgentCoordinator(h.services,object: ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("offline") },"preset")
        c.send("建立每天阅读打卡习惯"); assertEquals("阅读",h.services.habits.state.value.habits.single().title); assertTrue(h.items.all().isEmpty())
        c.send("阅读习惯打卡"); assertTrue(h.services.habits.state.value.checks.single().done)
        c.send("撤销阅读习惯打卡"); assertFalse(h.services.habits.state.value.checks.single().done)
        c.send("查询习惯进度"); assertTrue(c.session.value.history.last().text.contains("近7天 0/1"))
    }
    @Test fun checksAreIdempotentAndRestoreAfterRestart()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="喝水")
        h.services.habits.check(habit.id,key="check"); h.services.habits.check(habit.id,key="check")
        assertEquals(1,h.services.habits.state.value.checks.size)
        val restored=HabitService(h.sessions,h.services.clock); assertEquals(h.services.habits.state.value,restored.load())
    }
    @Test fun onlyPlannedRecentPastDatesCanBeChecked()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="周末阅读",weekdays=setOf(6,7))
        assertTrue(runCatching { h.services.habits.check(habit.id) }.isFailure)
        assertTrue(runCatching { h.services.habits.check(habit.id,"2026-10-07") }.isFailure)
        assertTrue(runCatching { h.services.habits.check(habit.id,"2026-10-04") }.isFailure) // Before creation.
    }
    @Test fun reminderRequiresTimeAndCannotAlterDataOnError()=runBlocking {
        val h=AgentHarness(); assertTrue(runCatching { h.services.habits.save(title="阅读",remind=true) }.isFailure)
        assertTrue(h.services.habits.state.value.habits.isEmpty()); assertNull(h.sessions.read("habit_state"))
    }
    @Test fun pauseRetainsHistoryAndResumingKeepsRule()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="阅读",weekdays=setOf(2,4,6),time="20:00",remind=true)
        h.services.habits.check(habit.id); val paused=h.services.habits.save(habit.id,habit.version,habit.title,habit.weekdays,habit.time,habit.remind,false)
        assertFalse(paused.enabled); assertEquals(1,h.services.habits.state.value.checks.size)
        val resumed=h.services.habits.save(paused.id,paused.version,paused.title,paused.weekdays,paused.time,paused.remind,true)
        assertEquals(setOf(2,4,6),resumed.weekdays); assertEquals("20:00",resumed.time)
    }
    @Test fun staleHabitEditCannotOverwriteNewRule()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="阅读")
        h.services.habits.save(habit.id,habit.version,"阅读20分钟")
        assertTrue(runCatching { h.services.habits.save(habit.id,habit.version,"旧标题") }.isFailure); assertEquals("阅读20分钟",h.services.habits.state.value.habits.single().title)
    }
    @Test fun weekdayStreakSkipsRestDaysAndTodayIsNotFailure() {
        val habit=Habit("reading",title="阅读",weekdays=(1..5).toSet(),createdDate="2026-09-30")
        val state=HabitState(listOf(habit),listOf("2026-10-01","2026-10-02").map { HabitCheck(habit.id,it) })
        val p=HabitRules.progress(habit,state,LocalDate.parse("2026-10-05")); assertEquals(2,p.streak); assertFalse(p.todayDone)
    }
    @Test fun modelCannotInventOrCheckCompletionFromQuestion()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="阅读")
        val result=ToolGateway(h.services).execute(Decision.Call("habit_manage",buildJsonObject { put("operation","check"); put("habit_id",habit.id) }),"bad","我今天阅读了吗？",true)
        assertEquals("error",result["status"]!!.jsonPrimitive.content); assertTrue(h.services.habits.state.value.checks.isEmpty())
    }
    @Test fun modelCannotEnableSupervisionWhenOnlyAskedHow()=runBlocking {
        val h=AgentHarness(); val result=ToolGateway(h.services).execute(Decision.Call("gentle_settings",buildJsonObject { put("enabled",true) }),"bad","怎么使用温和监督？",true)
        assertEquals("error",result["status"]!!.jsonPrimitive.content); assertFalse(h.services.habits.state.value.gentle)
    }
    @Test fun explicitModelHabitCanBeCheckedAndUnChecked()=runBlocking {
        val h=AgentHarness(); val g=ToolGateway(h.services)
        val create=Decision.Call("habit_manage",buildJsonObject { put("operation","create"); put("title","阅读"); put("time","20:00"); put("remind",true) })
        val first=g.execute(create,"create","建立每天阅读习惯，晚上八点提醒",true); assertEquals("ok",first["status"]!!.jsonPrimitive.content)
        assertEquals(first,g.execute(create,"create","建立每天阅读习惯，晚上八点提醒",true)); assertEquals(1,h.services.habits.state.value.habits.size)
        val habit=h.services.habits.state.value.habits.single(); val call=Decision.Call("habit_manage",buildJsonObject { put("operation","check"); put("habit_id",habit.id) })
        assertEquals("ok",g.execute(call,"check","阅读已完成，请打卡",true)["status"]!!.jsonPrimitive.content); assertTrue(h.services.habits.state.value.checks.single().done)
        val status=g.execute(Decision.Call("habit_status",buildJsonObject { put("query","阅读") }),"status","查询阅读习惯",true)
        assertEquals(1,status["habits"]!!.jsonArray.single().jsonObject["streak"]!!.jsonPrimitive.int)
    }
    @Test fun backupPreservesHabitsAndRejectsInvalidRecords()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="拉伸"); h.services.habits.check(habit.id)
        val archive=BackupArchive(items=emptyList(),knowledge=emptyList(),preferences=emptyList(),habits=h.services.habits.state.value)
        assertEquals(archive,BackupCodec.decode(BackupCodec.encode(archive)))
        assertTrue(runCatching { BackupCodec.validate(archive.copy(habits=archive.habits!!.copy(checks=listOf(HabitCheck("missing","2026-10-06"))))) }.isFailure)
    }
    @Test fun deletingHabitClearsOnlyItsRecordsAndRejectsStaleVersion()=runBlocking {
        val h=AgentHarness(); val a=h.services.habits.save(title="阅读"); val b=h.services.habits.save(title="拉伸")
        h.services.habits.check(a.id); h.services.habits.check(b.id)
        assertTrue(runCatching { h.services.habits.delete(a.id,a.version+1) }.isFailure)
        assertEquals(2,h.services.habits.state.value.habits.size)
        h.services.habits.delete(a.id,a.version,"delete-reading"); h.services.habits.delete(a.id,a.version,"delete-reading")
        assertEquals(listOf(b),h.services.habits.state.value.habits); assertEquals(b.id,h.services.habits.state.value.checks.single().habitId)
        assertEquals(h.services.habits.state.value,HabitService(h.sessions,h.services.clock).load())
    }
    @Test fun modelCannotDeleteHabitWhenAskedToPause()=runBlocking {
        val h=AgentHarness(); val habit=h.services.habits.save(title="阅读")
        val result=ToolGateway(h.services).execute(Decision.Call("habit_manage",buildJsonObject { put("operation","delete"); put("habit_id",habit.id); put("expected_version",habit.version) }),"delete","暂停阅读习惯",true)
        assertEquals("error",result["status"]!!.jsonPrimitive.content); assertEquals(listOf(habit),h.services.habits.state.value.habits)
    }
    @Test fun gentleRemindersRespectCompletionDateAndQuietHours()=runBlocking {
        val h=AgentHarness(); val task=h.services.items.create(ItemDraft(ItemKind.TASK,"阅读",schedule=ScheduleSpec("2026-10-06","20:30"),durationMinutes=60,reminder=ReminderSpec(offsetMinutes=0))).item!!
        val now=LocalDateTime.parse("2026-10-06T20:00")
        assertTrue(GentleRules.times(true,task,now).isEmpty()) // Both reminders fall in quiet hours.
        val earlier=task.copy(schedule=task.schedule!!.copy(time="18:00"))
        assertEquals(listOf(LocalDateTime.parse("2026-10-06T19:30"),LocalDateTime.parse("2026-10-06T20:00")),GentleRules.times(true,earlier,now))
        assertTrue(GentleRules.times(false,earlier,now).isEmpty())
        assertTrue(GentleRules.times(true,earlier.copy(status=ItemStatus.COMPLETED),now).isEmpty())
        assertTrue(GentleRules.times(true,earlier.copy(reminder=null),now).isEmpty())
        assertTrue(GentleRules.times(true,earlier,now.plusDays(1)).isEmpty())
    }
    @Test fun gentleDailyLimitAndDuplicateGuardAreLocal() {
        val now=LocalDateTime.parse("2026-10-06T20:00")
        assertTrue(GentleRules.canNotify(now,"2026-10-06",1,false))
        assertFalse(GentleRules.canNotify(now,"2026-10-06",2,false))
        assertFalse(GentleRules.canNotify(now,"2026-10-06",0,true))
        assertFalse(GentleRules.canNotify(now.withHour(22),"2026-10-06",0,false))
        assertFalse(GentleRules.canNotify(now,"2026-10-05",0,false))
    }
    @Test fun offlineOpenGentleRequestChangesSettingsInsteadOfNavigating()=runBlocking {
        val h=AgentHarness(); val c=AgentCoordinator(h.services,object: ModelClient { override suspend fun available()=false; override suspend fun decide(request: AgentRequest)=error("offline") },"preset")
        c.send("打开温和督促"); assertTrue(h.services.habits.state.value.gentle)
        c.send("关闭温和督促"); assertFalse(h.services.habits.state.value.gentle)
    }
}
