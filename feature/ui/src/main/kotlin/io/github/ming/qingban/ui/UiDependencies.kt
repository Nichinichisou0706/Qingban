package io.github.ming.qingban.ui

import io.github.ming.qingban.agent.AgentCoordinator
import io.github.ming.qingban.domain.Services

data class ImportedText(val title: String, val text: String, val attachment: io.github.ming.qingban.domain.KnowledgeAttachment? = null)
data class MusicTrack(val path: String, val title: String)
data class RemoteTrack(val url: String, val title: String)
interface PlatformActions {
    fun appInfo(): Map<String,String>
    fun permissions(): Map<String, Boolean>
    fun requestPermission(kind: String)
    fun startPet()
    fun stopPet()
    fun pinWidget()
    suspend fun readText(uri: String, keepOriginal: Boolean = false): ImportedText
    fun openOriginal(attachment: io.github.ming.qingban.domain.KnowledgeAttachment)
    suspend fun removeOriginal(id: String)
    suspend fun fetchWeb(url: String): ImportedText
    suspend fun importMusic(uri: String): String
    suspend fun musicLibrary(): List<MusicTrack>
    suspend fun remotePlaylist(url: String): List<RemoteTrack>
    suspend fun cacheMusic(url: String, title: String? = null): String
    fun previewMusic(path: String?, headphonesOnly: Boolean)
    fun stopPreview()
    fun openNetease()
    fun sharedSong(): String
    fun endSongSelection()
    fun petSettings(): Map<String, String>
    fun savePetSettings(size: Int, alpha: Float, animations: Boolean)
    fun hidePet()
    suspend fun exportPortable(uri: String)
    suspend fun restorePortable(uri: String)
    suspend fun evaluateModel(conversations: Boolean = false,scenario: String?=null): String
    fun modelSummary(): Map<String, String>
    fun modelProfiles(): List<io.github.ming.qingban.domain.ModelProfile>
    fun activeModelId(): String?
    fun saveModelProfile(profile: io.github.ming.qingban.domain.ModelProfile, key: String, makeActive: Boolean)
    fun activateModel(id: String?)
    fun deleteModelProfile(id: String)
    suspend fun discoverModels(base: String, key: String, retainedProfileId: String?): List<String>
    fun saveModel(base: String, model: String, key: String, jsonMode: Boolean)
    fun clearModel()
    suspend fun exportBackup(uri: String)
    suspend fun restoreBackup(uri: String)
}
data class UiDependencies(val services: Services, val assistant: AgentCoordinator, val platform: PlatformActions)
