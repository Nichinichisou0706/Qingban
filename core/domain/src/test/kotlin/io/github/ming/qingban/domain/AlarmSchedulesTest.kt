package io.github.ming.qingban.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AlarmSchedulesTest {
    private fun clock(local: String,zone: String="Asia/Shanghai")=Clock.fixed(LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant(),ZoneId.of(zone))
    @Test fun futureTimeRingsToday() { assertEquals("2026-10-08",AlarmSchedules.nextOneShot("08:00",clock("2026-10-08T07:00")).date) }
    @Test fun passedTimeRingsTomorrowAcrossYearBoundary() { assertEquals("2027-01-01",AlarmSchedules.nextOneShot("08:00",clock("2026-12-31T23:59")).date) }
    @Test fun timeAlreadyReachedToTheMinuteRingsTomorrow() { assertEquals("2026-10-09",AlarmSchedules.nextOneShot("08:00",clock("2026-10-08T08:00:30")).date) }
    @Test fun editingSoundKeepsExistingFutureAlarmDate() { assertEquals("2026-10-12",AlarmSchedules.nextOneShot("08:00",clock("2026-10-08T07:00"),"2026-10-12").date) }
    @Test fun expiredAlarmCanBeSavedAgainWithoutDatePicker() { assertEquals("2026-10-09",AlarmSchedules.nextOneShot("08:00",clock("2026-10-08T09:00"),"2026-10-07").date) }
    @Test fun nextAlarmUsesDeviceTimezoneAndIsActuallySchedulable() {
        val clock=clock("2026-10-08T07:00")
        val schedule=AlarmSchedules.nextOneShot("08:00",clock)
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"),ScheduleEngine.nextTrigger(PlanItem("a",kind=ItemKind.ALARM,title="起床",schedule=schedule),clock))
    }
    @Test fun changingShiftPhasePreservesWorkAndRestSegments() {
        val today=LocalDate.parse("2026-10-08")
        val rule=RecurrenceSpec(frequency="shift",startDate=today.toString(),workDays=2,restDays=2,cycleAnchor=today.toString())
        val restDay=AlarmSchedules.withShiftPhase(rule,3,today)
        assertEquals(3,AlarmSchedules.shiftPhase(restDay,today))
        assertFalse(RecurrenceRules.matches(restDay,today))
        assertFalse(RecurrenceRules.matches(restDay,today.plusDays(1)))
        assertTrue(RecurrenceRules.matches(restDay,today.plusDays(2)))
        RecurrenceRules.validate(restDay)
    }
}
