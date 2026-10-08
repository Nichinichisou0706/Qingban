package io.github.ming.qingban.model

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Discovers only IDs advertised by the configured OpenAI-compatible endpoint. */
class ModelDiscoveryClient {
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun discover(baseUrl: String, apiKey: String): List<String> {
        require(apiKey.isNotBlank()) { "请先填写 API 密钥" }
        val request = Request.Builder().url(ModelEndpoints.models(baseUrl))
            .header("Authorization", "Bearer ${apiKey.trim()}").header("Accept", "application/json").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("获取模型失败，请检查网络与接口地址"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val models = response.use { readModels(it) }
                        if (continuation.isActive) continuation.resume(models)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun readModels(response: Response): List<String> {
        if (!response.isSuccessful) error(when (response.code) {
            401, 403 -> "密钥无效或没有查看模型的权限（HTTP ${response.code}）"
            404, 405 -> "接口未提供模型列表，可手动填写模型名称"
            429 -> "请求过于频繁，请稍后刷新模型列表"
            else -> "获取模型失败（HTTP ${response.code}），可稍后重试或手动填写"
        })
        val body = response.body ?: error("接口没有返回模型列表")
        require(body.contentLength() <= 4_000_000) { "模型列表响应过大" }
        val source = body.source(); source.request(4_000_001)
        require(source.buffer.size <= 4_000_000) { "模型列表响应过大" }
        val raw = runCatching { Json.parseToJsonElement(source.readUtf8()) }.getOrElse { error("接口返回的模型列表格式不正确，可手动填写") }
        val data = (raw as? JsonObject)?.get("data") as? JsonArray ?: error("接口未返回兼容的模型列表，可手动填写模型名称")
        val models = data.mapNotNull { row ->
            val id = (row as? JsonObject)?.get("id") as? JsonPrimitive
            id?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        require(models.isNotEmpty()) { "接口没有返回可用模型，可手动填写模型名称" }
        return models
    }
}
