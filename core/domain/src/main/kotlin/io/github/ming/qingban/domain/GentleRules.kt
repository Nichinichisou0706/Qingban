package io.github.ming.qingban.domain

import java.time.LocalDateTime
import java.time.LocalTime

/** Extra reminders for today's unfinished tasks; all decisions are local. */
object GentleRules {
    fun times(enabled: Boolean, source: PlanItem, now: LocalDateTime): List<LocalDateTime> {
        if(!enabled || source.kind!=ItemKind.TASK || source.status!=ItemStatus.OPEN || source.reminder==null) return emptyList()
        val day=now.toLocalDate()
        val view=if(source.schedule?.recurrence!=null) ScheduleEngine.onDate(source,day) else source.takeIf { it.schedule?.date==null || it.schedule.date==day.toString() }
        if(view?.status!=ItemStatus.OPEN) return emptyList()
        val base=view.reminder?.atLocal?.let(LocalDateTime::parse) ?: view.schedule?.time?.let { day.atTime(LocalTime.parse(it)).plusMinutes((view.durationMinutes ?: 0).toLong()) } ?: return emptyList()
        return listOf(30L,60L).map { base.plusMinutes(it) }.filter { it.toLocalDate()==day && it.hour in 8..21 }
    }
    fun canNotify(now: LocalDateTime, day: String, count: Int, alreadySent: Boolean) =
        now.toLocalDate().toString()==day && now.hour in 8..21 && count<2 && !alreadySent
}
