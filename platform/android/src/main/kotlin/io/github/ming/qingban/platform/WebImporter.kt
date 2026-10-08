package io.github.ming.qingban.platform

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

class WebImporter {
    suspend fun fetch(address: String): ParsedDocument = withContext(Dispatchers.IO) {
        var uri=URI(address); var connection: HttpURLConnection?=null
        try {
            repeat(6) {
                if(connection==null) {
                    require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null) { "网页链接须为 HTTPS" }
                    val next=uri.toURL().openConnection() as HttpURLConnection
                    next.connectTimeout=15_000; next.readTimeout=30_000; next.instanceFollowRedirects=false
                    val status=next.responseCode
                    if(status in setOf(301,302,303,307,308)) { val location=next.getHeaderField("Location"); next.disconnect(); require(location!=null) { "网页跳转无效" }; uri=uri.resolve(location) }
                    else { if(status !in 200..299) { next.disconnect(); error("网页返回 HTTP $status") }; connection=next }
                }
            }
            val response=connection ?: error("网页跳转次数过多")
            require(response.contentType.orEmpty().contains("text/") || response.contentType.orEmpty().contains("html")) { "请导入网页或文本；其他文件请下载后导入" }
            val raw=response.inputStream.use { input -> val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0) break; require(output.size()+n<=2_000_000) { "网页正文过大" }; output.write(buffer,0,n) }; output.toString("UTF-8") }
            val title=Regex("<title[^>]*>(.*?)</title>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)?.let { Html.fromHtml(it,Html.FROM_HTML_MODE_LEGACY).toString() }?.take(240) ?: uri.host
            val clean=raw.replace(Regex("<(script|style|noscript)[^>]*>.*?</\\1>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)),"")
            val text=if(response.contentType.orEmpty().contains("html")) Html.fromHtml(clean,Html.FROM_HTML_MODE_LEGACY).toString().trim() else raw
            require(text.isNotBlank() && text.length<=250_000) { "网页没有可导入的正文，或超过 25 万字" }
            ParsedDocument(title,text,"网页：$uri")
        } finally { connection?.disconnect() }
    }
}
