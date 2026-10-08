package io.github.ming.qingban.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CalendarImporterTest {
    private fun calendar(event: String)="BEGIN:VCALENDAR\nBEGIN:VEVENT\n$event\nEND:VEVENT\nEND:VCALENDAR"
    private val zone=ZoneId.of("Asia/Shanghai")
    @Test fun utcTimeConvertsToDeviceZone() { val item=CalendarImporter.parse(calendar("SUMMARY:会议\nDTSTART:20261007T010000Z\nDTEND:20261007T020000Z"),zone).drafts.single(); assertEquals("09:00",item.schedule!!.time); assertEquals(60,item.durationMinutes); assertNull(item.reminder) }
    @Test fun allDayEventDoesNotInventTime() { val item=CalendarImporter.parse(calendar("SUMMARY:买牛奶\nDTSTART;VALUE=DATE:20261007"),zone).drafts.single(); assertNull(item.schedule!!.time); assertEquals("2026-10-07",item.schedule.date) }
    @Test fun weeklyRecurrenceHasCorrectWeekdays() { val item=CalendarImporter.parse(calendar("SUMMARY:运动\nDTSTART:20261007T190000\nRRULE:FREQ=WEEKLY;BYDAY=WE,FR"),zone).drafts.single(); assertEquals(setOf(3,5),item.schedule!!.recurrence!!.weekdays) }
    @Test fun unsupportedRuleIsWarningInsteadOfWrongSchedule() { val result=CalendarImporter.parse(calendar("SUMMARY:运动\nDTSTART:20261007T190000\nRRULE:FREQ=MONTHLY"),zone); assertTrue(result.drafts.isEmpty()); assertEquals(1,result.warnings.size) }
    @Test fun alarmDetailsDoNotOverwriteEvent() { val item=CalendarImporter.parse(calendar("SUMMARY:会议\nDTSTART:20261007T190000\nBEGIN:VALARM\nSUMMARY:提醒标题\nTRIGGER:-PT15M\nEND:VALARM"),zone).drafts.single(); assertEquals("会议",item.title); assertNull(item.reminder) }
    @Test fun foldedTextAndEscapesArePreserved() { val item=CalendarImporter.parse(calendar("SUMMARY:一个很长\n 的标题\nDTSTART:20261007T190000\nDESCRIPTION:第一行\\n第二行"),zone).drafts.single(); assertEquals("一个很长的标题",item.title); assertEquals("第一行\n第二行",item.detail) }
}
