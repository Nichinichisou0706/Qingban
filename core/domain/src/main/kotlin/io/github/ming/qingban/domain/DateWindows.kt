package io.github.ming.qingban.domain

import java.time.LocalDate

object DateWindows {
    fun centered(day: LocalDate, count: Int = 7): List<LocalDate> {
        require(count > 0 && count % 2 == 1)
        return (-(count / 2)..count / 2).map { day.plusDays(it.toLong()) }
    }
}

val PlanItem.isHabitTask: Boolean get() = kind == ItemKind.TASK && schedule?.recurrence != null
