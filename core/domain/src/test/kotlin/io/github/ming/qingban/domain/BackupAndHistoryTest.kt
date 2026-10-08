package io.github.ming.qingban.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BackupAndHistoryTest {
    private val item=PlanItem("item",kind=ItemKind.TASK,title="计划",schedule=ScheduleSpec("2026-10-07"))
    private val attachment=KnowledgeAttachment("12345678-1234-1234-1234-123456789abc","课表.pdf","application/pdf",120)
    private val source=KnowledgeSource("source","课表","周三有课",0,attachment=attachment)
    private fun archive()=BackupArchive(items=listOf(item),knowledge=listOf(source),preferences=emptyList())
    private fun invalid(value: BackupArchive) { assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(value)) }.isFailure) }
    @Test fun originalMetadataSurvivesRoundTrip() { assertEquals(archive(),BackupCodec.decode(BackupCodec.encode(archive()))) }
    @Test fun versionTwoWithoutOriginalsRemainsReadable() { val value=archive().copy(schema=2,knowledge=listOf(source.copy(attachment=null))); assertEquals(value,BackupCodec.decode(BackupCodec.encode(value).replace(",\n            \"attachment\": null",""))) }
    @Test fun duplicateIdsAreRejectedBeforeRestore() { invalid(archive().copy(items=listOf(item,item.copy(title="另一个")))) }
    @Test fun duplicatePreferenceIdentityIsRejected() { val p=Preference("时段","晚上",source="用户",confirmedAt=0); invalid(archive().copy(preferences=listOf(p,p))) }
    @Test fun attachmentCannotContainAPath() { invalid(archive().copy(knowledge=listOf(source.copy(attachment=attachment.copy(id="../../outside",fileName="../课表.pdf"))))) }
    @Test fun sourceRangeMustBeOrdered() { val bad=source.copy(validFrom="2026-11-01",validUntil="2026-10-01"); invalid(archive().copy(knowledge=listOf(bad))) }
    @Test fun revisionCannotPretendToBeCurrentVersion() { val bad=source.copy(revisions=listOf(KnowledgeRevision(1,"旧版","正文",0))); invalid(archive().copy(knowledge=listOf(bad))) }
    @Test fun overrideMustBeASingleOccurrence() { val schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-07")); val bad=item.copy(overrides=listOf(OccurrenceOverride("2026-10-07",schedule))); invalid(archive().copy(items=listOf(bad))) }
    @Test fun preferenceSourceRequiresAVersion() { val bad=Preference("时段","晚上",source="资料",confirmedAt=0,sourceId="source"); invalid(archive().copy(preferences=listOf(bad))) }
    @Test fun cancelledOccurrenceIsHiddenFromDailyView() { val repeat=item.copy(schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-01")),overrides=listOf(OccurrenceOverride("2026-10-07",status=ItemStatus.CANCELLED))); assertNull(ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-07"))); assertEquals(ItemStatus.CANCELLED,ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-07"),true)!!.status) }
    @Test fun completedOccurrenceRetainsItsHistory() { val repeat=item.copy(schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-01")),overrides=listOf(OccurrenceOverride("2026-10-07",status=ItemStatus.COMPLETED))); assertEquals(ItemStatus.COMPLETED,ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-07"))!!.status); assertEquals(ItemStatus.OPEN,ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-14"))!!.status) }
    @Test fun movedOccurrenceDoesNotShowOnOriginalDate() { val repeat=item.copy(schedule=ScheduleSpec(recurrence=RecurrenceSpec(weekdays=setOf(3),startDate="2026-10-01")),overrides=listOf(OccurrenceOverride("2026-10-07",ScheduleSpec("2026-10-08","09:00")))); assertNull(ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-07"))); assertEquals("09:00",ScheduleEngine.onDate(repeat,LocalDate.parse("2026-10-08"))!!.schedule!!.time) }
    @Test fun longDependencyChainDoesNotOverflowTheStack() { val list=(0..12_000).map { n -> item.copy(id="$n",dependsOn=if(n==0) emptySet() else setOf("${n-1}")) }; assertNull(DependencyPlanner.validate(list.associateBy { it.id })); assertEquals(list.map { it.id },DependencyPlanner.ordered(list.reversed()).map { it.id }) }
    @Test fun longDependencyCycleIsRejected() { val list=(0..4000).map { n -> item.copy(id="$n",dependsOn=setOf(if(n==0) "4000" else "${n-1}")) }; assertNotNull(DependencyPlanner.validate(list.associateBy { it.id })) }
}
