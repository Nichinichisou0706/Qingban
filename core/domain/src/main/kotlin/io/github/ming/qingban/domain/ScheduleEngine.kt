package io.github.ming.qingban.domain

import java.time.*

object ScheduleEngine {
    fun validate(draft: ItemDraft): String? = runCatching {
        require(draft.title.trim().isNotEmpty()) { "请填写事项名称" }
        require(draft.title.length <= 240) { "名称过长，最多 240 字" }
        require(draft.detail.length<=32_000 && draft.label.length<=80 && draft.dependsOn.size<=50) { "详情最多 32000 字，标签最多 80 字，前置事项最多 50 项" }
        require(draft.durationMinutes == null || draft.durationMinutes in 1..1440) { "时长需为 1–1440 分钟" }
        require(draft.checklist.size<=30 && draft.checklist.all { it.text.isNotBlank() && it.text.length<=240 }) { "清单最多 30 项，每项最多 240 字" }
        val s = draft.schedule
        s?.let {
            require(it.date == null || it.rangeStart == null) { "具体日期与日期范围不能同时设置" }
            require(it.time == null || it.period == null) { "具体时间与模糊时段不能同时设置" }
            it.date?.let(LocalDate::parse); it.time?.let(LocalTime::parse)
            require(it.time == null || it.date != null || it.recurrence != null) { "具体时间需要日期" }
            require(it.period == null || it.period in setOf("morning", "afternoon", "evening")) { "无效时段" }
            require((it.rangeStart == null) == (it.rangeEnd == null)) { "日期范围需要开始和结束日期" }
            if (it.rangeStart != null && it.rangeEnd != null) require(!LocalDate.parse(it.rangeEnd).isBefore(LocalDate.parse(it.rangeStart))) { "日期范围顺序不正确" }
            it.recurrence?.let { r ->
                RecurrenceRules.validate(r)
            }
        }
        draft.deadline?.let { LocalDate.parse(it.date); it.time?.let(LocalTime::parse) }
        draft.reminder?.let { r ->
            require((r.atLocal != null) xor (r.offsetMinutes != null)) { "提醒必须指定准确时间或提前分钟数" }
            r.atLocal?.let(LocalDateTime::parse)
            r.offsetMinutes?.let { require(it in 0..525600 && s?.time != null && (s.date != null || s.recurrence != null)) { "提前提醒需要准确的事项时间，提前分钟数最多一年" } }
            require(s?.recurrence==null || r.atLocal==null) { "重复事项请使用提前分钟数，独立日期提醒用于单次事项" }
        }
        if (draft.kind == ItemKind.ALARM) require(s?.time != null && (s.date != null || s.recurrence != null)) { "闹钟需要准确的日期和时间" }
        require(draft.alarm.snoozeMinutes in 1..60) { "稍后提醒需为 1–60 分钟" }
        if(draft.kind == ItemKind.ALARM) require(draft.alarm.playAudio || draft.alarm.vibrate) { "闹钟至少需要音乐或振动" }
    }.exceptionOrNull()?.let { if (it is DateTimeException) "日期或时间格式不正确" else it.message ?: "参数不正确" }

    fun nextTrigger(item: PlanItem, clock: Clock): Instant? {
        if (item.deleted || item.status != ItemStatus.OPEN) return null
        if (item.kind != ItemKind.ALARM && item.reminder == null) return null
        val s = item.schedule
        if (s?.recurrence == null && item.delivery == DeliveryState.FIRED) return null
        val now = clock.instant()
        val zone = clock.zone
        if (item.reminder?.atLocal != null) return LocalDateTime.parse(item.reminder.atLocal).atZone(zone).toInstant().takeIf { it.isAfter(now) }
        val time = s?.time?.let(LocalTime::parse) ?: return null
        val offset = item.reminder?.offsetMinutes?.toLong() ?: 0L
        val candidates = mutableListOf<Instant>()
        if (s.recurrence == null) {
            s.date?.let { candidates += LocalDate.parse(it).atTime(time).atZone(zone).toInstant().minusSeconds(offset * 60) }
        } else {
            val r = s.recurrence
            val overridesByDay = item.overrides.associateBy { it.originalDate }
            val start = maxOf(LocalDate.now(clock).minusDays(1), LocalDate.parse(r.startDate))
            val end = RecurrenceRules.searchEnd(r, start, item.overrides.size, offset / 1440L)
            for (n in 0L..java.time.temporal.ChronoUnit.DAYS.between(start, end)) {
                val day = start.plusDays(n)
                if (!RecurrenceRules.matches(r, day)) continue
                val override = overridesByDay[day.toString()]
                if (override?.status != null && override.status != ItemStatus.OPEN) continue
                if (override?.schedule != null) continue
                val trigger=day.atTime(time).atZone(zone).toInstant().minusSeconds(offset * 60)
                candidates += trigger
                if(trigger.isAfter(now)) break
            }
            item.overrides.filter { it.status == ItemStatus.OPEN && it.schedule?.date != null && it.schedule.time != null }.forEach {
                candidates += LocalDate.parse(it.schedule!!.date).atTime(LocalTime.parse(it.schedule.time)).atZone(zone).toInstant().minusSeconds(offset * 60)
            }
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    fun occursOn(item: PlanItem, date: LocalDate): Boolean {
        if (item.deleted || item.status != ItemStatus.OPEN) return false
        val s = item.schedule ?: return false
        item.overrides.firstOrNull { it.schedule?.date == date.toString() && it.status == ItemStatus.OPEN }?.let { return true }
        item.overrides.firstOrNull { it.originalDate == date.toString() }?.let { return it.status == ItemStatus.OPEN && it.schedule == null }
        s.recurrence?.let { r -> return RecurrenceRules.matches(r, date) }
        return s.date == date.toString() || (s.rangeStart != null && s.rangeEnd != null && !date.isBefore(LocalDate.parse(s.rangeStart)) && !date.isAfter(LocalDate.parse(s.rangeEnd)))
    }
    fun onDate(item: PlanItem, date: LocalDate, includeCancelled: Boolean = false): PlanItem? {
        if(item.deleted) return null
        if(item.status==ItemStatus.CANCELLED && !includeCancelled) return null
        val day=date.toString(); val schedule=item.schedule
        if(item.deadline?.date==day && schedule?.recurrence==null && schedule?.date!=day) return item.copy(schedule=null)
        if(schedule==null) return null
        val moved=item.overrides.firstOrNull { it.schedule?.date==day }
        if(moved!=null) return if(moved.status==ItemStatus.CANCELLED && !includeCancelled) null else item.copy(schedule=moved.schedule?.copy(recurrence=schedule.recurrence),status=moved.status,checklist=moved.checklist ?: item.checklist)
        val original=item.overrides.firstOrNull { it.originalDate==day }
        if(original?.schedule!=null) return null
        if(original!=null) return if(original.status==ItemStatus.CANCELLED && !includeCancelled) null else item.copy(status=original.status,checklist=original.checklist ?: item.checklist)
        return item.takeIf { occursOn(it.copy(status=ItemStatus.OPEN),date) }
    }
}
