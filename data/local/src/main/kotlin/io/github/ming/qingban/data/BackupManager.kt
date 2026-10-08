package io.github.ming.qingban.data

import androidx.room.withTransaction
import io.github.ming.qingban.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BackupManager(private val local: LocalStores) {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    suspend fun export(): String {
        val focus=local.sessions.read("focus_state")?.let { json.decodeFromString<FocusState>(it) }
        val habits=local.sessions.read("habit_state")?.let { json.decodeFromString<HabitState>(it) }
        return json.encodeToString(BackupArchive(items = local.items.all(), knowledge = local.knowledge.all(), preferences = local.memory.listPreferences(),focus=focus?.let { FocusArchive(it.settings,it.records) },habits=habits))
    }
    suspend fun restore(raw: String, musicPaths: Map<String,String> = emptyMap(), attachmentIds: Map<String,String> = emptyMap()) {
        val archive = BackupCodec.decode(raw)
        local.database.withTransaction {
            val dao = local.database.dao()
            val ids = local.items.all().map { it.id }.toSet()
            val merged = local.items.all().associateBy { it.id }.toMutableMap(); archive.items.filterNot { it.id in ids }.forEach { merged[it.id] = it }
            DependencyPlanner.validate(merged)?.let { error("备份先后关系无效：$it") }
            archive.items.filterNot { it.id in ids }.forEach { item ->
                val restored = item.copy(version = 1, alarm = item.alarm.copy(soundPath = item.alarm.soundPath?.let { musicPaths[it] }), delivery = if(item.delivery == DeliveryState.FIRED) DeliveryState.FIRED else DeliveryState.NONE)
                dao.putItem(ItemEntity(restored.id, restored.version, json.encodeToString(restored), restored.delivery.name))
            }
            val sources = local.knowledge.all().map { it.id }.toSet()
            archive.knowledge.filterNot { it.id in sources }.forEach { source -> local.knowledge.save(source.copy(attachment=source.attachment?.let { original -> attachmentIds[original.id]?.let { original.copy(id=it) } })) }
            val prefs = local.memory.listPreferences().map { it.key to it.scope }.toSet()
            archive.preferences.filterNot { (it.key to it.scope) in prefs }.forEach { local.memory.save(it) }
            archive.focus?.let { imported ->
                val existing=local.sessions.read("focus_state")?.let { json.decodeFromString<FocusState>(it) }
                val merged=(existing ?: FocusState(settings=imported.settings)).copy(records=((existing?.records ?: emptyList())+imported.records).distinctBy { it.id }.sortedBy { it.endedAt }.takeLast(2000))
                // Restoring a backup never restarts an old timer.
                local.sessions.write("focus_state",json.encodeToString(merged))
            }
            archive.habits?.let { imported ->
                val old=local.sessions.read("habit_state")?.let { json.decodeFromString<HabitState>(it) }
                val merged=HabitState(((old?.habits ?: emptyList())+imported.habits).distinctBy { it.id },((old?.checks ?: emptyList())+imported.checks).distinctBy { it.habitId to it.date },old?.gentle ?: false)
                HabitRules.validate(merged); local.sessions.write("habit_state",json.encodeToString(merged))
            }
        }
    }
}
