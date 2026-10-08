package io.github.ming.qingban.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CopyBehaviorTest {
    @Test fun greetingUsesPhoneLocalTimeIncludingMidnight() {
        val zone=ZoneId.of("Asia/Shanghai")
        val before=CopyTime.now(Clock.fixed(Instant.parse("2026-10-06T15:59:00Z"),zone))
        val after=CopyTime.now(Clock.fixed(Instant.parse("2026-10-06T16:00:00Z"),zone))
        assertEquals(CopyPeriod.NIGHT,before.period)
        assertEquals(LocalDate.parse("2026-10-07"),after.date)
        val morning=CopyTime.now(Clock.fixed(Instant.parse("2026-10-06T22:00:00Z"),zone))
        assertEquals(CopyPeriod.MORNING,morning.period)
        assertNotEquals(CopyCatalog.assistantGreeting(before).id,CopyCatalog.assistantGreeting(morning).id)
    }

    @Test fun pendingWorkNeverGetsAllDoneCopy() {
        val time=CopyTime(LocalDate.parse("2026-10-06"),CopyPeriod.EVENING)
        assertNotEquals(CopyCatalog.homeHeadline(time,DayProgress(0,3)).id,CopyCatalog.homeHeadline(time,DayProgress(1,3)).id)
        val support=CopyCatalog.dashboardSupport(DayProgress(4,3))
        assertTrue(support.choices.all { "4" in it })
        assertNotEquals(support.id,CopyCatalog.dashboardSupport(DayProgress(0,3)).id)
    }

    @Test fun repeatedEntriesRotateWithoutAdjacentDuplicates() {
        val rotation=CopyRotation(Int.MIN_VALUE)
        val sequence=List(12) { rotation.next("home.headline",4) }
        assertEquals(4,sequence.take(4).toSet().size)
        assertTrue(sequence.zipWithNext().all { (a,b) -> a!=b })
        assertEquals(sequence[0],sequence[4])
        assertEquals(0,rotation.next("single",1))
    }

    @Test fun restoredRotationContinuesEachPositionIndependently() {
        val rotation=CopyRotation(2026)
        rotation.next("home",3); rotation.next("home",3); rotation.next("assistant",4)
        val restored=CopyRotation.restore(rotation.snapshot())
        assertEquals(rotation.next("home",3),restored.next("home",3))
        assertEquals(rotation.next("assistant",4),restored.next("assistant",4))
        assertEquals(rotation.next("newSlot",5),restored.next("newSlot",5))
    }

    @Test fun searchAndSelectedDateKeepEmptyCopyRelevant() {
        val today=LocalDate.parse("2026-10-06")
        val past=CopyCatalog.emptyTitle(1,false,false,today.minusDays(1),today)
        val future=CopyCatalog.emptyTitle(1,false,false,today.plusDays(1),today)
        assertNotEquals(past.id,future.id)
        val search=CopyCatalog.emptyTitle(2,false,true,today,today)
        assertNotEquals(search.id,CopyCatalog.emptyTitle(2,false,false,today,today).id)
        assertTrue(CopyCatalog.emptyHint(true).choices.all { "GPT" !in it })
    }

    @Test fun questionAndReminderContextsRemainDistinct() {
        assertNotEquals(CopyCatalog.assistantInput(AssistantMood.WAITING).id,CopyCatalog.assistantInput(AssistantMood.IDLE).id)
        assertNotEquals(CopyCatalog.ringSupport(false).id,CopyCatalog.ringSupport(true).id)
        assertTrue(CopyCatalog.ringSupport(false).choices.none { Regex("起床|醒来|醒神").containsMatchIn(it) })
        assertNotEquals(CopyCatalog.memoryIntro(false).id,CopyCatalog.memoryIntro(true).id)
    }
}
