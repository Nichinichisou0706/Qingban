package io.github.ming.qingban.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable data class BackupArchive(val schema: Int = 6, val items: List<PlanItem>, val knowledge: List<KnowledgeSource>, val preferences: List<Preference>, val focus: FocusArchive? = null,val habits: HabitState?=null)

/** Validate the entire archive before any database or file changes. */
object BackupCodec {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    fun encode(archive: BackupArchive): String = json.encodeToString(archive)
    fun decode(raw: String): BackupArchive {
        require(raw.length <= 16_000_000) { "备份过大，最多 1600 万字符" }
        return json.decodeFromString<BackupArchive>(raw).also(::validate)
    }
    fun validateAttachment(attachment: KnowledgeAttachment) {
        require(Regex("[a-fA-F0-9-]{36}").matches(attachment.id) && runCatching { java.util.UUID.fromString(attachment.id) }.isSuccess) { "原文件标识不正确" }
        require(attachment.fileName.isNotBlank() && attachment.fileName.length <= 255 && attachment.fileName.none { it == '\u0000' || it == '/' || it == '\\' }) { "原文件名称不正确" }
        require(attachment.mimeType.length in 1..120 && Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+").matches(attachment.mimeType)) { "原文件类型不正确" }
        require(attachment.byteCount in 1..32_000_000) { "原文件大小不正确" }
    }
    fun validate(archive: BackupArchive) {
        require(archive.schema in 1..6) { "备份版本不支持" }
        archive.habits?.let(HabitRules::validate)
        archive.focus?.let(FocusValidation::archive)
        require(archive.items.size <= 50_000 && archive.knowledge.size <= 2000 && archive.preferences.size <= 10_000) { "备份条目过多" }
        require(archive.items.map { it.id }.distinct().size == archive.items.size && archive.knowledge.map { it.id }.distinct().size == archive.knowledge.size && archive.preferences.map { it.key to it.scope }.distinct().size == archive.preferences.size) { "备份包含重复记录" }
        archive.items.forEach { item ->
            require(!item.deleted || item.status==ItemStatus.CANCELLED) { "已删除事项状态不正确" }
            require(item.id.length in 1..128 && item.version > 0 && item.dependsOn.size <= 50 && item.overrides.size <= 10_000) { "事项标识或版本不正确" }
            ScheduleEngine.validate(ItemDraft(item.kind,item.title,item.detail,item.schedule,item.deadline,item.reminder,item.alarm,item.label,item.dependsOn,item.durationMinutes,item.pinned,item.order,item.checklist))?.let { error("无效事项：$it") }
            require(item.overrides.map { it.originalDate }.distinct().size == item.overrides.size) { "重复事项的单次记录重复" }
            item.overrides.forEach { override ->
                override.checklist?.let { list -> require(list.size<=30 && list.all { it.text.isNotBlank() && it.text.length<=240 }) { "单次清单不正确" } }
                LocalDate.parse(override.originalDate)
                override.schedule?.let { s ->
                    require(s.recurrence == null && s.date != null) { "单次记录不能包含重复规则" }
                    ScheduleEngine.validate(ItemDraft(item.kind,item.title,schedule=s,alarm=item.alarm))?.let { error(it) }
                }
            }
        }
        archive.knowledge.forEach { source ->
            require(source.id.length in 1..128 && source.version > 0 && source.title.isNotBlank() && source.title.length<=240 && source.text.isNotBlank() && source.text.length<=250_000 && source.revisions.size<=5) { "资料备份不正确" }
            source.validFrom?.let(LocalDate::parse); source.validUntil?.let(LocalDate::parse)
            require(source.validFrom == null || source.validUntil == null || source.validFrom<=source.validUntil) { "资料有效期顺序不正确" }
            require(source.revisions.all { it.version in 1 until source.version && it.title.isNotBlank() && it.text.length<=250_000 } && source.revisions.map { it.version }.distinct().size==source.revisions.size) { "资料历史版本不正确" }
            source.attachment?.let(::validateAttachment)
        }
        archive.preferences.forEach { preference ->
            require(preference.key.isNotBlank() && preference.key.length<=80 && preference.value.isNotBlank() && preference.value.length<=500 && preference.scope.length<=240) { "偏好备份不正确" }
            preference.validUntil?.let(LocalDate::parse)
            require((preference.sourceId==null)==(preference.sourceVersion==null) && (preference.sourceVersion==null || preference.sourceVersion>0)) { "偏好来源版本不正确" }
        }
    }
}
