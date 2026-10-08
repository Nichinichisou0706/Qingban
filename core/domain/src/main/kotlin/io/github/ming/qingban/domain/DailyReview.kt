package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.*
import java.util.UUID

@Serializable data class ReviewMove(val id: String,val version: Int,val title: String,val before: ScheduleSpec?,val after: ScheduleSpec,val reminder: ReminderSpec?,val reason: String,val occurrence: String? = null)
@Serializable data class DailyReview(val id: String,val day: String,val moves: List<ReviewMove>,val notes: List<String>,val expiresAt: Long)

/** Durable suggestions. Generation never mutates tasks; approval uses the existing atomic/undo path. */
class DailyReviewService(private val items: ItemService,private val sessions: SessionStore,private val clock: Clock) {
    private val mutex=Mutex()
    private val current=MutableStateFlow<DailyReview?>(null)
    val pending=current.asStateFlow()
    private val json=Json
    suspend fun refresh(force: Boolean=false): DailyReview? = mutex.withLock {
        val now=LocalDateTime.now(clock)
        val day=if(now.toLocalTime()>=LocalTime.of(23,0)) now.toLocalDate() else now.toLocalDate().minusDays(1)
        val old=sessions.read("daily_review")?.let { runCatching { json.decodeFromString<DailyReview>(it) }.getOrNull() }
        val rows=items.store.all()
        if(!force && old!=null && old.day==day.toString() && old.expiresAt>clock.millis() && old.moves.all { m -> rows.any { it.id==m.id && it.version==m.version && it.status==ItemStatus.OPEN } }) {
            current.value=old; return@withLock old
        }
        sessions.remove("daily_review"); current.value=null
        if(!force && sessions.read("review_done:${day}")!=null) return@withLock null
        val review=build(day,rows)
        if(review!=null) { sessions.write("daily_review",json.encodeToString(review)); current.value=review }
        review
    }
    suspend fun dismiss(id: String)=mutex.withLock {
        val p=current.value ?: return@withLock
        if(p.id==id) { sessions.write("review_done:${p.day}","dismissed"); sessions.remove("daily_review"); current.value=null }
    }
    suspend fun approve(id: String,selected: Set<String>): OperationResult = mutex.withLock {
        sessions.read("review_applied:$id")?.let { return@withLock OperationResult("ok","已调整，无需重复执行",changeId=it) }
        val p=current.value ?: return@withLock OperationResult("error","建议已失效，请重新检查")
        if(p.id!=id || clock.millis()>=p.expiresAt) return@withLock OperationResult("error","建议已过期，请重新检查")
        if(selected.isEmpty() || !p.moves.map { it.id }.containsAll(selected)) return@withLock OperationResult("error","请选择要调整的任务")
        val live=items.store.all()
        val chosen=p.moves.filter { it.id in selected }
        val proposed=chosen.mapNotNull { m -> live.firstOrNull { it.id==m.id }?.copy(schedule=m.after,overrides=emptyList()) }
        // New bookings and deselected successors remain occupied until the user approves them.
        for(m in chosen) {
            val item=proposed.firstOrNull { it.id==m.id } ?: continue
            val time=m.after.time ?: continue
            val duration=item.durationMinutes ?: continue
            val start=LocalDate.parse(m.after.date).atTime(LocalTime.parse(time))
            val occupied=booked(live.filter { it.id !in selected || it.schedule?.recurrence!=null }+proposed.filter { it.id!=m.id },start.toLocalDate())
            if(occupied.any { (a,b) -> start<b && start.plusMinutes(duration.toLong())>a })
                return@withLock OperationResult("error","安排有新的占用，或有未选中的关联任务，请重新检查并一起审阅相关安排")
        }
        val result=items.applyBatch(p.moves.filter { it.id in selected }.map { m -> BatchCommand.Update(m.id,m.version,
            ItemPatch(schedule=FieldPatch.Set(m.after),reminder=if(m.occurrence==null) FieldPatch.Set(m.reminder) else FieldPatch.Unchanged),
            if(m.occurrence==null) EditScope.WHOLE_SERIES else EditScope.THIS_OCCURRENCE,m.occurrence) },"daily-review:$id")
        if(result.status in setOf("ok","partial")) {
            sessions.write("review_applied:$id",result.changeId.orEmpty()); sessions.write("review_done:${p.day}","approved")
            sessions.remove("daily_review"); current.value=null
        }
        result
    }
    private fun build(day: LocalDate,rows: List<PlanItem>): DailyReview? {
        val now=LocalDateTime.now(clock)
        val target=maxOf(day.plusDays(1),now.toLocalDate())
        val dayEnd=minOf(day.plusDays(1).atStartOfDay(),now)
        val repeats=rows.filter { it.kind==ItemKind.TASK && it.status==ItemStatus.OPEN && it.schedule?.recurrence!=null }.mapNotNull { source ->
            ScheduleEngine.onDate(source,day)?.takeIf { it.status==ItemStatus.OPEN }?.let { view -> view.copy(schedule=view.schedule?.copy(date=day.toString(),recurrence=null)) }
        }.filter { it.schedule?.time?.let { time -> day.atTime(LocalTime.parse(time)).plusMinutes((it.durationMinutes ?: 0).toLong())<=dayEnd } ?: true }
        val candidates=rows.filter { item ->
            item.kind==ItemKind.TASK && item.status==ItemStatus.OPEN && item.schedule?.recurrence==null &&
                (item.schedule?.date?.let { LocalDate.parse(it).atTime(item.schedule.time?.let(LocalTime::parse) ?: LocalTime.MIN).plusMinutes((item.durationMinutes ?: 0).toLong())<=dayEnd } == true || item.schedule?.rangeEnd?.let { LocalDate.parse(it)<=day }==true || item.schedule?.date==null && item.schedule?.rangeEnd==null && item.deadline?.let { LocalDate.parse(it.date).atTime(it.time?.let(LocalTime::parse) ?: LocalTime.MAX)<=dayEnd }==true)
        }+repeats
        if(candidates.isEmpty()) return null
        val notes=mutableListOf<String>()
        val affected=candidates.map { it.id }.toMutableSet()
        // Include unfinished successors so an approved plan can preserve the dependency graph.
        var changed=true
        while(changed) { changed=false; rows.filter { it.status==ItemStatus.OPEN && it.kind==ItemKind.TASK && it.schedule?.recurrence==null && it.dependsOn.any(affected::contains) && it.id !in affected }.forEach { affected+=it.id; changed=true } }
        if(affected.size>50) { notes+="关联事项超过 50 项，请分批调整。"; return null }
        val snapshot=rows.associateBy { it.id }.toMutableMap()
        val moves=mutableListOf<ReviewMove>()
        val views=rows.associateBy { it.id }+repeats.associateBy { it.id }
        val sorted=DependencyPlanner.ordered(views.values.filter { it.id in affected })
        val windowEnd=LocalTime.of(22,0)
        sorted.forEach { original ->
            // Successors that are already after their new prerequisite do not need to move.
            val priorEnds=original.dependsOn.mapNotNull(snapshot::get).mapNotNull { p -> p.schedule?.date?.let { LocalDate.parse(it).atTime(p.schedule.time?.let(LocalTime::parse) ?: LocalTime.MIN).plusMinutes((p.durationMinutes ?: 0).toLong()) } }
            val earliest=maxOf(target.atStartOfDay(),priorEnds.maxOrNull() ?: target.atStartOfDay())
            val originalStart=original.schedule?.date?.let { LocalDate.parse(it).atTime(original.schedule.time?.let(LocalTime::parse) ?: LocalTime.MIN) }
            if(original.id !in candidates.map { it.id } && originalStart!=null && originalStart>=earliest) return@forEach
            var next: ScheduleSpec
            val time=original.schedule?.time
            if(time!=null && original.durationMinutes!=null) {
                var start=maxOf(earliest.toLocalDate().atTime(LocalTime.parse(time)),earliest,now.plusMinutes((original.reminder?.offsetMinutes ?: 0).toLong()+1))
                var found=false
                repeat(14) {
                    if(found) return@repeat
                    start=maxOf(start,start.toLocalDate().atTime(LocalTime.of(8,0)))
                    val movedRows=moves.map { m -> snapshot.getValue(m.id) }
                    val busy=booked(rows.filter { it.id !in affected || it.schedule?.recurrence!=null }+movedRows,start.toLocalDate())
                    for((a,b) in busy) if(start < b && start.plusMinutes(original.durationMinutes.toLong()) > a) start=b
                    if(start.plusMinutes(original.durationMinutes.toLong())<=start.toLocalDate().atTime(windowEnd)) found=true
                    else start=start.toLocalDate().plusDays(1).atTime(LocalTime.of(8,0))
                }
                if(found) next=ScheduleSpec(date=start.toLocalDate().toString(),time=start.toLocalTime().toString())
                else { next=ScheduleSpec(date=start.toLocalDate().toString()); notes+="“${original.title}”暂未找到足够的整段空闲，先建议日期，请补选时间。" }
            } else {
                // Unknown duration is never used to claim a conflict-free precise slot.
                next=ScheduleSpec(date=earliest.toLocalDate().toString(),period=original.schedule?.period)
                if(time!=null) notes+="“${original.title}”缺少时长，建议日期，具体时间待确认。"
            }
            var reminder=original.reminder
            if(reminder?.atLocal!=null && originalStart!=null) {
                val newStart=LocalDate.parse(next.date).atTime(next.time?.let(LocalTime::parse) ?: originalStart.toLocalTime())
                reminder=reminder.copy(atLocal=LocalDateTime.parse(reminder.atLocal).plus(Duration.between(originalStart,newStart)).toString())
            }
            if(next.time==null && reminder?.offsetMinutes!=null) { notes+="“${original.title}”的到点通知需要补选时间，本方案先保留为待办。"; reminder=null }
            val late=original.deadline?.let { d -> LocalDate.parse(next.date)>LocalDate.parse(d.date) || next.time!=null && d.time!=null && LocalDate.parse(next.date).atTime(LocalTime.parse(next.time)).plusMinutes((original.durationMinutes ?: 0).toLong())>LocalDate.parse(d.date).atTime(LocalTime.parse(d.time)) } ?: false
            val reason=if(original.id in candidates.map { it.id }) "超期未完成" else "跟随前置任务调整"
            val recurringSource=rows.first { it.id==original.id }.takeIf { it.schedule?.recurrence!=null }
            if(recurringSource!=null && next.time==null && original.reminder?.offsetMinutes!=null) {
                notes+="“${original.title}”是有通知的重复任务，缺少时长，需单独确认本次补做时间。"; return@forEach
            }
            val occurrence=recurringSource?.let { source -> source.overrides.firstOrNull { it.schedule?.date==day.toString() }?.originalDate ?: day.toString() }
            moves+=ReviewMove(original.id,original.version,original.title,original.schedule,next,reminder,reason+(if(occurrence!=null) "；仅本次，保留系列" else "")+if(late) "；超出原截止要求，截止日期保留" else "",occurrence)
            snapshot[original.id]=original.copy(schedule=next,reminder=reminder)
        }
        if(moves.isEmpty()) return null
        return DailyReview(UUID.randomUUID().toString(),day.toString(),moves,notes,clock.millis()+36*60*60_000L)
    }
    private fun booked(rows: List<PlanItem>,date: LocalDate): List<Pair<LocalDateTime,LocalDateTime>> = rows.filter { it.status==ItemStatus.OPEN && it.kind!=ItemKind.ALARM }.flatMap { source ->
        listOf(date.minusDays(1),date).mapNotNull { d -> ScheduleEngine.onDate(source,d)?.let { item ->
            item.schedule?.time?.let { t -> val start=d.atTime(LocalTime.parse(t)); val end=start.plusMinutes((item.durationMinutes ?: 1).toLong()); (start to end).takeIf { end>date.atStartOfDay() } }
        } }
    }.sortedBy { it.first }
}
