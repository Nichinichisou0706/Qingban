package io.github.ming.qingban.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable enum class ItemKind { NOTE, TASK, ALARM }
@Serializable enum class ItemStatus { OPEN, COMPLETED, CANCELLED }
@Serializable enum class DeliveryState { NONE, SCHEDULED, PERMISSION_REQUIRED, FAILED, FIRED }
@Serializable data class RecurrenceSpec(
    val frequency: String = "weekly", val weekdays: Set<Int> = emptySet(),
    @SerialName("start_date") val startDate: String, @SerialName("end_date") val endDate: String? = null,
    @SerialName("work_days") val workDays: Int? = null, @SerialName("rest_days") val restDays: Int? = null,
    @SerialName("cycle_anchor") val cycleAnchor: String? = null
)
@Serializable data class ScheduleSpec(val date: String? = null, val time: String? = null, val period: String? = null, @SerialName("range_start") val rangeStart: String? = null, @SerialName("range_end") val rangeEnd: String? = null, val recurrence: RecurrenceSpec? = null)
@Serializable data class DeadlineSpec(val date: String, val time: String? = null)
@Serializable data class ReminderSpec(@SerialName("at_local") val atLocal: String? = null, @SerialName("offset_minutes") val offsetMinutes: Int? = null)
@Serializable data class AlarmSpec(@SerialName("sound_path") val soundPath: String? = null, val vibrate: Boolean = true, @SerialName("headphones_only") val headphonesOnly: Boolean = false, @SerialName("snooze_minutes") val snoozeMinutes: Int = 5, @SerialName("play_audio") val playAudio: Boolean = true)
@Serializable data class OccurrenceOverride(val originalDate: String, val schedule: ScheduleSpec? = null, val status: ItemStatus = ItemStatus.OPEN, val checklist: List<ChecklistEntry>? = null)
@Serializable data class ChecklistEntry(val text: String, val completed: Boolean = false)
@Serializable data class PlanItem(
    val id: String, val version: Int = 1, val kind: ItemKind, val title: String,
    val detail: String = "", val schedule: ScheduleSpec? = null, val deadline: DeadlineSpec? = null,
    val reminder: ReminderSpec? = null, val alarm: AlarmSpec = AlarmSpec(), val label: String = "",
    val status: ItemStatus = ItemStatus.OPEN, val delivery: DeliveryState = DeliveryState.NONE,
    val overrides: List<OccurrenceOverride> = emptyList(), val createdAt: Long = 0,
    val dependsOn: Set<String> = emptySet(), val durationMinutes: Int? = null,
    val pinned: Boolean = false, val order: Long = 0, val checklist: List<ChecklistEntry> = emptyList(),
    val deleted: Boolean = false
)
data class ItemDraft(val kind: ItemKind, val title: String, val detail: String = "", val schedule: ScheduleSpec? = null, val deadline: DeadlineSpec? = null, val reminder: ReminderSpec? = null, val alarm: AlarmSpec = AlarmSpec(), val label: String = "", val dependsOn: Set<String> = emptySet(), val durationMinutes: Int? = null, val pinned: Boolean = false, val order: Long = 0, val checklist: List<ChecklistEntry> = emptyList())
sealed interface FieldPatch<out T> {
    data object Unchanged : FieldPatch<Nothing>
    data class Set<T>(val value: T?) : FieldPatch<T>
}
data class ItemPatch(val title: String? = null, val detail: String? = null, val schedule: FieldPatch<ScheduleSpec> = FieldPatch.Unchanged, val deadline: FieldPatch<DeadlineSpec> = FieldPatch.Unchanged, val reminder: FieldPatch<ReminderSpec> = FieldPatch.Unchanged, val alarm: AlarmSpec? = null, val label: String? = null, val status: ItemStatus? = null, val dependsOn: Set<String>? = null, val durationMinutes: FieldPatch<Int> = FieldPatch.Unchanged, val pinned: Boolean? = null, val order: Long? = null, val checklist: List<ChecklistEntry>? = null)
enum class EditScope { THIS_OCCURRENCE, THIS_AND_FUTURE, WHOLE_SERIES }
@Serializable data class StoredChange(val id: String, val before: PlanItem?, val after: PlanItem, val requestKey: String, val timestamp: Long)
@Serializable data class StoredBatch(val id: String, val changes: List<StoredChange>, val requestKey: String, val timestamp: Long)
sealed interface BatchCommand {
    data class Create(val draft: ItemDraft, val id: String = java.util.UUID.randomUUID().toString()) : BatchCommand
    data class Update(val id: String, val version: Int, val patch: ItemPatch, val scope: EditScope = EditScope.WHOLE_SERIES, val occurrenceDate: String? = null) : BatchCommand
    data class Delete(val id: String, val version: Int, val scope: EditScope = EditScope.WHOLE_SERIES, val occurrenceDate: String? = null) : BatchCommand
}
data class OperationResult(val status: String, val message: String, val item: PlanItem? = null, val changeId: String? = null, val errorCode: String? = null)
data class ScheduleResult(val state: DeliveryState, val message: String = "")
@Serializable data class KnowledgeRevision(val version: Int, val title: String, val text: String, val savedAt: Long)
@Serializable data class KnowledgeAttachment(val id: String, val fileName: String, val mimeType: String, val byteCount: Long)
@Serializable data class KnowledgeSource(val id: String, val title: String, val text: String, val importedAt: Long, val version: Int = 1, val enabled: Boolean = true, val allowRemote: Boolean = false, val validUntil: String? = null, val validFrom: String? = null, val origin: String = "用户导入", val reliability: String = "未确认", val revisions: List<KnowledgeRevision> = emptyList(), val attachment: KnowledgeAttachment? = null)
@Serializable data class KnowledgeHit(val sourceId: String, val title: String, val excerpt: String, val version: Int, val allowRemote: Boolean, val validUntil: String? = null)
@Serializable data class Preference(val key: String, val value: String, val scope: String = "", val source: String, val confirmedAt: Long, val enabled: Boolean = true, val validUntil: String? = null, val sourceId: String? = null, val sourceVersion: Int? = null, val sourceDeleted: Boolean = false, val learned: Boolean = false)
@Serializable data class ChoiceObservation(val id: String, val activity: String, val period: String, val date: String, val origin: String = "user")
@Serializable data class HabitCandidate(val id: String, val activity: String, val period: String, val count: Int, val distinctDays: Int)
@Serializable data class MemoryChange(val id: String, val key: String, val scope: String, val before: Preference?, val after: Preference?, val timestamp: Long)
