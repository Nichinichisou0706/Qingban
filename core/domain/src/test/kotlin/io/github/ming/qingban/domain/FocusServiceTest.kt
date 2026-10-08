package io.github.ming.qingban.domain

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import java.time.*

class TimerClock: Clock() {
    var now=Instant.parse("2026-10-07T01:00:00Z").toEpochMilli()
    override fun getZone()=ZoneId.of("Asia/Shanghai")
    override fun withZone(zone: ZoneId)=this
    override fun instant()=Instant.ofEpochMilli(now)
    fun advance(seconds: Long) { now+=seconds*1000 }
}
class TimerStore: SessionStore {
    val values=mutableMapOf<String,String>()
    override suspend fun read(key: String)=values[key]
    override suspend fun write(key: String,value: String) { values[key]=value }
    override suspend fun remove(key: String) { values.remove(key) }
}
class FocusServiceTest {
    private val clock=TimerClock(); private val store=TimerStore()
    private var notified=0
    private val scheduler=object: FocusScheduler { override suspend fun completed(run: FocusRun) { notified++ } }
    private fun service()=FocusService(store,clock,scheduler)
    @Test fun pauseResumePreservesTimeAcrossAProcessRestart()=runBlocking {
        val s=service(); s.command("start",minutes=1); clock.advance(20); s.command("pause"); clock.advance(300)
        val restored=service(); assertEquals(20_000L,restored.load().run!!.elapsed(clock.millis())); restored.command("resume"); clock.advance(39); assertEquals("running",restored.tick().run!!.status); clock.advance(1); assertEquals("finished",restored.tick().run!!.status)
    }
    @Test fun overdueRecoveryRecordsExactlyOnceAtTheOriginalDeadline()=runBlocking {
        val s=service(); val start=s.command("start",minutes=1).run!!.startedAt!!; clock.advance(600)
        val restored=service(); assertEquals(start+60_000,restored.load().records.single().endedAt); restored.load(); service().load(); assertEquals(1,notified)
    }
    @Test fun completedTaskLinkSurvivesRestartButDoesNotChangeAnyTask()=runBlocking { service().command("start",minutes=1,taskId="real-task",title="数学"); clock.advance(60); assertEquals("real-task",service().load().records.single().taskId) }
    @Test fun startingAnotherTimerCannotOverwritePausedOne()=runBlocking { val s=service(); val id=s.command("start").run!!.id; s.command("pause"); assertTrue(runCatching { s.command("start") }.isFailure); assertEquals(id,s.state.value.run!!.id) }
    @Test fun partialTomatoDoesNotCountAsCompleted()=runBlocking { val s=service(); s.command("start"); clock.advance(20); s.command("finish"); assertEquals(20_000L,s.state.value.records.single().millis); assertFalse(s.state.value.records.single().completed); assertTrue(runCatching { s.command("rest") }.isFailure) }
    @Test fun stopwatchHasNoDeadlineAndSavesActualTime()=runBlocking { val s=service(); s.command("start",mode="stopwatch"); clock.advance(8000); assertNull(s.tick().run!!.endAt()); val record=s.command("finish").records.single(); assertEquals(8_000_000L,record.millis); assertEquals("stopwatch",record.mode) }
    @Test fun fourthTomatoUsesLongRest()=runBlocking { val s=service(); repeat(4) { s.command("start",minutes=1); clock.advance(60); s.tick() }; val r=s.command("rest").run!!; assertEquals("rest",r.phase); assertEquals(15*60_000L,r.targetMillis) }
    @Test fun restCompletionDoesNotAddAnotherTomato()=runBlocking { val s=service(); s.command("start",minutes=1); clock.advance(60); s.command("rest"); clock.advance(5*60); assertEquals(1,s.tick().records.size) }
    @Test fun settingsApplyToNextRunWithoutChangingCurrentOne()=runBlocking { val s=service(); s.command("start"); s.command("settings",settings=FocusSettings(focusMinutes=45)); assertEquals(25*60_000L,s.state.value.run!!.targetMillis); s.command("reset"); assertEquals(45*60_000L,s.command("start").run!!.targetMillis) }
    @Test fun disabledNotificationDoesNotNotifyAfterRecovery()=runBlocking { val s=service(); s.command("settings",settings=FocusSettings(notify=false)); s.command("start",minutes=1); clock.advance(60); service().load(); assertEquals(0,notified) }
    @Test fun requestRetryDoesNotCreateSecondTimer()=runBlocking { val s=service(); val id=s.command("start",requestKey="same").run!!.id; assertEquals(id,service().command("start",requestKey="same").run!!.id) }
    @Test fun resetPreservesRecords()=runBlocking { val s=service(); s.command("start",minutes=1); clock.advance(60); s.tick(); assertNull(s.command("reset").run); assertEquals(1,s.state.value.records.size) }
    @Test fun invalidSettingsDoNotChangeStoredConfiguration()=runBlocking { val s=service(); assertTrue(runCatching { s.command("settings",settings=FocusSettings(restMinutes=0)) }.isFailure); assertEquals(5,service().load().settings.restMinutes) }
    @Test fun focusArchiveRoundTripsAndRejectsDuplicateRecords() {
        val record=FocusRecord("r",null,"专注",1,60_000,"pomodoro")
        val archive=BackupArchive(items=emptyList(),knowledge=emptyList(),preferences=emptyList(),focus=FocusArchive(records=listOf(record)))
        assertEquals(archive,BackupCodec.decode(BackupCodec.encode(archive)))
        assertTrue(runCatching { BackupCodec.validate(archive.copy(focus=FocusArchive(records=listOf(record,record)))) }.isFailure)
    }
    @Test fun movingSystemClockDoesNotEndCountdownOnSameBoot()=runBlocking {
        var uptime=1000L
        val monotonic=object: ElapsedClock { override fun millis()=uptime; override fun bootId()="same-boot" }
        val s=FocusService(store,clock,scheduler,monotonic); s.command("start",minutes=1)
        clock.advance(3600); uptime+=30_000; assertEquals("running",s.tick().run!!.status); assertEquals(30_000L,s.elapsed(s.state.value.run!!))
        uptime+=30_000; assertEquals("finished",s.tick().run!!.status)
    }
    @Test fun rebootFallsBackToStoredWallDeadline()=runBlocking {
        var boot="old"; var uptime=1000L
        val monotonic=object: ElapsedClock { override fun millis()=uptime; override fun bootId()=boot }
        FocusService(store,clock,scheduler,monotonic).command("start",minutes=1); clock.advance(120); uptime=20; boot="new"
        val restored=FocusService(store,clock,scheduler,monotonic); assertEquals("finished",restored.load().run!!.status); assertEquals(1,restored.state.value.records.size)
    }
}
