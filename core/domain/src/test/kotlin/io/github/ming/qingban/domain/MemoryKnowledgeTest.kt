package io.github.ming.qingban.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

private class SessionFake : SessionStore {
    val rows = mutableMapOf<String,String>()
    override suspend fun read(key: String) = rows[key]
    override suspend fun write(key: String, value: String) { rows[key] = value }
    override suspend fun remove(key: String) { rows.remove(key) }
}
private class MemoryFake : MemoryStore {
    val rows = mutableMapOf<Pair<String,String>, Preference>()
    val events = mutableListOf<ChoiceObservation>()
    override fun preferences() = MutableStateFlow(rows.values.toList())
    override suspend fun listPreferences() = rows.values.toList()
    override suspend fun save(preference: Preference) { rows[preference.key to preference.scope] = preference }
    override suspend fun delete(key: String, scope: String) { rows.remove(key to scope) }
    override suspend fun observations() = events.toList()
    override suspend fun observeChoice(observation: ChoiceObservation) { events += observation }
    override suspend fun clearObservations() { events.clear() }
}
private class KnowledgeFake : KnowledgeStore {
    val rows = mutableMapOf<String,KnowledgeSource>()
    override fun observe() = MutableStateFlow(rows.values.toList())
    override suspend fun all() = rows.values.toList()
    override suspend fun save(source: KnowledgeSource) { rows[source.id] = source }
    override suspend fun delete(id: String) { rows.remove(id) }
}
class MemoryKnowledgeTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
    @Test fun remoteSearchExcludesLocalOnlySources() = runBlocking { val store = KnowledgeFake(); val service = KnowledgeService(store, clock); service.importText("私有课表", "周四下午数据结构"); assertTrue(service.search("数据结构", true).isEmpty()); assertEquals(1, service.search("数据结构", false).size) }
    @Test fun expiryAndDeletionInvalidateRetrieval() = runBlocking { val store = KnowledgeFake(); val service = KnowledgeService(store, clock); val source = service.importText("旧课表", "周四下午数据结构", true); store.save(source.copy(validUntil = "2026-10-01")); assertTrue(service.search("数据结构", true).isEmpty()); assertEquals(1, service.search("数据结构", true, true).size); service.delete(source.id); assertTrue(service.search("数据结构", true, true).isEmpty()) }
    @Test fun learningOffDoesNotRecordActions() = runBlocking { val store = MemoryFake(); val service = MemoryService(store, SessionFake(), clock); service.recordChoice(PlanItem("a", kind = ItemKind.TASK, title = "运动", schedule = ScheduleSpec(date = "2026-10-07", period = "evening"))); assertTrue(store.events.isEmpty()) }
    @Test fun repeatedClicksOneDayAreInsufficient() = runBlocking { val store = MemoryFake(); val service = MemoryService(store, SessionFake(), clock); service.setLearning(true); repeat(5) { store.events += ChoiceObservation("$it", "运动", "evening", "2026-10-06") }; assertTrue(service.candidates().isEmpty()) }
    @Test fun programSuggestionsAreNotEvidence() = runBlocking { val store = MemoryFake(); val service = MemoryService(store, SessionFake(), clock); service.setLearning(true); (1..4).forEach { store.events += ChoiceObservation("$it", "运动", "evening", "2026-10-0$it", "program") }; assertTrue(service.candidates().isEmpty()) }
    @Test fun patternsNeedConsentAndRespectCooldown() = runBlocking { val store = MemoryFake(); val service = MemoryService(store, SessionFake(), clock); service.setLearning(true); (3..5).forEach { store.events += ChoiceObservation("$it", "运动", "evening", "2026-10-0$it") }; val candidate = service.candidates().single(); assertTrue(service.activePreferences().isEmpty()); service.dismiss(candidate); assertTrue(service.candidates().isEmpty()); service.accept(candidate); assertEquals("evening", service.activePreferences().single().value) }
    @Test fun correctedPreferenceReplacesValueAndCanUndo() = runBlocking { val store = MemoryFake(); val service = MemoryService(store, SessionFake(), clock); service.setPreference("时段", "evening", "用户声明", "运动"); val changed = service.setPreference("时段", "morning", "用户更正", "运动"); assertEquals(1, store.rows.size); assertEquals("ok", service.undo(changed.id).status); assertEquals("evening", service.activePreferences().single().value) }
    @Test fun undoCannotOverwriteLaterPreference() = runBlocking { val service = MemoryService(MemoryFake(), SessionFake(), clock); val old = service.setPreference("作息", "morning", "声明"); service.setPreference("作息", "evening", "更正"); assertEquals("version_conflict", service.undo(old.id).errorCode) }
    @Test fun silentAlarmIsRejected() { assertNotNull(ScheduleEngine.validate(ItemDraft(ItemKind.ALARM, "起床", schedule = ScheduleSpec(date = "2026-10-07", time = "08:00"), alarm = AlarmSpec(vibrate = false, playAudio = false)))) }
    @Test fun sourceUpdateKeepsHistoryAndUsesNewText()=runBlocking { val store=KnowledgeFake(); val k=KnowledgeService(store,clock); val old=k.importText("课表","周二数学",true); val new=k.update(old.id,"课表","周二英语",true,null,null,"学校","已确认"); assertEquals(2,new.version); assertEquals("周二数学",new.revisions.single().text); assertTrue(k.search("数学",true).isEmpty()); assertEquals(2,k.search("英语",true).single().version) }
    @Test fun linkedMemoryStopsWhenSourceVersionChanges()=runBlocking { val sources=KnowledgeFake(); val memory=MemoryFake(); val sessions=SessionFake(); val k=KnowledgeService(sources,clock,memory); val s=MemoryService(memory,sessions,clock,sources); val source=k.importText("课表","周二数学"); s.setPreference("课表","数学","资料确认",sourceId=source.id,sourceVersion=1); assertEquals(1,s.activePreferences().size); k.update(source.id,"课表","周二英语",true,null,null,"学校","已确认"); assertTrue(s.activePreferences().isEmpty()) }
    @Test fun deletionMarksOrRemovesLinkedMemory()=runBlocking { val sources=KnowledgeFake(); val memory=MemoryFake(); val k=KnowledgeService(sources,clock,memory); val s=MemoryService(memory,SessionFake(),clock,sources); val source=k.importText("课表","数学"); s.setPreference("课表","数学","确认",sourceId=source.id,sourceVersion=1); k.delete(source.id); assertTrue(memory.rows.values.single().sourceDeleted); assertTrue(s.activePreferences().isEmpty()) }
    @Test fun futureSourcesAreNotCurrentlyApplicable()=runBlocking { val store=KnowledgeFake(); val k=KnowledgeService(store,clock); val source=k.importText("课表","数学",true); k.update(source.id,source.title,source.text,true,"2026-11-01",null,"学校","未确认"); assertTrue(k.search("数学",true).isEmpty()); assertEquals(1,k.search("数学",true,true).size) }
    @Test fun dailyHabitPromptDoesNotRepeatOrWriteMemory()=runBlocking { val memory=MemoryFake(); val s=MemoryService(memory,SessionFake(),clock); s.setLearning(true); (3..5).forEach { memory.events+=ChoiceObservation("$it","运动","evening","2026-10-0$it") }; assertNotNull(s.nextProactive()); assertNull(s.nextProactive()); assertTrue(s.activePreferences().isEmpty()) }
    @Test fun learnedPreferencesCanBeStoppedSeparately()=runBlocking { val s=MemoryService(MemoryFake(),SessionFake(),clock); s.setPreference("活动","晚间","用户习惯确认",learned=true); s.setPreference("声明","早上","明确声明"); s.setUseLearned(false); assertEquals(listOf("声明"),s.activePreferences().map { it.key }) }
    @Test fun vectorRankingHandlesEmptyInput() { assertEquals(0.0,TextVector.cosine(TextVector.of(""),TextVector.of("数学")),0.0); assertTrue(TextVector.cosine(TextVector.of("数据结构"),TextVector.of("数据结构作业"))>0.5) }
    @Test fun derivedPreferenceHonorsSourceRemoteConsent()=runBlocking { val sources=KnowledgeFake(); val k=KnowledgeService(sources,clock); val source=k.importText("私有课表","数学"); val s=MemoryService(MemoryFake(),SessionFake(),clock,sources); s.setPreference("课表","数学","资料确认",sourceId=source.id,sourceVersion=1); assertEquals(1,s.activePreferences().size); assertTrue(s.activePreferences(remote=true).isEmpty()); k.allowRemote(source.id,true); assertEquals(1,s.activePreferences(remote=true).size) }
}
