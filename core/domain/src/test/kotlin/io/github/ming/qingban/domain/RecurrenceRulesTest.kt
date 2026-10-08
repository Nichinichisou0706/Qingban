package io.github.ming.qingban.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RecurrenceRulesTest {
    private fun clock(day: String, time: String = "07:00") = Clock.fixed(LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"))
    private fun rule(frequency: String, start: String = "2026-01-01") = RecurrenceSpec(frequency = frequency, startDate = start)
    private fun alarm(rule: RecurrenceSpec, overrides: List<OccurrenceOverride> = emptyList()) = PlanItem("alarm", kind = ItemKind.ALARM, title = "起床", schedule = ScheduleSpec(time = "08:00", recurrence = rule), overrides = overrides)
    private fun next(item: PlanItem, clock: Clock): String? = ScheduleEngine.nextTrigger(item, clock)?.atZone(clock.zone)?.toLocalDateTime()?.toString()
    private fun shift() = RecurrenceSpec(frequency = RecurrenceRules.SHIFT, startDate = "2026-10-01", workDays = 2, restDays = 2, cycleAnchor = "2026-10-01")
    private fun validation(r: RecurrenceSpec) = ScheduleEngine.validate(ItemDraft(ItemKind.ALARM,"起床",schedule=ScheduleSpec(time="08:00",recurrence=r)))

    @Test fun dailyRequiresNoChosenDateAndAfterTimeMovesToTomorrow() {
        val item = alarm(RecurrenceSpec(weekdays = (1..7).toSet(), startDate = "2026-10-07"))
        assertNull(validation(item.schedule!!.recurrence!!))
        assertEquals("2026-10-07T08:00", next(item, clock("2026-10-07")))
        assertEquals("2026-10-08T08:00", next(item, clock("2026-10-07", "09:00")))
    }
    @Test fun legalWorkdaySkipsWeekdayHolidayAndIncludesAdjustedSaturday() {
        val item = alarm(rule(RecurrenceRules.WORKDAY))
        assertFalse(ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-07")))
        assertTrue(ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-10")))
        assertEquals("2026-10-08T08:00", next(item,clock("2026-10-01")))
        assertEquals("2026-10-10T08:00", next(item,clock("2026-10-09","09:00")))
    }
    @Test fun legalRestdayIncludesOrdinaryWeekendAndExcludesAdjustedSunday() {
        val item = alarm(rule(RecurrenceRules.RESTDAY))
        assertTrue(ScheduleEngine.occursOn(item,LocalDate.parse("2026-09-19")))
        assertFalse(ScheduleEngine.occursOn(item,LocalDate.parse("2026-09-20")))
        assertEquals("2026-09-25T08:00", next(item,clock("2026-09-19","09:00")))
    }
    @Test fun springFestivalGapLongerThanWeekDoesNotLoseAlarm() {
        assertEquals("2026-02-24T08:00",next(alarm(rule(RecurrenceRules.WORKDAY)),clock("2026-02-15")))
    }
    @Test fun holidayDataUnknownYearHasNoWeekdayFallback() {
        assertNull(MainlandHolidayCalendar.isWorkday(LocalDate.parse("2027-01-04")))
        val item = alarm(rule(RecurrenceRules.WORKDAY))
        assertFalse(ScheduleEngine.occursOn(item,LocalDate.parse("2027-01-04")))
        assertNull(next(item,clock("2027-01-01")))
        assertNotNull(RecurrenceRules.unavailableMessage(item,clock("2027-01-01")))
        assertNotNull(validation(rule(RecurrenceRules.WORKDAY,"2027-01-01")))
        assertNotNull(validation(rule(RecurrenceRules.WORKDAY).copy(endDate="2027-01-01")))
    }
    @Test fun explicitlyEndedSeriesDoesNotShowCalendarUpdateError() {
        val item = alarm(rule(RecurrenceRules.WORKDAY).copy(endDate="2026-12-31"))
        assertNull(next(item,clock("2027-01-01")))
        assertNull(RecurrenceRules.unavailableMessage(item,clock("2027-01-01")))
    }
    @Test fun calendarAndTriggerAgreeForEveryKnownDate() {
        for(frequency in listOf(RecurrenceRules.WORKDAY,RecurrenceRules.RESTDAY)) {
            val item = alarm(rule(frequency,"2025-01-01"))
            var day = MainlandHolidayCalendar.firstDate
            while(day <= MainlandHolidayCalendar.lastDate) {
                val matches = ScheduleEngine.occursOn(item,day)
                assertEquals("$frequency $day", matches, ScheduleEngine.onDate(item,day)!=null)
                if(matches) assertEquals(day.toString()+"T08:00",next(item,clock(day.toString())))
                day=day.plusDays(1)
            }
        }
    }
    @Test fun regularWeekdaysRemainDifferentFromLegalWorkdays() {
        val weekly = alarm(RecurrenceSpec(weekdays=(1..5).toSet(),startDate="2026-01-01"))
        assertTrue(ScheduleEngine.occursOn(weekly,LocalDate.parse("2026-10-07")))
        assertFalse(ScheduleEngine.occursOn(weekly,LocalDate.parse("2026-10-10")))
    }
    @Test fun shiftAlternatesCorrectlyAndNeverRunsBeforeAnchor() {
        val item = alarm(shift())
        assertEquals(listOf(true,true,false,false,true,true,false,false), (0L..7L).map { ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-01").plusDays(it)) })
        assertFalse(ScheduleEngine.occursOn(item,LocalDate.parse("2026-09-30")))
        assertEquals("2026-10-09T08:00",next(item,clock("2026-10-07")))
    }
    @Test fun longRestCycleStillSchedulesNextShift() {
        val item=alarm(shift().copy(workDays=1,restDays=90))
        assertEquals("2026-12-31T08:00",next(item,clock("2026-10-02")))
    }
    @Test fun shiftMissingOrInvalidParametersFailWithoutGuessing() {
        assertNotNull(validation(shift().copy(cycleAnchor=null)))
        assertNotNull(validation(shift().copy(workDays=0)))
        assertNotNull(validation(shift().copy(restDays=366)))
        assertNotNull(validation(shift().copy(cycleAnchor="2026-10-02")))
        assertNotNull(validation(shift().copy(weekdays=setOf(1))))
        assertNotNull(validation(rule(RecurrenceRules.WORKDAY).copy(workDays=2)))
    }
    @Test fun movedCancelledAndCompletedOccurrencesKeepTheirRules() {
        val item=alarm(shift(),listOf(OccurrenceOverride("2026-10-01",status=ItemStatus.CANCELLED),OccurrenceOverride("2026-10-02",ScheduleSpec("2026-10-03","10:00")),OccurrenceOverride("2026-10-05",status=ItemStatus.COMPLETED)))
        assertNull(ScheduleEngine.onDate(item,LocalDate.parse("2026-10-01")))
        assertNull(ScheduleEngine.onDate(item,LocalDate.parse("2026-10-02")))
        assertEquals("10:00",ScheduleEngine.onDate(item,LocalDate.parse("2026-10-03"))!!.schedule!!.time)
        assertEquals("2026-10-03T10:00",next(item,clock("2026-10-01")))
        assertEquals("2026-10-06T08:00",next(item,clock("2026-10-03","11:00")))
    }
    @Test fun advanceReminderPreservesShiftAcrossMidnight() {
        val item=alarm(shift()).copy(schedule=ScheduleSpec(time="00:10",recurrence=shift()),reminder=ReminderSpec(offsetMinutes=30))
        assertEquals("2026-10-04T23:40",next(item,clock("2026-10-04","23:00")))
    }
    @Test fun oldWeeklyJsonAndNewShiftBackupsRoundTrip() {
        val old=Json.decodeFromString<RecurrenceSpec>("""{"weekdays":[1,3],"start_date":"2026-10-01"}""")
        assertEquals(RecurrenceRules.WEEKLY,old.frequency)
        assertNull(old.cycleAnchor)
        val item=alarm(shift())
        assertEquals(item,Json.decodeFromString<PlanItem>(Json.encodeToString(item)))
        val archive=BackupArchive(items=listOf(item),knowledge=emptyList(),preferences=emptyList())
        assertEquals(archive,BackupCodec.decode(BackupCodec.encode(archive)))
    }
    @Test fun recurrenceEndIsInclusiveForSchedulingAndCalendar() {
        val item=alarm(shift().copy(endDate="2026-10-02"))
        assertTrue(ScheduleEngine.occursOn(item,LocalDate.parse("2026-10-02")))
        assertEquals("2026-10-02T08:00",next(item,clock("2026-10-02")))
        assertNull(next(item,clock("2026-10-02","09:00")))
    }
}
