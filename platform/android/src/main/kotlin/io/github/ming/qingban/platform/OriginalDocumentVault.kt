package io.github.ming.qingban.platform

import android.content.Context
import android.net.Uri
import io.github.ming.qingban.domain.BackupCodec
import io.github.ming.qingban.domain.KnowledgeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class OriginalDocumentVault(private val context: Context) {
    private val folder get() = File(context.filesDir,"documents").apply { mkdirs() }
    fun file(id: String): File {
        require(Regex("[a-fA-F0-9-]{36}").matches(id) && runCatching { UUID.fromString(id) }.isSuccess)
        return File(folder,"$id.blob")
    }
    suspend fun keep(uri: Uri, title: String): KnowledgeAttachment = withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString(); val destination=file(id)
        try {
            var count=0L
            context.contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output ->
                val block=ByteArray(16384)
                while(true) { val n=input.read(block); if(n<0) break; count+=n; require(count<=32_000_000) { "原文件最多 32 MB" }; output.write(block,0,n) }
            } } ?: error("无法保留原文件")
            val name=title.replace(Regex("[\\\\/\\u0000]"),"_").take(255).ifBlank { "导入资料" }
            val mime=context.contentResolver.getType(uri)?.takeIf { Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+").matches(it) } ?: "application/octet-stream"
            KnowledgeAttachment(id,name,mime,count).also(BackupCodec::validateAttachment)
        } catch(e: Exception) { destination.delete(); throw e }
    }
    suspend fun discard(id: String) = withContext(Dispatchers.IO) { file(id).delete(); Unit }
    suspend fun prune(retained: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff=System.currentTimeMillis()-24*60*60_000L
        folder.listFiles()?.filter { it.extension=="blob" && it.nameWithoutExtension !in retained && it.lastModified()<cutoff }?.forEach { it.delete() }
    }
}
