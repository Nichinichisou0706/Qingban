package io.github.ming.qingban.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Clock-style alarm input; explicit dates already stored by the user remain supported. */
object AlarmSchedules {
    fun nextOneShot(time: String, clock: Clock, existingDate: String? = null): ScheduleSpec {
        val today=LocalDate.now(clock)
        val selected=LocalTime.parse(time)
        if(existingDate!=null && LocalDate.parse(existingDate).atTime(selected).atZone(clock.zone).toInstant().isAfter(clock.instant())) return ScheduleSpec(date=existingDate,time=time)
        val day=if(today.atTime(selected).atZone(clock.zone).toInstant().isAfter(clock.instant())) today else today.plusDays(1)
        return ScheduleSpec(date=day.toString(),time=time)
    }
    fun shiftPhase(rule: RecurrenceSpec, today: LocalDate): Int {
        val length=(rule.workDays ?: 2)+(rule.restDays ?: 2)
        val anchor=rule.cycleAnchor?.let(LocalDate::parse) ?: today
        return Math.floorMod(ChronoUnit.DAYS.between(anchor,today),length.toLong()).toInt()+1
    }
    fun withShiftPhase(rule: RecurrenceSpec, phase: Int, today: LocalDate): RecurrenceSpec {
        val length=(rule.workDays ?: 2)+(rule.restDays ?: 2)
        require(phase in 1..length) { "请选择有效的轮班周期天数" }
        return rule.copy(cycleAnchor=today.minusDays(phase-1L).toString(),startDate=today.toString())
    }
}
