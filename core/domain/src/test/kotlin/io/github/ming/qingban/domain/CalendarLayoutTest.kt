package io.github.ming.qingban.domain

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class CalendarLayoutTest {
    private val day=LocalDate.parse("2026-10-07")
    private fun task(id: String,time: String,duration: Int?=60)=PlanItem(id,kind=ItemKind.TASK,title=id,schedule=ScheduleSpec(day.toString(),time),durationMinutes=duration)
    @Test fun overlappingBlocksGetSeparateLanes() { val blocks=CalendarLayout.blocks(listOf(task("a","09:00"),task("b","09:30"),task("c","09:45")),day); assertEquals(3,blocks.map { it.lane }.distinct().size); assertTrue(blocks.all { it.lanes==3 }) }
    @Test fun adjacentBlocksCanReuseFullWidth() { val blocks=CalendarLayout.blocks(listOf(task("a","09:00"),task("b","10:00")),day); assertTrue(blocks.all { it.lane==0 && it.lanes==1 }) }
    @Test fun crossMidnightIsSplitIntoTwoDates() { val item=task("a","23:30",90); assertEquals(1440,CalendarLayout.blocks(listOf(item),day).single().end); val tail=CalendarLayout.blocks(listOf(item),day.plusDays(1)).single(); assertEquals(0,tail.start); assertEquals(60,tail.end); assertTrue(tail.continuation) }
    @Test fun recurringMovedOccurrenceUsesNewTimeAndDate() { val item=task("a","09:00").copy(schedule=ScheduleSpec(time="09:00",recurrence=RecurrenceSpec(weekdays=setOf(3),startDate=day.toString())),overrides=listOf(OccurrenceOverride(day.toString(),ScheduleSpec(day.plusDays(1).toString(),"14:00")))); assertTrue(CalendarLayout.blocks(listOf(item),day).isEmpty()); assertEquals(840,CalendarLayout.blocks(listOf(item),day.plusDays(1)).single().start) }
    @Test fun completedOccurrenceIsVisibleButCancelledIsHidden() { val a=task("a","09:00").copy(status=ItemStatus.COMPLETED); val b=task("b","09:00").copy(status=ItemStatus.CANCELLED); assertEquals(listOf("a"),CalendarLayout.blocks(listOf(a,b),day).map { it.item.id }) }
    @Test fun unknownDurationIsOnlyAVisualDefault() { val item=task("a","09:00",null); val block=CalendarLayout.blocks(listOf(item),day).single(); assertEquals(30,block.end-block.start); assertNull(block.item.durationMinutes) }
    @Test fun allDayIsExcludedFromTimedGrid() { assertTrue(CalendarLayout.blocks(listOf(task("a","09:00").copy(schedule=ScheduleSpec(day.toString()))),day).isEmpty()) }
}
