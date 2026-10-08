package io.github.ming.qingban.domain

import java.time.LocalDate
import java.time.LocalTime

data class CalendarBlock(val item: PlanItem, val start: Int, val end: Int, val lane: Int = 0, val lanes: Int = 1, val continuation: Boolean = false)
object CalendarLayout {
    fun blocks(items: List<PlanItem>, day: LocalDate): List<CalendarBlock> {
        val blocks=mutableListOf<CalendarBlock>()
        items.forEach { original ->
            ScheduleEngine.onDate(original,day)?.let { item -> item.schedule?.time?.let { time ->
                val t=LocalTime.parse(time); val start=t.hour*60+t.minute
                blocks+=CalendarBlock(item,start,minOf(1440,start+(item.durationMinutes ?: 30)))
            } }
            ScheduleEngine.onDate(original,day.minusDays(1))?.let { item -> item.schedule?.time?.let { time ->
                val t=LocalTime.parse(time); val end=t.hour*60+t.minute+(item.durationMinutes ?: 30)-1440
                if(end>0) blocks+=CalendarBlock(item,0,end,continuation=true)
            } }
        }
        val groups=mutableListOf<MutableList<CalendarBlock>>()
        for(block in blocks.sortedWith(compareBy<CalendarBlock> { it.start }.thenBy { it.end }.thenBy { it.item.id })) {
            val group=groups.lastOrNull()
            if(group==null || block.start >= group.maxOf { it.end }) groups+=mutableListOf(block) else group+=block
        }
        return groups.flatMap { group ->
            val ends=mutableListOf<Int>()
            val assigned=group.map { block ->
                val available=ends.indexOfFirst { it<=block.start }
                val lane=if(available<0) { ends+=block.end; ends.lastIndex } else { ends[available]=block.end; available }
                block.copy(lane=lane)
            }
            assigned.map { it.copy(lanes=ends.size) }
        }
    }
}
