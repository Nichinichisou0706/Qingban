package io.github.ming.qingban.domain

import kotlinx.serialization.Serializable
@Serializable data class SoundChoice(val id: String, val title: String)
interface SoundLibrary {
    suspend fun list(): List<SoundChoice> = emptyList()
    suspend fun path(id: String): String? = null
}
