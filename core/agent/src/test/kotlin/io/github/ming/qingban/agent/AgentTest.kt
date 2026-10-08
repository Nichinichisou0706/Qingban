package io.github.ming.qingban.agent

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AgentTest {
    private val planner = LocalPlanner(Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneId.of("Asia/Shanghai")))
    @Test fun tomorrowTodoHasNoReminder() { val d = (planner.parse("明天买牛奶", null) as LocalAction.Create).draft; assertEquals("买牛奶", d.title); assertEquals("2026-10-07", d.date); assertNull(d.time); assertFalse(d.needsReminder) }
    @Test fun alarmAsksOnlyForMissingTime() { val action = planner.parse("明早叫我起床", null) as LocalAction.Ask; assertEquals("2026-10-07", action.draft.date); val d = (planner.parse("八点半", action.draft) as LocalAction.Create).draft; assertEquals("08:30", d.time) }
    @Test fun afternoonConvertsTo24Hour() { assertEquals("15:00", planner.time("下午三点")) }
    @Test fun vagueEveningStaysVague() { val d = (planner.parse("明晚整理照片", null) as LocalAction.Create).draft; assertNull(d.time); assertEquals("evening", d.period) }
    @Test fun malformedOutputCannotExecute() { assertThrows(Exception::class.java) { DecisionParser.parse("已保存") }; assertThrows(Exception::class.java) { DecisionParser.parse("{\"mode\":\"say\",\"text\":\"ok\",\"tool\":\"delete\"}") } }
    @Test fun questionOptionsBounded() { assertThrows(Exception::class.java) { DecisionParser.parse("{\"mode\":\"ask\",\"question\":\"which\",\"options\":[\"1\",\"2\",\"3\",\"4\"]}") } }
    @Test fun modelToolEnvelopeHasStrictFields() { assertTrue(DecisionParser.parse("{\"mode\":\"call\",\"tool\":\"query_items\",\"args\":{}}") is Decision.Call) }
    @Test fun nextWeekFridayUsesDeviceDate() { assertEquals("2026-10-16", planner.date("下周五开会")) }
    @Test fun deadlineDoesNotBecomePlannedDate() { val d = (planner.parse("周五前交报告", null) as LocalAction.Create).draft; assertEquals("2026-10-09", d.deadlineDate); assertNull(d.date); assertEquals("交报告", d.title) }
    @Test fun dateAnswerKeepsKnownAlarmTime() { val d = (planner.parse("明天", LocalDraft("alarm", "起床", "2026-10-06", "08:00")) as LocalAction.Create).draft; assertEquals("2026-10-07", d.date); assertEquals("08:00", d.time) }
    @Test fun headphonesAnswerSurvivesTimeQuestion() { val ask=planner.parse("明早叫我起床",null) as LocalAction.Ask; val answer=(planner.parse("八点，别外放",ask.draft) as LocalAction.Create).draft; assertTrue(answer.alarm.headphonesOnly); assertEquals("08:00",answer.time) }
    @Test fun dailyAlarmKeepsRecurrence() { val answer=(planner.parse("每天八点叫我起床",null) as LocalAction.Create).draft; assertEquals((1..7).toSet(),answer.recurrence!!.weekdays) }
    @Test fun weeklyUnknownDayIsAskedInsteadOfGuessed() { val ask=planner.parse("每周七点叫我起床",null) as LocalAction.Ask; assertEquals("weekdays",ask.draft.pendingField); val answer=(planner.parse("周一",ask.draft) as LocalAction.Create).draft; assertEquals(setOf(1),answer.recurrence!!.weekdays); assertEquals("07:00",answer.time) }
    @Test fun legalWorkdayUsesRealHolidayRule() { val d=(planner.parse("法定工作日八点叫我起床",null) as LocalAction.Create).draft; assertEquals("cn_workday",d.recurrence!!.frequency); assertEquals("起床",d.title); assertTrue(d.recurrence.weekdays.isEmpty()) }
    @Test fun legalRestDayKeepsRuleDuringTimeQuestion() { val ask=planner.parse("法定节假日叫我起床",null) as LocalAction.Ask; val d=(planner.parse("八点",ask.draft) as LocalAction.Create).draft; assertEquals("cn_restday",d.recurrence!!.frequency); assertEquals("08:00",d.time) }
    @Test fun plainWorkdayStillMeansMondayToFriday() { val d=(planner.parse("工作日八点叫我起床",null) as LocalAction.Create).draft; assertEquals("weekly",d.recurrence!!.frequency); assertEquals((1..5).toSet(),d.recurrence.weekdays) }
    @Test fun explicitWeekdayRangeDoesNotPolluteAlarmName() { val d=(planner.parse("周一至周五八点叫我起床",null) as LocalAction.Create).draft; assertEquals("起床",d.title); assertEquals((1..5).toSet(),d.recurrence!!.weekdays) }
    @Test fun incompleteOfflineShiftDoesNotCreateGuessedAlarm() { assertTrue(planner.parse("设一个轮班闹钟",null) is LocalAction.Unsupported) }
    @Test fun promptExamplesContainValidDecisionJson() {
        val rendered=PromptPreset.render("",Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"),ZoneId.of("Asia/Shanghai")))
        rendered.lineSequence().map(String::trim).filter { it.startsWith("决策：") }.forEach { DecisionParser.parse(it.removePrefix("决策：")) }
    }
}
