package io.github.ming.qingban.model

import io.github.ming.qingban.domain.ModelConnectionAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object ModelEndpoints {
    private fun root(raw: String): String {
        val base = ModelConnectionAddress.validate(raw)
        val prefix = when {
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions")
            base.endsWith("/models") -> base.removeSuffix("/models")
            else -> base
        }
        return if (prefix.toHttpUrl().encodedPath == "/") "$prefix/v1" else prefix
    }
    fun chat(raw: String): HttpUrl = "${root(raw)}/chat/completions".toHttpUrl()
    fun models(raw: String): HttpUrl = "${root(raw)}/models".toHttpUrl()
}
