package io.github.ming.qingban.platform

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MusicImporter(private val context: Context) {
    private val catalog = context.getSharedPreferences("music_catalog", Context.MODE_PRIVATE)
    fun library(): List<Pair<String,String>> = File(context.filesDir, "music").listFiles()?.filter { it.isFile }?.map { it.absolutePath to catalog.getString(it.name, "导入音乐 ${it.name.take(6)}")!! } ?: emptyList()
    suspend fun cache(url: String, title: String? = null): String = withContext(Dispatchers.IO) {
        val file = File(File(context.filesDir,"music").apply { mkdirs() }, "${UUID.randomUUID()}.audio")
        try { RemoteMusic().download(url,file); validate(file); catalog.edit().putString(file.name, title?.take(120) ?: java.net.URI(url).path.substringAfterLast('/').take(120)).apply(); file.absolutePath } catch(e: Exception) { file.delete(); throw e }
    }
    private fun validate(file: File) { val retriever = MediaMetadataRetriever(); try { retriever.setDataSource(file.absolutePath); require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0) { "该文件不是可用音频" } } finally { retriever.release() } }
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "music").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.audio")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(16384); var total = 0L
                while(true) { val count = input.read(buffer); if(count == -1) break; total += count; require(total <= 128L * 1024 * 1024) { "音乐文件超过 128 MiB" }; output.write(buffer, 0, count) }
            } } ?: error("无法读取音乐")
            validate(file)
            val title = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "导入音乐" } ?: "导入音乐"
            catalog.edit().putString(file.name,title).apply()
            file.absolutePath
        } catch(e: Exception) { file.delete(); throw e }
    }
}
