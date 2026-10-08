package io.github.ming.qingban

import android.content.Context
import android.net.Uri
import androidx.room.Room
import io.github.ming.qingban.data.*
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class,manifest=Config.NONE)
class PortableBackupTest {
    private lateinit var context:Context; private lateinit var local:LocalStores
    @Before fun setup() { context=RuntimeEnvironment.getApplication(); local=LocalStores(context,Room.inMemoryDatabaseBuilder(context,QingbanDatabase::class.java).allowMainThreadQueries().build()) }
    @After fun cleanup() { local.database.close() }
    private fun portable()=PortableBackup(context,local,BackupManager(local))
    private fun archive(entries:Map<String,ByteArray>):Uri {
        val file=File.createTempFile("test-archive-",".zip",context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return Uri.fromFile(file)
    }
    @Test fun unknownPathsAreRejectedWithoutDatabaseWrites()=runBlocking {
        val uri=archive(mapOf("../outside.txt" to "bad".toByteArray())); assertTrue(runCatching { portable().restore(uri) }.isFailure); assertTrue(local.items.all().isEmpty())
    }
    @Test fun binaryOriginalIsRestoredWithANewIdentity()=runBlocking {
        val id="12345678-1234-1234-1234-123456789abc"; val bytes="original".toByteArray()
        val source=KnowledgeSource("s","文档","正文",0,attachment=KnowledgeAttachment(id,"文档.txt","text/plain",bytes.size.toLong()))
        val raw=BackupCodec.encode(BackupArchive(items=emptyList(),knowledge=listOf(source),preferences=emptyList()))
        portable().restore(archive(mapOf("backup.json" to raw.toByteArray(),"documents/$id.blob" to bytes)))
        val restored=local.knowledge.all().single().attachment!!; assertNotEquals(id,restored.id); assertArrayEquals(bytes,File(context.filesDir,"documents/${restored.id}.blob").readBytes())
    }
    @Test fun originalSizeMismatchDoesNotImportSource()=runBlocking {
        val id="12345678-1234-1234-1234-123456789abc"; val source=KnowledgeSource("s","文档","正文",0,attachment=KnowledgeAttachment(id,"文档.txt","text/plain",90))
        val raw=BackupCodec.encode(BackupArchive(items=emptyList(),knowledge=listOf(source),preferences=emptyList()))
        assertTrue(runCatching { portable().restore(archive(mapOf("backup.json" to raw.toByteArray(),"documents/$id.blob" to "short".toByteArray()))) }.isFailure)
        assertTrue(local.knowledge.all().isEmpty())
    }
    @Test fun exportContainsOriginalsButExcludesApprovals()=runBlocking {
        val id="12345678-1234-1234-1234-123456789abc"; val file=File(context.filesDir,"documents/$id.blob"); file.parentFile!!.mkdirs(); file.writeText("original")
        local.knowledge.save(KnowledgeSource("s","文档","正文",0,attachment=KnowledgeAttachment(id,"文档.txt","text/plain",file.length())))
        local.sessions.write("current_proposal","do-not-export")
        val zip=File.createTempFile("export-",".zip",context.cacheDir); portable().export(Uri.fromFile(zip))
        ZipFile(zip).use { archive -> assertNotNull(archive.getEntry("documents/$id.blob")); assertFalse(archive.getInputStream(archive.getEntry("sessions.json")).reader().readText().contains("do-not-export")) }
    }
    @Test fun timerBackupContainsHistoryWithoutRestartingOldTimer()=runBlocking {
        val record=FocusRecord("focus-record",null,"专注",100,60_000,"pomodoro")
        val archive=BackupArchive(items=emptyList(),knowledge=emptyList(),preferences=emptyList(),focus=FocusArchive(FocusSettings(restMinutes=10),listOf(record)))
        portable().restore(archive(mapOf("backup.json" to BackupCodec.encode(archive).toByteArray())))
        val restored=kotlinx.serialization.json.Json.decodeFromString<FocusState>(local.sessions.read("focus_state")!!)
        assertNull(restored.run); assertEquals(listOf(record),restored.records); assertEquals(10,restored.settings.restMinutes)
    }
    @Test fun importingHistoryKeepsCurrentRunAndDeduplicates()=runBlocking {
        val clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-07T02:00:00Z"),java.time.ZoneId.of("Asia/Shanghai"))
        val focus=FocusService(local.sessions,clock); val id=focus.command("start").run!!.id
        val record=FocusRecord("focus-record",null,"旧专注",100,60_000,"pomodoro")
        val archive=BackupArchive(items=emptyList(),knowledge=emptyList(),preferences=emptyList(),focus=FocusArchive(records=listOf(record)))
        val uri=archive(mapOf("backup.json" to BackupCodec.encode(archive).toByteArray())); portable().restore(uri); portable().restore(uri)
        val restored=focus.reload(); assertEquals(id,restored.run!!.id); assertEquals(1,restored.records.size)
    }
}
