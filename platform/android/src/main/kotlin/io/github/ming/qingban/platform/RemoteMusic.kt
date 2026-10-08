package io.github.ming.qingban.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class OnlineTrack(val url: String, val title: String)
class RemoteMusic {
    private fun connect(address: String): HttpURLConnection {
        var current = URI(address)
        repeat(6) {
            require(current.scheme == "https" && current.host != null && current.userInfo == null) { "音乐链接须为 HTTPS" }
            val connection = URL(current.toASCIIString()).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if(status in setOf(301,302,303,307,308)) {
                val target = connection.getHeaderField("Location") ?: error("跳转链接不完整"); connection.disconnect(); current = current.resolve(target)
                if(current.scheme=="http" && (current.host?.endsWith(".music.126.net")==true || current.host in setOf("music.163.com","y.music.163.com","163cn.tv"))) current=URI("https",current.userInfo,current.host,current.port,current.path,current.query,current.fragment)
            } else { if(status !in 200..299) { connection.disconnect(); error("下载失败：HTTP $status") }; return connection }
        }; error("链接跳转次数过多")
    }
    suspend fun playlist(address: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val musicLinks=io.github.ming.qingban.domain.MusicLinks
        musicLinks.sharedUrl(address)?.let { link ->
            val id=musicLinks.neteaseId(address) ?: connect(link).let { connection -> try { musicLinks.neteaseId(connection.url.toString()) } finally { connection.disconnect() } }
            require(id!=null) { "这个分享链接未提供歌曲信息，请复制歌曲的完整网页链接或导入音频" }
            return@withContext listOf(OnlineTrack(musicLinks.publicStream(id),musicLinks.title(address,id)))
        }
        if(!URI(address).path.lowercase().endsWith(".m3u")) return@withContext listOf(OnlineTrack(address, URI(address).path.substringAfterLast('/').ifBlank { "在线音频" }))
        val connection = connect(address)
        try {
            require(connection.contentLengthLong <= 1_000_000) { "歌单过大" }
            val bytes = connection.inputStream.use { input -> val buffer = java.io.ByteArrayOutputStream(); val block = ByteArray(8192); while(true) { val n = input.read(block); if(n < 0) break; require(buffer.size()+n <= 1_000_000) { "歌单过大" }; buffer.write(block,0,n) }; buffer.toByteArray() }
            var title: String? = null
            val result = mutableListOf<OnlineTrack>()
            bytes.toString(Charsets.UTF_8).lineSequence().forEach { raw ->
                val line = raw.trim()
                if(line.startsWith("#EXTINF:")) title = line.substringAfter(',').take(120)
                else if(line.isNotBlank() && !line.startsWith('#')) {
                    val resolved = URI(connection.url.toString()).resolve(line)
                    require(resolved.scheme == "https" && resolved.userInfo == null) { "歌单中含有非 HTTPS 链接" }
                    require(!resolved.path.endsWith(".m3u8", true)) { "暂不支持分段直播流，请提供音频文件直链" }
                    result += OnlineTrack(resolved.toString(), title ?: resolved.path.substringAfterLast('/')); title = null
                }
            }
            require(result.isNotEmpty() && result.size <= 50) { "歌单需包含 1–50 首可直接下载的音频" }; result
        } finally { connection.disconnect() }
    }
    suspend fun download(address: String, destination: File) = withContext(Dispatchers.IO) {
        require(!URI(address).path.endsWith(".m3u8", true)) { "请使用音频文件直链" }
        val connection = connect(address)
        try {
            require(connection.contentLengthLong <= 128L*1024*1024) { "单首音乐最多 128 MiB" }
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(16384); var size = 0L
                while(true) { val count = input.read(buffer); if(count < 0) break; size += count; require(size <= 128L*1024*1024) { "音乐过大" }; output.write(buffer,0,count) }
            } }
        } finally { connection.disconnect() }
    }
}
