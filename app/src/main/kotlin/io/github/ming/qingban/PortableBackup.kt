package io.github.ming.qingban

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.ming.qingban.data.*
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.*

class PortableBackup(private val context: Context, private val local: LocalStores, private val backup: BackupManager) {
    private fun allowed(key: String) = key in setOf("agent_session","learning_enabled","use_learned_preferences","habit_proactive","habit_last_prompt") || key.startsWith("habit_cooldown:")
    private fun safeSession(raw: String): String {
        val decoded=Json.decodeFromString<io.github.ming.qingban.agent.AgentSession>(raw)
        require(decoded.history.size<=80 && decoded.pausedRequests.size<=10 && decoded.pausedLocal.size<=10 && decoded.interruptedTurns.size<=5 && decoded.history.all { it.role in setOf("user","assistant") && it.text.length<=32_000 }) { "会话备份不正确" }
        val session=Json.parseToJsonElement(raw).jsonObject
        val history=session["history"]?.jsonArray?.map { JsonObject(it.jsonObject+("changeId" to JsonNull)) } ?: emptyList()
        return JsonObject(session+mapOf("proposalId" to JsonNull,"lastItemId" to JsonNull,"activeTurn" to JsonNull,"interruptedTurns" to JsonArray(emptyList()),"history" to JsonArray(history))).toString()
    }
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val sessions = local.database.dao().allSessions().filter { allowed(it.key) }.associate { row -> row.key to if(row.key == "agent_session") safeSession(row.value) else row.value }
        val output = context.contentResolver.openOutputStream(uri) ?: error("无法保存备份")
        ZipOutputStream(output).use { zip ->
            var total=0L
            fun text(name: String, value: String) { val bytes=value.toByteArray(Charsets.UTF_8); total+=bytes.size; require(bytes.size<=16_000_000 && total<=512L*1024*1024) { "备份正文过大" }; zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            text("backup.json",backup.export()); text("sessions.json",Json.encodeToString(sessions)); text("observations.json",Json.encodeToString(local.memory.observations()))
            val directory = File(context.filesDir,"music").canonicalFile
            val songs=directory.listFiles()?.filter { it.isFile && Regex("[a-zA-Z0-9-]+\\.audio").matches(it.name) } ?: emptyList()
            require(songs.size<=295) { "音乐数量过多，请先整理缓存" }
            val catalog=context.getSharedPreferences("music_catalog",Context.MODE_PRIVATE)
            text("music-titles.json",Json.encodeToString(songs.associate { it.name to catalog.getString(it.name,"导入音乐")!! }))
            songs.forEach { file ->
                total+=file.length(); require(file.length()<=128L*1024*1024 && total<=512L*1024*1024) { "完整备份最多 512 MiB" }
                zip.putNextEntry(ZipEntry("music/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            val originals=local.knowledge.all().mapNotNull { it.attachment }.distinctBy { it.id }
            require(originals.size<=2000) { "原文件数量过多" }
            originals.forEach { original ->
                BackupCodec.validateAttachment(original)
                val file=File(context.filesDir,"documents/${original.id}.blob")
                require(file.isFile && file.length()==original.byteCount) { "《${original.fileName}》原文件缺失或已变化，请先处理该资料" }
                total+=file.length(); require(total<=512L*1024*1024) { "完整备份最多 512 MiB" }
                zip.putNextEntry(ZipEntry("documents/${original.id}.blob")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir,"restore-${UUID.randomUUID()}").apply { mkdirs() }
        val staged = mutableMapOf<String,File>(); val committedFiles = mutableListOf<File>()
        try {
            var total = 0L; var entries = 0
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 2300 && !entry.isDirectory) { "备份条目不正确" }
                    require(entry.name in setOf("backup.json","sessions.json","observations.json","music-titles.json") || Regex("music/[a-zA-Z0-9-]+\\.audio").matches(entry.name) || Regex("documents/[a-fA-F0-9-]{36}\\.blob").matches(entry.name)) { "备份包含未知文件" }
                    require(entry.name !in staged) { "备份条目重复" }
                    val file = File(folder,"part-$entries")
                    file.outputStream().use { output ->
                        val block = ByteArray(16384); var size = 0L
                        while(true) { val n = zip.read(block); if(n < 0) break; size += n; total += n; require(size <= (if(entry.name.startsWith("music/")) 128L*1024*1024 else if(entry.name.startsWith("documents/")) 32_000_000 else 16_000_000) && total <= 512L*1024*1024) { "备份过大" }; output.write(block,0,n) }
                    }; staged[entry.name] = file; zip.closeEntry()
                }
            }
            val raw = staged["backup.json"]?.readText() ?: error("备份缺少事项数据")
            val archive=BackupCodec.decode(raw)
            val sessionRows = staged["sessions.json"]?.let { Json.decodeFromString<Map<String,String>>(it.readText()) } ?: emptyMap()
            require(sessionRows.size<=500 && sessionRows.all { allowed(it.key) && it.key.length<=200 && it.value.length <= 2_000_000 }) { "会话数据不正确" }
            sessionRows.forEach { (key,value) -> when { key=="agent_session" -> safeSession(value); key in setOf("learning_enabled","use_learned_preferences","habit_proactive") -> require(value in setOf("true","false")); else -> java.time.LocalDate.parse(value) } }
            val observations = staged["observations.json"]?.let { Json.decodeFromString<List<ChoiceObservation>>(it.readText()) } ?: emptyList()
            require(observations.size <= 100_000 && observations.all { it.period in setOf("morning","afternoon","evening") && it.activity.length <= 240 && runCatching { java.time.LocalDate.parse(it.date) }.isSuccess }) { "习惯观察数据不正确" }
            val items = Json.parseToJsonElement(raw).jsonObject["items"]!!.jsonArray
            val paths = mutableMapOf<String,String>()
            val documentIds=mutableMapOf<String,String>()
            val sourceIds=local.knowledge.all().map { it.id }.toSet()
            val documentFolder=File(context.filesDir,"documents").apply { mkdirs() }
            archive.knowledge.mapNotNull { it.attachment }.distinctBy { it.id }.forEach { original ->
                val stagedFile=staged["documents/${original.id}.blob"] ?: error("备份缺少原文件：${original.fileName}")
                require(stagedFile.length()==original.byteCount) { "原文件大小与备份记录不一致" }
                if(archive.knowledge.any { it.id !in sourceIds && it.attachment?.id==original.id }) {
                    val id=UUID.randomUUID().toString(); val target=File(documentFolder,"$id.blob")
                    committedFiles+=target; stagedFile.copyTo(target); documentIds[original.id]=id
                }
            }
            require(staged.keys.filter { it.startsWith("documents/") }.all { path -> archive.knowledge.any { path=="documents/${it.attachment?.id}.blob" } }) { "备份包含无来源的原文件" }
            val musicFolder = File(context.filesDir,"music").apply { mkdirs() }
            val titles=staged["music-titles.json"]?.let { Json.decodeFromString<Map<String,String>>(it.readText()) } ?: emptyMap()
            require(titles.size<=295 && titles.values.all { it.length<=500 }) { "音乐目录不正确" }
            val byName=staged.filterKeys { it.startsWith("music/") }.map { (name,file) ->
                val target=File(musicFolder,"${UUID.randomUUID()}.audio"); committedFiles+=target; file.copyTo(target); name.substringAfter('/') to target
            }.toMap()
            items.forEach { element ->
                val old = element.jsonObject["alarm"]?.jsonObject?.get("sound_path")?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: return@forEach
                val name = File(old).name
                byName[name]?.let { paths[old]=it.absolutePath }
            }
            local.database.withTransaction {
                backup.restore(raw,paths,documentIds)
                sessionRows.forEach { (key,value) -> if(local.sessions.read(key) == null) local.sessions.write(key, if(key == "agent_session") safeSession(value) else value) }
                observations.forEach { local.memory.observeChoice(it) }
            }
            runCatching { val editor=context.getSharedPreferences("music_catalog",Context.MODE_PRIVATE).edit(); byName.forEach { (name,file) -> editor.putString(file.name,titles[name] ?: "恢复的音乐") }; editor.apply() }
        } catch(e: Exception) { committedFiles.forEach { it.delete() }; throw e }
        finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
}
