package io.github.ming.qingban.data

import androidx.room.Room
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class StorageBackupTest {
    private lateinit var local:LocalStores
    @Before fun setup() { val context=RuntimeEnvironment.getApplication(); local=LocalStores(context,Room.inMemoryDatabaseBuilder(context,QingbanDatabase::class.java).allowMainThreadQueries().build()) }
    @After fun cleanup() { local.database.close() }
    private fun item(id:String="a")=PlanItem(id,kind=ItemKind.TASK,title=id)
    @Test fun transactionConflictDoesNotWriteHalfABatch()=runBlocking {
        val a=item(); local.items.commit(null,a,"first",0)
        val saved=local.items.commitBatch(listOf(a.copy(version=9) to a.copy(version=10),null to item("b")),"batch",1)
        assertNull(saved); assertEquals(listOf("a"),local.items.all().map { it.id }); assertEquals(1,local.items.get("a")!!.version)
    }
    @Test fun batchRequestIsIdempotentInRealRoom()=runBlocking {
        val entries=listOf(null to item(),null to item("b")); val first=local.items.commitBatch(entries,"batch",0)
        val second=local.items.commitBatch(entries,"batch",1); assertEquals(first!!.id,second!!.id); assertEquals(2,local.items.all().size)
    }
    @Test fun invalidArchiveLeavesDatabaseUntouched()=runBlocking {
        local.items.commit(null,item("local"),"first",0)
        val raw=BackupCodec.encode(BackupArchive(items=listOf(item(),item()),knowledge=emptyList(),preferences=emptyList()))
        assertTrue(runCatching { BackupManager(local).restore(raw) }.isFailure); assertEquals("local",local.items.all().single().id)
    }
    @Test fun mergeKeepsExistingValuesAndResetsImportedDelivery()=runBlocking {
        local.items.commit(null,item().copy(title="本机"),"first",0)
        val raw=BackupCodec.encode(BackupArchive(items=listOf(item().copy(title="旧备份"),item("b").copy(delivery=DeliveryState.SCHEDULED)),knowledge=emptyList(),preferences=emptyList()))
        BackupManager(local).restore(raw); assertEquals("本机",local.items.get("a")!!.title); assertEquals(DeliveryState.NONE,local.items.get("b")!!.delivery)
    }
    @Test fun missingBinaryOriginalIsNotAdvertisedAfterJsonRestore()=runBlocking {
        val source=KnowledgeSource("s","文档","正文",0,attachment=KnowledgeAttachment("12345678-1234-1234-1234-123456789abc","文档.pdf","application/pdf",10))
        BackupManager(local).restore(BackupCodec.encode(BackupArchive(items=emptyList(),knowledge=listOf(source),preferences=emptyList())))
        assertNull(local.knowledge.all().single().attachment)
    }
    @Test fun sourceOriginalIdentityCanBeRemapped()=runBlocking {
        val old="12345678-1234-1234-1234-123456789abc"; val new="22345678-1234-1234-1234-123456789abc"
        val source=KnowledgeSource("s","文档","正文",0,attachment=KnowledgeAttachment(old,"文档.pdf","application/pdf",10))
        BackupManager(local).restore(BackupCodec.encode(BackupArchive(items=emptyList(),knowledge=listOf(source),preferences=emptyList())),attachmentIds=mapOf(old to new))
        assertEquals(new,local.knowledge.all().single().attachment!!.id)
    }
    @Test fun exportNeverIncludesSecretsOrToolApprovals()=runBlocking {
        local.sessions.write("api_key","should-never-export"); local.sessions.write("current_proposal","approval-secret")
        val raw=BackupManager(local).export(); assertFalse(raw.contains("should-never-export")); assertFalse(raw.contains("approval-secret"))
    }
    @Test fun mergedDependencyConflictRollsBackTheImport()=runBlocking {
        local.items.commit(null,item("a").copy(schedule=ScheduleSpec("2026-10-10")),"first",0)
        val source=KnowledgeSource("s","文档","正文",0)
        val b=item("b").copy(schedule=ScheduleSpec("2026-10-07"),dependsOn=setOf("a"))
        val raw=BackupCodec.encode(BackupArchive(items=listOf(b),knowledge=listOf(source),preferences=emptyList()))
        assertTrue(runCatching { BackupManager(local).restore(raw) }.isFailure); assertNull(local.items.get("b")); assertTrue(local.knowledge.all().isEmpty())
    }
    private val clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-06T02:00:00Z"),java.time.ZoneId.of("Asia/Shanghai"))
    private fun services()=Services(ItemService(local.items,object:ReminderScheduler { override suspend fun reconcile(item:PlanItem)=ScheduleResult(DeliveryState.NONE) },clock,local.sessions),KnowledgeService(local.knowledge,clock),MemoryService(local.memory,local.sessions,clock),local.sessions,clock)
    @Test fun preferenceAndReplayReceiptRollBackTogether()=runBlocking {
        val failing=object:SessionStore by local.sessions {
            override suspend fun write(key:String,value:String) { if(key.startsWith("memory_request:")) error("模拟回执写入失败"); local.sessions.write(key,value) }
        }
        val memory=MemoryService(local.memory,failing,clock)
        assertTrue(runCatching { memory.setPreference("作息","早起","用户声明",requestKey="write-1") }.isFailure)
        assertTrue(local.memory.listPreferences().isEmpty()); assertNull(local.sessions.read("last_change_id")); assertNull(memory.changeForRequest("write-1"))
    }
    @Test fun replayedPreferenceCannotOverwriteLaterEdit()=runBlocking {
        val memory=services().memory
        val first=memory.setPreference("时段","morning","用户声明",requestKey="write-1")
        memory.setPreference("时段","evening","用户更正")
        val replayed=MemoryService(local.memory,local.sessions,clock).setPreference("时段","morning","用户声明",requestKey="write-1")
        assertEquals(first.id,replayed.id); assertEquals("evening",local.memory.listPreferences().single().value)
    }
    @Test fun replayedItemUndoIgnoresNewMemoryOperation()=runBlocking {
        val service=services(); service.items.create(ItemDraft(ItemKind.TASK,"买牛奶"))
        val first=service.undo(requestKey="undo-1"); service.memory.setPreference("时段","evening","用户声明")
        val replayed=services().undo(requestKey="undo-1")
        assertEquals(first.changeId,replayed.changeId); assertEquals(ItemStatus.CANCELLED,local.items.all().single().status); assertEquals("evening",local.memory.listPreferences().single().value)
    }
    @Test fun replayedMemoryUndoIgnoresNewItemOperation()=runBlocking {
        val service=services(); service.memory.setPreference("时段","evening","用户声明")
        val first=service.undo(requestKey="undo-1"); service.items.create(ItemDraft(ItemKind.TASK,"买牛奶"))
        val replayed=services().undo(requestKey="undo-1")
        assertEquals(first.changeId,replayed.changeId); assertTrue(local.memory.listPreferences().isEmpty()); assertEquals(ItemStatus.OPEN,local.items.all().single().status)
    }
}
