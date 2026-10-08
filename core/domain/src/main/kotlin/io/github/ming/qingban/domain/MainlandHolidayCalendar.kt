package io.github.ming.qingban.domain

import java.time.LocalDate

/** Offline mainland-China arrangements. Unknown years are deliberately not inferred from weekdays. */
object MainlandHolidayCalendar {
    val firstDate: LocalDate = LocalDate.of(2025, 1, 1)
    val lastDate: LocalDate = LocalDate.of(2026, 12, 31)
    const val COVERAGE = "中国大陆 · 2025–2026 年"
    // State Council notices; dates cross-checked with chinese-calendar/scripts/data.py.
    val sources = mapOf(
        2025 to "https://www.gov.cn/zhengce/content/202411/content_6986382.htm",
        2026 to "https://www.gov.cn/zhengce/content/202511/content_7047090.htm"
    )
    private fun dates(vararg ranges: String): Set<LocalDate> = ranges.flatMap { range ->
        val parts = range.split('/')
        val start = LocalDate.parse(parts[0]); val end = LocalDate.parse(parts.last())
        generateSequence(start) { it.plusDays(1).takeUnless { day -> day.isAfter(end) } }.toList()
    }.toSet()
    private val holidays = dates(
        "2025-01-01", "2025-01-28/2025-02-04", "2025-04-04/2025-04-06",
        "2025-05-01/2025-05-05", "2025-05-31/2025-06-02", "2025-10-01/2025-10-08",
        "2026-01-01/2026-01-03", "2026-02-15/2026-02-23", "2026-04-04/2026-04-06",
        "2026-05-01/2026-05-05", "2026-06-19/2026-06-21", "2026-09-25/2026-09-27",
        "2026-10-01/2026-10-07"
    )
    private val extraWorkdays = dates(
        "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"
    )
    fun covers(date: LocalDate): Boolean = date >= firstDate && date <= lastDate
    /** null means that year's official calendar is unavailable; false includes weekends and holidays. */
    fun isWorkday(date: LocalDate): Boolean? = if (!covers(date)) null
        else date in extraWorkdays || (date !in holidays && date.dayOfWeek.value <= 5)
    // Like the clock reference, the holiday mode covers all rest days, including ordinary weekends.
    fun isRestDay(date: LocalDate): Boolean? = isWorkday(date)?.not()
}
