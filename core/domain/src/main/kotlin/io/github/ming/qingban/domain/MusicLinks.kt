package io.github.ming.qingban.domain

import java.net.URI
object MusicLinks {
    fun sharedUrl(text: String): String? = Regex("https?://[^\\s<>\u3002]+").findAll(text).firstNotNullOfOrNull { match -> runCatching {
        val uri=URI(match.value.trimEnd(')',']','，',','))
        if(uri.host in setOf("music.163.com","y.music.163.com","163cn.tv") && uri.userInfo==null) uri.toString().replaceFirst("http://","https://") else null
    }.getOrNull() }
    fun neteaseId(text: String): String? = Regex("https?://[^\\s<>\u3002]+").findAll(text).firstNotNullOfOrNull { match ->
        runCatching {
            val uri=URI(match.value.trimEnd(')',']','，',','))
            if(uri.host !in setOf("music.163.com","y.music.163.com") || uri.userInfo!=null) return@runCatching null
            val path=uri.fragment ?: (uri.path+"?"+uri.query.orEmpty())
            if(!Regex("(?:^|/)song(?:\\?|/)").containsMatchIn(path.trimStart('/'))) return@runCatching null
            Regex("(?:[?&]id=|song/)([0-9]{1,18})(?:[&/?#]|$)").find(path)?.groupValues?.get(1)
        }.getOrNull()
    }
    fun publicStream(id: String): String { require(Regex("[0-9]{1,18}").matches(id)); return "https://music.163.com/song/media/outer/url?id=$id.mp3" }
    fun title(text: String,id: String)=Regex("《([^》]{1,120})》").find(text)?.groupValues?.get(1) ?: "网易云歌曲 $id"
}
