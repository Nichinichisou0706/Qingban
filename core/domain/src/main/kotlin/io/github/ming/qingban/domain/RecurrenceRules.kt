package io.github.ming.qingban.domain

import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Shared by reminder scheduling, calendar projection, editors and agent tools. */
object RecurrenceRules {
    const val WEEKLY = "weekly"
    const val WORKDAY = "cn_workday"
    const val RESTDAY = "cn_restday"
    const val SHIFT = "shift"
    fun usesHolidayCalendar(rule: RecurrenceSpec): Boolean = rule.frequency in setOf(WORKDAY, RESTDAY)
    fun validate(rule: RecurrenceSpec) {
        val start = LocalDate.parse(rule.startDate)
        val end = rule.endDate?.let(LocalDate::parse)
        require(end == null || end >= start) { "重复结束日期早于开始日期" }
        when (rule.frequency) {
            WEEKLY -> require(rule.weekdays.isNotEmpty() && rule.weekdays.all { it in 1..7 }) { "至少选择一个重复星期" }
            WORKDAY, RESTDAY -> {
                require(MainlandHolidayCalendar.covers(start) && (end == null || MainlandHolidayCalendar.covers(end))) {
                    "法定日历仅覆盖 ${MainlandHolidayCalendar.COVERAGE}，其他年份请更新日历后再设置"
                }
                require(rule.weekdays.isEmpty()) { "法定重复规则不能同时指定星期" }
            }
            SHIFT -> {
                require(rule.weekdays.isEmpty()) { "轮班规则不能同时指定星期" }
                require(rule.workDays in 1..365 && rule.restDays in 1..365) { "轮班需指定上班和休息天数，各为 1–365 天" }
                val anchor = rule.cycleAnchor?.let(LocalDate::parse)
                require(anchor != null && anchor <= start) { "轮班需要首个上班日，且不能晚于生效日期" }
            }
            else -> error("不支持的重复规则")
        }
        if (rule.frequency != SHIFT) require(rule.workDays == null && rule.restDays == null && rule.cycleAnchor == null) { "轮班参数仅用于轮班规则" }
    }
    fun matches(rule: RecurrenceSpec, date: LocalDate): Boolean {
        if (date < LocalDate.parse(rule.startDate) || (rule.endDate != null && date > LocalDate.parse(rule.endDate))) return false
        return when (rule.frequency) {
            WEEKLY -> date.dayOfWeek.value in rule.weekdays
            WORKDAY -> MainlandHolidayCalendar.isWorkday(date) == true
            RESTDAY -> MainlandHolidayCalendar.isRestDay(date) == true
            SHIFT -> {
                val work = rule.workDays ?: return false; val rest = rule.restDays ?: return false
                val anchor = rule.cycleAnchor?.let(LocalDate::parse) ?: return false
                val elapsed = ChronoUnit.DAYS.between(anchor, date)
                elapsed >= 0 && work in 1..365 && rest in 1..365 && elapsed % (work + rest) < work
            }
            else -> false
        }
    }
    fun searchEnd(rule: RecurrenceSpec, start: LocalDate, overrideCount: Int, offsetDays: Long): LocalDate {
        val gap = when (rule.frequency) { SHIFT -> (rule.restDays ?: 365) + 1L; WORKDAY, RESTDAY -> 366L; else -> 7L }
        val horizon = start.plusDays(gap * (overrideCount + 1L) + offsetDays + 2L)
        val end = rule.endDate?.let(LocalDate::parse) ?: horizon
        return minOf(horizon, end, if (usesHolidayCalendar(rule)) MainlandHolidayCalendar.lastDate else horizon)
    }
    /** A series hasn't ended, but its next date depends on an unpublished/unbundled year. */
    fun unavailableMessage(item: PlanItem, clock: Clock): String? {
        val rule = item.schedule?.recurrence ?: return null
        if (item.status != ItemStatus.OPEN || !usesHolidayCalendar(rule)) return null
        if (item.kind != ItemKind.ALARM && item.reminder == null) return null
        val today = LocalDate.now(clock)
        if (rule.endDate != null && LocalDate.parse(rule.endDate) <= MainlandHolidayCalendar.lastDate) return null
        return if (today >= MainlandHolidayCalendar.firstDate)
            "法定日历待更新：仅覆盖 ${MainlandHolidayCalendar.COVERAGE}，未推测后续日期" else null
    }
    fun label(rule: RecurrenceSpec?): String = when (rule?.frequency) {
        null -> "只响一次"
        WORKDAY -> "法定工作日"
        RESTDAY -> "法定节假日／休息日"
        SHIFT -> "轮班制 · 上 ${rule.workDays} 天休 ${rule.restDays} 天"
        WEEKLY -> when (rule.weekdays) {
            (1..7).toSet() -> "每天"
            setOf(1, 2, 3, 4, 5) -> "周一至周五"
            setOf(6, 7) -> "周末"
            else -> rule.weekdays.sorted().joinToString("、") { "周${"一二三四五六日".getOrNull(it - 1) ?: '?'}" }
        }
        else -> "重复规则待更新"
    }
}
