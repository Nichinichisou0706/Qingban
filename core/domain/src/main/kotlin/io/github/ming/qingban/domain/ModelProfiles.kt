package io.github.ming.qingban.domain

import java.net.URI

/** Public configuration metadata. API keys never cross this boundary. */
data class ModelProfile(
    val id: String,
    val name: String,
    val providerId: String = "custom",
    val baseUrl: String,
    val model: String,
    val jsonMode: Boolean = true,
    val hasKey: Boolean = false,
    val modelIds: List<String> = emptyList()
)

data class ModelProviderPreset(val id: String, val name: String, val baseUrl: String, val jsonMode: Boolean = true)

object ModelProviders {
    val presets = listOf(
        ModelProviderPreset("custom", "自定义兼容接口", ""),
        ModelProviderPreset("openai", "OpenAI", "https://api.openai.com/v1"),
        ModelProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        ModelProviderPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1"),
        ModelProviderPreset("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        ModelProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        ModelProviderPreset("gemini", "Gemini 兼容接口", "https://generativelanguage.googleapis.com/v1beta/openai", false)
    )
    fun name(id: String) = presets.firstOrNull { it.id == id }?.name ?: "自定义兼容接口"
    fun infer(baseUrl: String): String = runCatching {
        val host = URI(baseUrl.trim()).host
        presets.firstOrNull { it.baseUrl.isNotBlank() && URI(it.baseUrl).host == host }?.id ?: "custom"
    }.getOrDefault("custom")
}

object ModelConnectionAddress {
    fun validate(raw: String): String {
        val base = raw.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrElse { error("接口地址格式不正确") }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "请使用 HTTPS 接口地址" }
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "接口地址不能包含用户名、查询参数或片段" }
        return base
    }
}
