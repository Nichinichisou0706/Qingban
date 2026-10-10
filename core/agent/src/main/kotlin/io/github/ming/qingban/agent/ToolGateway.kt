package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

class ToolGateway(private val services: Services) {
    private val json = Json
    private val mutating = setOf("create_item", "update_item", "cancel_item", "delete_item", "write_memory", "undo_change", "propose_changes", "split_task", "apply_proposal", "focus_control", "focus_settings", "navigate", "set_alarm_sound", "manage_knowledge")
    private val features=FeatureTools(services)
    suspend fun execute(call: Decision.Call, requestKey: String, userText: String, remote: Boolean, approvedProposal: Boolean = false): JsonObject {
        if(call.tool in mutating || call.tool in setOf("habit_manage","gentle_settings")) services.sessions.read("tool_result:$requestKey")?.let { return json.parseToJsonElement(it).jsonObject }
        val result = executeUncached(call, requestKey, userText, remote, approvedProposal)
        if((call.tool in mutating || call.tool in setOf("habit_manage","gentle_settings")) && result["status"]?.jsonPrimitive?.content in setOf("ok", "partial", "approval_pending")) services.sessions.write("tool_result:$requestKey", result.toString())
        return result
    }
    private suspend fun executeUncached(call: Decision.Call, requestKey: String, userText: String, remote: Boolean, approvedProposal: Boolean): JsonObject = try {
        val a = call.args
        features.execute(call,requestKey,userText,remote)?.let { return it }
        if(!approvedProposal) {
            if(call.tool in setOf("cancel_item","update_item","delete_item")) require(!NEGATED_CHANGE.containsMatchIn(userText)) { "用户没有要求修改或取消事项" }
            if(call.tool == "cancel_item") require(Regex("取消|删除|删掉|关闭|不要这|停止").containsMatchIn(userText)) { "当前请求没有授权取消事项" }
            if(call.tool == "update_item") require(Regex("改|挪|调整|换|推迟|提前|完成|做完|办完|搞定|恢复|启用|开始|重新安排|另外安排|安排到|安排在").containsMatchIn(userText)) { "当前请求没有授权修改事项" }
            if(call.tool == "create_item") require(!Regex("有什么|有哪些|有课吗|什么时候|查询|查一下|搜一下").containsMatchIn(userText) || Regex("添加|记下|记录|安排|创建|设.*闹钟").containsMatchIn(userText)) { "查询不能自行新增事项" }
        }
        when(call.tool) {
            "query_items" -> {
                a.allowed("query", "date_from", "date_to", "kind", "status", "habit_only", "offset", "limit")
                val q = a.string("query") ?: ""
                val from = a.string("date_from")?.let(LocalDate::parse)
                val to = a.string("date_to")?.let(LocalDate::parse)
                require(to==null || from!=null) { "结束日期需要开始日期" }
                val days=if(from==null) 0L else java.time.temporal.ChronoUnit.DAYS.between(from,to ?: from)
                require(days in 0..366) { "查询日期顺序不正确，单次最多一年" }
                val status=a.string("status") ?: "open"
                require(status in setOf("open","completed","cancelled") && (a.string("kind")==null || a.string("kind") in setOf("note","task","alarm"))) { "查询类型或状态不正确" }
                val offset=a["offset"]?.jsonPrimitive?.int ?: 0; val limit=a["limit"]?.jsonPrimitive?.int ?: 30
                require(offset>=0 && limit in 1..100) { "查询分页参数不正确" }
                val matched = services.items.store.all().filterNot { it.deleted }.filter { a["habit_only"]?.jsonPrimitive?.boolean!=true || it.isHabitTask }.mapNotNull { item ->
                    if(!(q.isBlank() || q in item.title || q in item.detail) || (a.string("kind")!=null && !item.kind.name.equals(a.string("kind"),true))) null
                    else if(from==null) item.takeIf { it.status.name.equals(status,true) }
                    else (0L..days).firstNotNullOfOrNull { offset -> ScheduleEngine.onDate(item,from.plusDays(offset),status=="cancelled")?.takeIf { it.status.name.equals(status,true) } }
                }
                val items=matched.drop(offset).take(limit)
                buildJsonObject { put("status", "ok"); put("total",matched.size); put("has_more",offset+items.size<matched.size); put("items", json.encodeToJsonElement(items.map { it.copy(detail=it.detail.take(500),overrides=emptyList()) })) }
            }
            "get_item" -> { a.allowed("item_id"); val item = services.items.store.get(a.required("item_id"))?.takeUnless { it.deleted } ?: error("没找到事项"); buildJsonObject { put("status", "ok"); put("item", json.encodeToJsonElement(item)) } }
            "search_knowledge" -> {
                a.allowed("query", "include_historical")
                val hits = services.knowledge.search(a.required("query"), remote, a["include_historical"]?.jsonPrimitive?.boolean ?: false)
                buildJsonObject { put("status", "ok"); put("sources", json.encodeToJsonElement(hits)) }
            }
            "create_item" -> {
                val draft=decodeDraft(a)
                val blocks=PlanBlocks.split(draft)
                if(blocks!=null) stage("已将全天计划拆为 ${blocks.size} 个独立时段，请核对后保存",blocks.map { ProposedAction("create_item",draftArgs(it)) })
                else {
                    val result = services.items.create(draft, requestKey)
                    if(result.status != "error" && Regex("上午|下午|晚上|早上|晚间|[零一二三四五六七八九十两\\d]+[点:：]").containsMatchIn(userText)) result.item?.let { services.memory.recordChoice(it) }
                    outcome(result)
                }
            }
            "update_item" -> {
                a.allowed("item_id", "expected_version", "scope", "patch", "occurrence_date")
                val current = services.items.store.get(a.required("item_id"))?.takeUnless { it.deleted } ?: error("事项不存在")
                val newDetail=a["patch"]!!.jsonObject.string("detail")
                if(newDetail!=null) require(PlanBlocks.split(ItemDraft(current.kind,current.title,newDetail,current.schedule))==null) { "多个时段需要拆为独立任务，请用 split_task 或 propose_changes" }
                val result = services.items.update(a.required("item_id"), a.requiredInt("expected_version"), decodePatch(a["patch"]!!.jsonObject, current.alarm), scope(a.required("scope")), a.string("occurrence_date"), requestKey)
                if (result.status != "error" && a["patch"]?.jsonObject?.containsKey("schedule") == true && Regex("上午|下午|晚上|早上|晚间|[零一二三四五六七八九十两\\d]+[点:：]").containsMatchIn(userText)) result.item?.let { services.memory.recordChoice(it, "user") }
                outcome(result)
            }
            "delete_item" -> {
                require(Regex("删除|删掉|移除|清空").containsMatchIn(userText)) { "当前请求没有授权删除事项" }
                a.allowed("item_id","expected_version","scope","occurrence_date")
                stage("删除所选事项并停止相关提醒，可撤销；请核对删除范围",listOf(ProposedAction("delete_item",a)))
            }
            "cancel_item" -> {
                a.allowed("item_id", "expected_version", "scope", "occurrence_date")
                stage("取消所选事项并停止相关提醒，可撤销；请核对取消范围",listOf(ProposedAction("cancel_item",a)))
            }
            "undo_change" -> { a.allowed("change_id"); outcome(services.undo(a.string("change_id"), requestKey)) }
            "write_memory" -> {
                a.allowed("operation", "key", "value", "scope", "basis", "valid_until")
                require(a.required("basis") == "user_declared" && Regex("记住|以后|每次|长期|忘掉|忘记|删除.*偏好|不适用|不再用").containsMatchIn(userText)) { "长期偏好需要用户明确要求；习惯候选请通过确认卡片采用" }
                val key = a.required("key"); val scoped = a["scope"]?.takeUnless { it == JsonNull }?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""
                val change = when(a.required("operation")) {
                    "set" -> services.memory.setPreference(key, a.required("value"), userText, scoped, a.string("valid_until"), requestKey=requestKey)
                    "delete" -> services.memory.forget(key, scoped, requestKey)
                    "disable" -> services.memory.disable(key, scoped, requestKey)
                    else -> error("未知记忆操作")
                }
                buildJsonObject { put("status", "ok"); put("message", "偏好已更新，可在资料与记忆中查看、更正或删除"); put("change_id", change.id) }
            }
            "propose_changes" -> {
                a.allowed("summary", "changes")
                val changes = json.decodeFromJsonElement<List<ProposedAction>>(a["changes"]!!)
                if(changes.any { it.tool=="delete_item" }) require(Regex("删除|删掉|移除|清空").containsMatchIn(userText)) { "当前请求没有授权删除事项" }
                val granular=changes.flatMap { action -> if(action.tool=="create_item" && "plan_key" !in action.args) PlanBlocks.split(decodeDraft(action.args))?.map { ProposedAction("create_item",draftArgs(it)) } ?: listOf(action) else listOf(action) }
                stage(a.required("summary"),granular)
            }
            "split_task" -> {
                a.allowed("item_id","expected_version")
                require(Regex("拆|分开|分块|细化").containsMatchIn(userText)) { "请先确认要拆分的任务" }
                val item=services.items.store.get(a.required("item_id"))?.takeUnless { it.deleted } ?: error("事项不存在")
                require(item.version==a.requiredInt("expected_version") && item.status==ItemStatus.OPEN) { "任务已变化，请重新查询" }
                val drafts=PlanBlocks.split(ItemDraft(item.kind,item.title,item.detail,item.schedule,item.deadline,item.reminder,item.alarm,item.label,item.dependsOn,item.durationMinutes,item.pinned,item.order,item.checklist)) ?: error("未找到多个明确时间段，请提问后逐项生成方案")
                val creates=drafts.mapIndexed { i,d -> ProposedAction("create_item",JsonObject(draftArgs(d)+("plan_key" to JsonPrimitive("block$i")))) }
                val successors=services.items.store.all().filter { item.id in it.dependsOn && it.status!=ItemStatus.CANCELLED }.map { successor -> ProposedAction("update_item",buildJsonObject {
                    put("item_id",successor.id); put("expected_version",successor.version); put("scope","whole_series"); put("patch",buildJsonObject { put("depends_on",json.encodeToJsonElement(successor.dependsOn-item.id+drafts.indices.map { "@block$it" })) })
                }) }
                stage("把“${item.title}”拆成 ${drafts.size} 个独立任务，原任务归档；前置关系将跟随更新，可整批撤销",listOf(ProposedAction("cancel_item",buildJsonObject { put("item_id",item.id); put("expected_version",item.version); put("scope","whole_series") }))+creates+successors)
            }
            "review_overdue" -> { a.allowed(); buildJsonObject { put("status","ok"); put("review",json.encodeToJsonElement(services.review.refresh(true))); put("message","查看日终调整卡片；只有点击同意并调整才保存，不能由模型代确认") } }
            "apply_proposal" -> {
                a.allowed("proposal_id")
                val id = a.required("proposal_id")
                services.sessions.read("proposal_applied:$id")?.let { return json.parseToJsonElement(it).jsonObject }
                val p = json.decodeFromString<Proposal>(services.sessions.read("proposal:$id") ?: error("方案不存在"))
                require(p.approved && services.clock.millis() < p.expiresAt) { "方案未确认或已过期" }
                p.changes.forEach { validateProposed(it) }
                val refs=p.changes.filter { it.tool=="create_item" }.mapNotNull { action -> action.args.string("plan_key")?.let { it to UUID.randomUUID().toString() } }.toMap()
                val commands = p.changes.map { action ->
                    val args = resolveRefs(action.args,refs)
                    if(action.tool == "create_item") BatchCommand.Create(decodeDraft(args),action.args.string("plan_key")?.let { refs.getValue(it) } ?: UUID.randomUUID().toString()) else {
                        if(action.tool in setOf("cancel_item","delete_item")) args.allowed("item_id","expected_version","scope","occurrence_date") else args.allowed("item_id", "expected_version", "scope", "patch", "occurrence_date")
                        val item = services.items.store.get(args.required("item_id"))?.takeUnless { it.deleted } ?: error("事项不存在")
                        if(action.tool=="delete_item") BatchCommand.Delete(item.id,args.requiredInt("expected_version"),scope(args.required("scope")),args.string("occurrence_date")) else BatchCommand.Update(item.id, args.requiredInt("expected_version"), if(action.tool == "cancel_item") ItemPatch(status = ItemStatus.CANCELLED) else decodePatch(args["patch"]!!.jsonObject, item.alarm), scope(args.required("scope")), args.string("occurrence_date"))
                    }
                }
                val result = services.items.applyBatch(commands, "proposal:$id")
                outcome(result).also { if(result.status in setOf("ok", "partial")) { services.sessions.remove("current_proposal"); services.sessions.write("proposal_applied:$id", it.toString()) } }
            }
            else -> error("未知工具 ${call.tool}")
        }
    } catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; buildJsonObject { put("status", "error"); put("message", e.message ?: "参数不正确"); put("error_code", "invalid_tool_request") } }

    suspend fun approve(id: String) {
        val p = json.decodeFromString<Proposal>(services.sessions.read("proposal:$id") ?: error("方案不存在"))
        require(services.clock.millis() < p.expiresAt) { "方案已过期" }
        services.sessions.write("proposal:$id", json.encodeToString(p.copy(approved = true)))
    }
    suspend fun editPreview(id: String, changes: List<ProposedAction>) {
        val p=json.decodeFromString<Proposal>(services.sessions.read("proposal:$id") ?: error("方案不存在"))
        require(services.clock.millis()<p.expiresAt && changes.size in 1..50) { "方案已过期或没有选择任何事项" }
        changes.forEach { validateProposed(it) }
        services.sessions.write("proposal:$id",json.encodeToString(p.copy(changes=changes,approved=false)))
    }
    suspend fun pendingProposal(): Proposal? = services.sessions.read("current_proposal")?.let { id -> services.sessions.read("proposal:$id")?.let { json.decodeFromString<Proposal>(it) } }
    private suspend fun stage(summary: String,changes: List<ProposedAction>): JsonObject {
        require(changes.size in 1..50) { "每个方案最多 50 个独立安排" }
        val keys=changes.filter { it.tool=="create_item" }.mapNotNull { it.args.string("plan_key") }
        require(keys.distinct().size==keys.size && keys.all { Regex("[a-zA-Z0-9_-]{1,40}").matches(it) }) { "方案内 plan_key 必须唯一，使用短字母或数字" }
        changes.forEach { action -> val fields=action.args["patch"]?.jsonObject ?: action.args; fields["depends_on"]?.jsonArray?.forEach { ref -> val id=ref.jsonPrimitive.content; require(!id.startsWith("@") || id.drop(1) in keys) { "方案的前置引用不存在" } } }
        changes.forEach { validateProposed(it) }
        val p=Proposal(UUID.randomUUID().toString(),summary,changes,services.clock.millis()+60*60_000)
        services.sessions.write("proposal:${p.id}",json.encodeToString(p)); services.sessions.write("current_proposal",p.id)
        return buildJsonObject { put("status","approval_pending"); put("proposal_id",p.id); put("summary",p.summary); put("changes",json.encodeToJsonElement(changes)) }
    }
    private fun resolveRefs(args: JsonObject,refs: Map<String,String>): JsonObject {
        val patch=args["patch"]?.jsonObject
        val fields=patch ?: JsonObject(args-"plan_key")
        val changed=fields["depends_on"]?.jsonArray?.let { deps -> JsonObject(fields+("depends_on" to JsonArray(deps.map { value -> val id=value.jsonPrimitive.content; if(id.startsWith("@")) JsonPrimitive(refs.getValue(id.drop(1))) else value }))) } ?: fields
        return if(patch!=null) JsonObject(args+("patch" to changed)) else changed
    }
    private fun draftArgs(d: ItemDraft)=buildJsonObject {
        put("kind",d.kind.name.lowercase()); put("title",d.title); put("detail",d.detail)
        put("schedule",json.encodeToJsonElement(d.schedule)); put("deadline",json.encodeToJsonElement(d.deadline)); put("reminder",json.encodeToJsonElement(d.reminder))
        put("label",d.label); put("duration_minutes",json.encodeToJsonElement(d.durationMinutes)); put("depends_on",json.encodeToJsonElement(d.dependsOn)); put("checklist",json.encodeToJsonElement(d.checklist)); put("pinned",d.pinned)
    }
    private suspend fun validateProposed(action: ProposedAction) {
        when(action.tool) {
            "create_item" -> { val d=decodeDraft(JsonObject(action.args-"plan_key")); ScheduleEngine.validate(d)?.let { error(it) }; require(PlanBlocks.split(d)==null) { "仍包含多个时段，请拆成多个 create_item" } }
            "update_item", "cancel_item", "delete_item" -> {
                if(action.tool in setOf("cancel_item","delete_item")) action.args.allowed("item_id","expected_version","scope","occurrence_date") else action.args.allowed("item_id","expected_version","scope","occurrence_date","patch")
                val item = services.items.store.get(action.args.required("item_id"))?.takeUnless { it.deleted } ?: error("事项不存在")
                require(item.version == action.args.requiredInt("expected_version")) { "方案目标已变化" }
                val editScope=scope(action.args.required("scope")); val occurrence=action.args.string("occurrence_date")?.let(LocalDate::parse)
                if(action.tool=="delete_item") {
                    require(editScope in setOf(EditScope.THIS_OCCURRENCE,EditScope.WHOLE_SERIES)) { "删除范围必须是仅本次或整个系列" }
                    if(item.isHabitTask && editScope==EditScope.THIS_OCCURRENCE) require(occurrence!=null && ScheduleEngine.onDate(item,occurrence)!=null) { "请选择实际发生的日期" }
                }
                if (action.tool == "update_item") decodePatch(action.args["patch"]!!.jsonObject, item.alarm)
            }
            else -> error("方案只允许新增、修改、取消或删除事项")
        }
    }
    private fun decodeDraft(a: JsonObject): ItemDraft {
        a.allowed("kind", "title", "detail", "schedule", "deadline", "reminder", "alarm_preset_id", "label", "alarm", "depends_on", "duration_minutes", "pinned", "checklist")
        require(a.string("alarm_preset_id") in setOf(null, "default")) { "响铃预设不存在" }
        val kind=ItemKind.valueOf(a.required("kind").uppercase())
        val raw=a.decode<ScheduleSpec>("schedule")
        val schedule=if(kind==ItemKind.ALARM && raw?.time!=null && raw.date==null && raw.recurrence==null) AlarmSchedules.nextOneShot(raw.time!!,services.clock) else raw
        if(kind==ItemKind.TASK && a.string("label")=="习惯") require(schedule?.recurrence!=null) { "习惯需要重复规则，请先询问每周哪几天出现" }
        return ItemDraft(kind, a.required("title"), a.string("detail") ?: "", schedule, a.decode("deadline"), a.decode("reminder"), alarm = a["alarm"]?.jsonObject?.let { decodeAlarm(it, AlarmSpec()) } ?: AlarmSpec(), label = a.string("label") ?: if(kind==ItemKind.TASK && schedule?.recurrence!=null) "习惯" else "", dependsOn = a.decode<Set<String>>("depends_on") ?: emptySet(), durationMinutes = a.decode("duration_minutes"), pinned = a.decode<Boolean>("pinned") ?: false, checklist = a.decode<List<ChecklistEntry>>("checklist") ?: emptyList())
    }
    private fun decodePatch(p: JsonObject, existingAlarm: AlarmSpec = AlarmSpec()): ItemPatch {
        p.allowed("title", "detail", "schedule", "deadline", "reminder", "label", "status", "alarm", "depends_on", "duration_minutes", "pinned", "checklist")
        return ItemPatch(p.string("title"), p.string("detail"), p.patch("schedule"), p.patch("deadline"), p.patch("reminder"), alarm = p["alarm"]?.jsonObject?.let { decodeAlarm(it, existingAlarm) }, label = p.string("label"), status = p.string("status")?.let { require(it in setOf("open", "completed")); ItemStatus.valueOf(it.uppercase()) }, dependsOn = p.decode("depends_on"), durationMinutes = p.patch("duration_minutes"), pinned = p.decode("pinned"), checklist = p.decode("checklist"))
    }
    private fun decodeAlarm(a: JsonObject, existing: AlarmSpec): AlarmSpec {
        a.allowed("play_audio", "vibrate", "headphones_only", "snooze_minutes")
        return existing.copy(playAudio = a["play_audio"]?.jsonPrimitive?.boolean ?: existing.playAudio, vibrate = a["vibrate"]?.jsonPrimitive?.boolean ?: existing.vibrate, headphonesOnly = a["headphones_only"]?.jsonPrimitive?.boolean ?: existing.headphonesOnly, snoozeMinutes = a["snooze_minutes"]?.jsonPrimitive?.int ?: existing.snoozeMinutes)
    }
    private inline fun <reified T> JsonObject.decode(key: String): T? = get(key)?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<T>(it) }
    private inline fun <reified T> JsonObject.patch(key: String): FieldPatch<T> = if (containsKey(key)) FieldPatch.Set(decode<T>(key)) else FieldPatch.Unchanged
    private fun scope(value: String) = EditScope.valueOf(value.uppercase())
    private fun outcome(r: OperationResult) = buildJsonObject { put("status", r.status); put("message", r.message); r.item?.let { put("item", json.encodeToJsonElement(it)) }; r.changeId?.let { put("change_id", it) }; r.errorCode?.let { put("error_code", it) } }
}
internal fun JsonObject.allowed(vararg keys: String) { require(this.keys.all { it in keys }) { "未知字段：${this.keys - keys.toSet()}" } }
internal fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
internal fun JsonObject.required(key: String): String = string(key)?.takeIf { it.isNotBlank() } ?: error("缺少 $key")
internal fun JsonObject.requiredInt(key: String) = get(key)?.jsonPrimitive?.int ?: error("缺少整数 $key")
/** “别取消”“不要改”“先不用删”之类的否定说法不能作为修改授权。 */
internal val NEGATED_CHANGE = Regex("(别|不要|不用|先别|无需|不必|没必要|不想)(帮我|给我|再)?(取消|删除|删掉|移除|关闭|停止|改|挪|调整|推迟|提前)")
