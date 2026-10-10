package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AgentCoordinator(private val services: Services, private val client: ModelClient, private val systemPrompt: String) {
    private val json = Json
    private val completeJson=Json { encodeDefaults=true }
    private val gateway = ToolGateway(services)
    private val mutex = Mutex()
    private val planner = LocalPlanner(services.clock)
    private val mutableSession = MutableStateFlow(AgentSession())
    val session = mutableSession.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    private var loaded = false
    val busy = mutableBusy.asStateFlow()
    suspend fun load() = mutex.withLock { ensureLoaded() }
    suspend fun reloadFromStorage() = mutex.withLock { loaded=false; mutableSession.value=AgentSession(); ensureLoaded() }
    private suspend fun ensureLoaded() { if(loaded) return; services.sessions.read("agent_session")?.let { raw -> runCatching { json.decodeFromString<AgentSession>(raw) }.getOrNull()?.let { mutableSession.value = it } }; loaded = true }
    suspend fun clear() = mutex.withLock { mutableSession.value = AgentSession(); save(); services.sessions.remove("current_proposal") }
    suspend fun send(text: String) = mutex.withLock {
        ensureLoaded()
        val canContinue=continuationCommand(text) && mutableSession.value.pendingUserRequest==null && mutableSession.value.pendingLocal==null
        val turn=if(canContinue) turnToResume() else null
        if(turn!=null) { append(ChatLine("user",text.trim())); runTurn(turn.request,turn) }
        else runTurn(text)
    }
    suspend fun retry(index: Int = -1) = mutex.withLock {
        ensureLoaded()
        turnToResume(index)?.let { runTurn(it.request,it) }
    }
    private fun continuationCommand(text: String) = text.trim().trimEnd('。','！','!','，',',','？','?') in setOf("继续","重试","接着做","继续处理","继续安排","继续这次请求","继续未完成的请求")
    private fun turnToResume(index: Int = -1): PendingAgentTurn? {
        val current=mutableSession.value
        // Old sessions may have incorrectly saved the word "继续" as a new request.
        val selectedIndex=if(index>=0) index else if(current.activeTurn?.let { continuationCommand(it.request) }==true || current.activeTurn==null)
            current.interruptedTurns.indexOfLast { !continuationCommand(it.request) } else -1
        val turn=if(selectedIndex>=0) current.interruptedTurns.getOrNull(selectedIndex) else current.activeTurn
        if(turn!=null && selectedIndex>=0) {
            val others=current.interruptedTurns.filterIndexed { i,_ -> i!=selectedIndex } + listOfNotNull(current.activeTurn?.takeUnless { continuationCommand(it.request) || it.requestId==turn.requestId })
            mutableSession.value=current.copy(interruptedTurns=others.takeLast(5),activeTurn=turn)
        }
        return turn
    }
    private suspend fun runTurn(text: String, resumed: PendingAgentTurn? = null) {
        if(text.isBlank()) return
        ensureLoaded()
        if(resumed==null && text.length > 8_000) { append(ChatLine("assistant", "铭哥，消息最多 8000 字，长内容可以导入资料库。")); save(); return }
        mutableBusy.value = true
        val pendingRequest = if(resumed==null) mutableSession.value.pendingUserRequest else null
        val answering = pendingRequest != null && text.length <= 40 && !Regex("新增|再记|帮我|查询|有什么|取消|删除|记住|以后").containsMatchIn(text)
        val effectiveRequest = if(answering) "$pendingRequest\n用户补充：$text" else text
        if(pendingRequest!=null && !answering) mutableSession.value=mutableSession.value.copy(pausedRequests=(mutableSession.value.pausedRequests+pendingRequest).takeLast(10))
        if(resumed==null) {
            val interrupted=mutableSession.value.interruptedTurns+listOfNotNull(mutableSession.value.activeTurn)
            mutableSession.value = mutableSession.value.copy(pendingUserRequest = null,interruptedTurns=interrupted.takeLast(5))
            append(ChatLine("user", text.trim()))
        }
        val turn=resumed ?: PendingAgentTurn(effectiveRequest,UUID.randomUUID().toString())
        mutableSession.value=mutableSession.value.copy(activeTurn=turn)
        try {
            save()
            val remote = client.available()
            require(remote || resumed==null || (turn.results.isEmpty() && turn.inFlight==null)) { "这次请求已有模型操作进度，请恢复模型配置后继续，避免重复记录" }
            if(remote) remoteTurn(turn) else localTurn(effectiveRequest)
            mutableSession.value=mutableSession.value.copy(activeTurn=null)
        } catch (e: Exception) {
            val changed=mutableSession.value.activeTurn?.results?.any { it["tool_name"]?.jsonPrimitive?.content in setOf("create_item", "update_item", "cancel_item", "delete_item", "write_memory", "apply_proposal","undo_change","focus_control","focus_settings","set_alarm_sound","manage_knowledge") && it["status"]?.jsonPrimitive?.content in setOf("ok", "partial") } == true
            val lastToolError=mutableSession.value.activeTurn?.results?.lastOrNull { it["status"]?.jsonPrimitive?.content=="error" && it["error_code"]?.jsonPrimitive?.content=="invalid_tool_request" }
            val detail=lastToolError?.get("message")?.jsonPrimitive?.content?.take(100)?.let { "。最近一次工具未执行：$it" }.orEmpty()
            append(ChatLine("assistant",if(e is kotlinx.coroutines.CancellationException) "铭哥，已停止继续处理${if(changed) "，已保存的操作保留" else ""}。可以稍后继续这次请求。" else "铭哥，${if(changed) "部分操作已保存，其余尚未完成" else "这次没有完成"}：${e.message?.take(160) ?: "请求失败"}$detail。输入“继续”或点击续接按钮，将沿用原请求和已保存进度。"))
            if(e is kotlinx.coroutines.CancellationException) throw e
        } finally { mutableBusy.value = false; withContext(NonCancellable) { save() } }
    }
    suspend fun approveProposal(id: String, changes: List<ProposedAction>? = null) = mutex.withLock {
        mutableBusy.value = true
        try {
            changes?.let { gateway.editPreview(id,it) }; gateway.approve(id)
            val result = gateway.execute(Decision.Call("apply_proposal", buildJsonObject { put("proposal_id", id) }), "approve:$id", "确认该方案", client.available())
            append(ChatLine("assistant", when(result["status"]?.jsonPrimitive?.content) { "ok" -> "铭哥，已按确认方案统一保存。"; "partial" -> "铭哥，事项已统一保存，部分提醒需要检查权限。"; else -> "铭哥，方案未保存：${result["message"]?.jsonPrimitive?.content ?: "请检查方案"}" },changeId=result["change_id"]?.jsonPrimitive?.content))
            if(result["status"]?.jsonPrimitive?.content in setOf("ok", "partial")) mutableSession.value = mutableSession.value.copy(proposalId = null)
        } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; append(ChatLine("assistant", "铭哥，方案没有执行：${e.message}")) }
        finally { mutableBusy.value = false; withContext(NonCancellable) { save() } }
    }
    suspend fun proposal() = gateway.pendingProposal()
    suspend fun resumeRequest(index: Int) = mutex.withLock {
        val request=mutableSession.value.pausedRequests.getOrNull(index) ?: return@withLock
        mutableSession.value.pendingUserRequest?.let { old -> mutableSession.value=mutableSession.value.copy(pausedRequests=mutableSession.value.pausedRequests+old) }
        mutableSession.value=mutableSession.value.copy(pendingUserRequest=request,pausedRequests=mutableSession.value.pausedRequests.filterIndexed { i,_ -> i!=index }.takeLast(10))
        append(ChatLine("assistant","铭哥，继续这件事：$request。请补充刚才需要确认的信息。")); save()
    }
    suspend fun undo(changeId: String) = mutex.withLock { val result = services.undo(changeId); append(ChatLine("assistant", "铭哥，${result.message}。")); save() }
    suspend fun resumeLocal(index: Int) = mutex.withLock {
        val draft = mutableSession.value.pausedLocal.getOrNull(index) ?: return@withLock
        mutableSession.value = mutableSession.value.copy(pendingLocal = draft, pausedLocal = mutableSession.value.pausedLocal.filterIndexed { i, _ -> i != index })
        append(ChatLine("assistant", "铭哥，继续安排“${draft.title}”：几点${if(draft.kind == "alarm") "响铃" else "提醒"}？")); save()
    }
    private suspend fun localTurn(text: String) {
        services.focus.load()
        if(!Regex("备忘|笔记|原文|想法").containsMatchIn(text)) {
            val blocks=runCatching { PlanBlocks.split(ItemDraft(ItemKind.TASK,"分时段安排",text,ScheduleSpec(date=planner.date(text)))) }
            if(blocks.isFailure) {
                mutableSession.value=mutableSession.value.copy(pendingUserRequest=text)
                append(ChatLine("assistant","铭哥，这几个时段具体安排在哪一天？",listOf("今天","明天"))); return
            }
            blocks.getOrNull()?.let { drafts ->
                val actions=drafts.map { d -> ProposedAction("create_item",buildJsonObject { put("kind","task"); put("title",d.title); put("detail",d.detail); put("schedule",json.encodeToJsonElement(d.schedule)); put("duration_minutes",d.durationMinutes!!); put("label","学习工作") }) }
                val result=gateway.execute(Decision.Call("propose_changes",buildJsonObject { put("summary","拆成 ${drafts.size} 个独立时段，请确认日期、内容与时间"); put("changes",json.encodeToJsonElement(actions)) }),"local-plan:${mutableSession.value.activeTurn?.requestId}",text,false)
                if(result["status"]?.jsonPrimitive?.content=="approval_pending") { mutableSession.value=mutableSession.value.copy(proposalId=result["proposal_id"]!!.jsonPrimitive.content); append(ChatLine("assistant","铭哥，已拆为 ${drafts.size} 个独立任务，确认后保存。",listOf("查看并确认方案"))) }
                else append(ChatLine("assistant","铭哥，方案未生成：${result["message"]?.jsonPrimitive?.content}"))
                return
            }
        }
        when(val feature=LocalFeatureRouter.decide(text,services)) {
            is Decision.Ask -> { mutableSession.value=mutableSession.value.copy(pendingUserRequest=text); append(ChatLine("assistant",feature.question,feature.options)); return }
            is Decision.Call -> {
                val result=gateway.execute(feature,"local:${mutableSession.value.activeTurn?.requestId}:${feature.tool}",text,false)
                if(feature.tool=="delete_item") {
                    if(result["status"]?.jsonPrimitive?.content=="approval_pending") { mutableSession.value=mutableSession.value.copy(proposalId=result["proposal_id"]!!.jsonPrimitive.content); append(ChatLine("assistant","铭哥，请核对删除范围，确认后执行，可以撤销。",listOf("查看并确认方案"))) }
                    else append(ChatLine("assistant","铭哥，未删除：${result["message"]?.jsonPrimitive?.content}"))
                    return
                }
                if(feature.tool in setOf("habit_status","habit_manage","gentle_settings","review_overdue")) {
                    val rows=result["habits"]?.jsonArray
                    val summary=rows?.joinToString("\n") { row -> val r=row.jsonObject; val h=r["habit"]!!.jsonObject; "${h["title"]?.jsonPrimitive?.content}：近7天 ${r["done_last_7"]}/${r["planned_last_7"]}，连续 ${r["streak"]} 个计划日" } ?: result["message"]?.jsonPrimitive?.content.orEmpty()
                    append(ChatLine("assistant","铭哥，${if(result["status"]?.jsonPrimitive?.content=="error") "未执行：" else ""}${summary.ifBlank { "还没有习惯，可以先建立一个。" }}")); return
                }
                val run=result["run"]?.takeUnless { it==JsonNull }?.jsonObject
                val settings=result["settings"]?.jsonObject
                val status=when(run?.get("status")?.jsonPrimitive?.content) { "running" -> "运行中"; "paused" -> "已暂停"; "finished" -> "已结束"; else -> "未开始" }
                val elapsed=result["elapsed_ms"]?.jsonPrimitive?.long ?: 0L
                val remaining=((run?.get("targetMillis")?.jsonPrimitive?.long ?: 0L)-elapsed).coerceAtLeast(0)/1000
                val summary=if(result["status"]?.jsonPrimitive?.content=="error") "未执行：${result["message"]?.jsonPrimitive?.content}" else if(feature.tool=="navigate") "已打开对应页面。" else "${result["message"]?.jsonPrimitive?.content}。${if(run!=null) "${run["title"]?.jsonPrimitive?.content} · $status · "+if(run["mode"]?.jsonPrimitive?.content=="stopwatch") "已计时 ${elapsed/60_000} 分钟" else "剩余 ${remaining/60} 分 ${(remaining%60)} 秒" else "暂无正在运行的计时"}；专注 ${settings?.get("focusMinutes")?.jsonPrimitive?.content} 分钟，短休息 ${settings?.get("restMinutes")?.jsonPrimitive?.content} 分钟。今天完成 ${result["today_tomatoes"]?.jsonPrimitive?.content} 个番茄。"
                append(ChatLine("assistant","铭哥，$summary")); return
            }
            else -> Unit
        }
        if(Regex("挪到|改到|完成刚才|完成了|取消刚才").containsMatchIn(text)) {
            val day=planner.date(text)
            val candidates=services.items.store.all().filter { it.status==ItemStatus.OPEN && (it.id==mutableSession.value.lastItemId && "刚才" in text || it.title in text) }
            if(candidates.size==1) {
                val item=candidates.single()
                if(item.schedule?.recurrence!=null) {
                    if("完成" in text && day!=null) { val result=services.items.update(item.id,item.version,ItemPatch(status=ItemStatus.COMPLETED),EditScope.THIS_OCCURRENCE,day.toString()); append(ChatLine("assistant","铭哥，${result.message}：${item.title} · 仅 $day 这次，后续继续出现。",changeId=result.changeId)); return }
                    mutableSession.value=mutableSession.value.copy(pendingUserRequest=text)
                    append(ChatLine("assistant","铭哥，这是一组重复事项，具体操作哪一次？",listOf("今天","明天"))); return
                }
                val p=when { "取消" in text -> ItemPatch(status=ItemStatus.CANCELLED); "完成" in text -> ItemPatch(status=ItemStatus.COMPLETED); day!=null -> ItemPatch(schedule=FieldPatch.Set((item.schedule ?: ScheduleSpec()).copy(date=day))); else -> null }
                if(p!=null) { val result=services.items.update(item.id,item.version,p); append(ChatLine("assistant","铭哥，${result.message}：${item.title}。",changeId=result.changeId)); return }
            } else if(candidates.size>1) { append(ChatLine("assistant","铭哥，找到了多件同名事项，请先在列表选择具体那一件。")); return }
        }
        Regex("^先(.+?)[，,]?再(.+)$").matchEntire(text.trim())?.let { match ->
            if(!Regex("点|明天|今天|后天|周[一二三四五六日天]").containsMatchIn(text)) {
                val result = services.items.createSequence(match.groupValues.drop(1).map { ItemDraft(ItemKind.TASK, it.trim().trimEnd('，', ',')) })
                append(ChatLine("assistant", "铭哥，${result.message}，已保留先后顺序。", changeId = result.changeId)); return
            }
        }
        val previous = mutableSession.value.pendingLocal
        when(val action = planner.parse(text, mutableSession.value.pendingLocal)) {
            is LocalAction.Ask -> { if(previous != null && previous.title != action.draft.title) pause(previous); mutableSession.value = mutableSession.value.copy(pendingLocal = action.draft); append(ChatLine("assistant", action.question, action.options)) }
            is LocalAction.Create -> {
                val d = action.draft
                if(previous != null && previous.title != d.title) pause(previous)
                val schedule = if(d.date != null || d.time != null || d.period != null || d.recurrence!=null) ScheduleSpec(d.date, d.time, d.period,recurrence=d.recurrence) else null
                val preferred = services.memory.activePreferences().firstOrNull { it.key=="activity.preferred_period" && it.scope==d.title }?.value
                val effectiveSchedule=if(schedule?.time==null && schedule?.period==null && preferred!=null) (schedule ?: ScheduleSpec()).copy(period=preferred) else schedule
                val result = services.items.create(ItemDraft(ItemKind.valueOf(d.kind.uppercase()), d.title, schedule = effectiveSchedule, deadline = d.deadlineDate?.let(::DeadlineSpec), reminder = if(d.needsReminder && d.time != null) ReminderSpec(offsetMinutes = 0) else null,alarm=d.alarm,label=if(d.kind=="task" && d.recurrence!=null) "习惯" else ""))
                if(result.status!="error") mutableSession.value=mutableSession.value.copy(lastItemId=result.item?.id)
                if(result.status!="error" && (d.time!=null || d.period!=null)) result.item?.let { services.memory.recordChoice(it) }
                mutableSession.value = mutableSession.value.copy(pendingLocal = if(result.status == "error") d else null)
                append(ChatLine("assistant", if(result.status == "error") "铭哥，未保存：${result.message}" else "铭哥，${result.message}：${d.title}${d.date?.let { " · $it" } ?: ""}${d.time?.let { " $it" } ?: ""}${if(d.alarm.headphonesOnly) " · 仅耳机，未连接时振动" else ""}${d.recurrence?.let { " · ${RecurrenceRules.label(it)}" } ?: ""}${if(effectiveSchedule!=schedule) " · 按已确认偏好推荐${periodLabel(preferred)}" else ""}。", changeId = result.changeId))
            }
            is LocalAction.Query -> {
                val items = services.items.store.all().filter { ScheduleEngine.occursOn(it, LocalDate.parse(action.date)) }
                append(ChatLine("assistant", "铭哥，${action.date}${if(items.isEmpty()) " 暂无安排。" else "：\n" + items.joinToString("\n") { "${it.schedule?.time ?: periodLabel(it.schedule?.period)} ${it.title}" }}"))
            }
            LocalAction.Undo -> { val result = services.undo(); append(ChatLine("assistant", "铭哥，${if(result.status == "error") result.message else "已撤销刚才的操作"}。")) }
            is LocalAction.Unsupported -> append(ChatLine("assistant", action.message))
        }
    }
    private suspend fun remoteTurn(turn: PendingAgentTurn) {
        val text=turn.request
        val results = turn.results.toMutableList()
        val sources = mutableSetOf<String>()
        val sourceIds = mutableSetOf<String>()
        val sourceVersions=mutableMapOf<String,Int>()
        val requestId = turn.requestId
        suspend fun execute(call: Decision.Call) = withContext(NonCancellable) {
            mutableSession.value=mutableSession.value.copy(activeTurn=PendingAgentTurn(text,requestId,results.toList(),ProposedAction(call.tool,call.args))); save()
            val rawResult=gateway.execute(call,"$requestId:${call.tool}:${call.args.hashCode()}",text,true)
            val result=JsonObject(rawResult+mapOf("tool_name" to JsonPrimitive(call.tool),"tool_args" to call.args))
            results+=result
            mutableSession.value=mutableSession.value.copy(activeTurn=PendingAgentTurn(text,requestId,results.toList()))
            services.sessions.write("agent_progress",JsonArray(results).toString()); save()
            result
        }
        turn.inFlight?.let { execute(Decision.Call(it.tool,it.args)) }
        suspend fun showPendingProposal(result: JsonObject): Boolean {
            if(result["status"]?.jsonPrimitive?.content != "approval_pending") return false
            val id=result["proposal_id"]?.jsonPrimitive?.content ?: return false
            val proposal=gateway.pendingProposal()
            if(proposal==null || proposal.id != id || services.clock.millis() >= proposal.expiresAt) return false
            mutableSession.value=mutableSession.value.copy(proposalId=id)
            append(ChatLine("assistant", "铭哥，先看一下这个安排，确认后才会保存：\n${proposal.summary}", listOf("查看并确认方案")))
            return true
        }
        if(results.any { showPendingProposal(it) }) return
        var repairs = 0
        var calls=if(turn.inFlight!=null) 1 else 0
        suspend fun repair(code: String,message: String) {
            repairs++
            results+=buildJsonObject {
                put("status","error"); put("error_code",code); put("tool_name","model_output")
                put("message","修复决策：$message。只输出一个对象：{\"mode\":\"say\",\"text\":\"...\"} 或 {\"mode\":\"ask\",\"question\":\"...\",\"options\":[]} 或 {\"mode\":\"call\",\"tool\":\"工具名\",\"args\":{}}。缩短内容，批量安排用 propose_changes，禁止直接声称已保存。")
            }
            mutableSession.value=mutableSession.value.copy(activeTurn=PendingAgentTurn(text,requestId,results.toList()))
            save()
            if(repairs > MAX_FORMAT_REPAIRS) error("模型连续未返回可执行决策：$message")
        }
        // Format repair must not consume the slot needed to finish after the tool budget.
        repeat(MAX_TOOL_CALLS+MAX_FORMAT_REPAIRS+1) {
            val eligibleSources=services.knowledge.store.all().filter { source -> source.enabled && source.allowRemote && (source.validUntil?.let { it>=LocalDate.now(services.clock).toString() } ?: true) && (source.validFrom?.let { it<=LocalDate.now(services.clock).toString() } ?: true) }.associateBy { it.id }
            for(index in results.indices) {
                val result=results[index]
                if(result["tool_name"]?.jsonPrimitive?.content=="search_knowledge") {
                    val safe=result["sources"]?.jsonArray?.filter { hit -> val source=hit.jsonObject; val id=source["sourceId"]?.jsonPrimitive?.content; id!=null && eligibleSources[id]?.let { it.version==source["version"]?.jsonPrimitive?.int }==true }
                    if(safe!=null) results[index]=JsonObject(result+("sources" to JsonArray(safe)))
                }
            }
            sources.clear(); sourceIds.clear(); sourceVersions.clear()
            results.forEach { result -> result["sources"]?.jsonArray?.forEach { element ->
                val source=element.jsonObject; val id=source["sourceId"]?.jsonPrimitive?.content ?: return@forEach
                val version=source["version"]?.jsonPrimitive?.int ?: 1
                sourceIds+=id; sourceVersions[id]=version; sources+="${source["title"]?.jsonPrimitive?.content.orEmpty()}（v$version）"
            } }
            val context = buildJsonObject {
                put("runtime_context", buildJsonObject { put("local_date", LocalDate.now(services.clock).toString()); put("local_time", java.time.LocalTime.now(services.clock).toString()); put("timezone", services.clock.zone.id); put("user_display_name", "铭哥") })
                put("confirmed_preferences", json.encodeToJsonElement(services.memory.activePreferences(remote=true)))
                put("current_user_request", text)
                put("recent_tool_results", JsonArray(results))
                put("execution_budget",buildJsonObject {
                    put("remaining_tool_calls",(MAX_TOOL_CALLS-calls).coerceAtLeast(0))
                    put("instruction","多日或多项安排应合成一次 propose_changes。缺可用时间、学习量或耗时先 ask，不编造。不重复相同查询或同一失败参数；工具返回错误时修正参数或向用户说明。次数用尽时用 say 说明实际进度，或 ask 澄清；下一步会保存供继续。")
                })
                put("available_items", JsonArray(services.items.store.all().filter { it.status == ItemStatus.OPEN }.take(12).map { item -> buildJsonObject { put("id", item.id); put("version", item.version); put("title", item.title); put("kind", item.kind.name.lowercase()); put("schedule", json.encodeToJsonElement(item.schedule)); put("deadline", json.encodeToJsonElement(item.deadline)); put("delivery", item.delivery.name.lowercase()) } }))
                put("habit_rules",json.encodeToJsonElement(services.habits.load()))
                put("pending_daily_review",json.encodeToJsonElement(services.review.pending.value))
                put("pending_proposal", gateway.pendingProposal()?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                put("pending_question_draft", mutableSession.value.pendingLocal?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                val focus=services.focus.tick()
                put("focus",buildJsonObject { put("settings",completeJson.encodeToJsonElement(focus.settings)); put("run",completeJson.encodeToJsonElement(focus.run)); put("elapsed_ms",focus.run?.let { services.focus.elapsed(it) } ?: 0L) })
            }
            var historySize=0
            val safeHistory=mutableSession.value.history.asReversed().filter { line -> line.sources.isEmpty() || (line.sourceIds.isNotEmpty() && line.sourceIds.all { id -> eligibleSources[id]?.let { it.version==line.sourceVersions[id] }==true }) }.take(16).takeWhile { line -> historySize+=line.text.length; historySize<=6000 }.asReversed()
            val raw = try { client.decide(AgentRequest(PromptPreset.render(systemPrompt,services.clock),safeHistory,context)) }
                catch(e: ModelOutputException) { repair(e.errorCode,e.message.orEmpty()); return@repeat }
            val decision = try { DecisionParser.parse(raw) } catch (e: DecisionFormatException) {
                repair(e.errorCode,e.message.orEmpty())
                return@repeat
            }
            when(decision) {
                is Decision.Say -> {
                    val actualWrite = results.any { it["tool_name"]?.jsonPrimitive?.content in setOf("create_item", "update_item", "cancel_item", "delete_item", "undo_change", "write_memory", "apply_proposal","habit_manage","gentle_settings","focus_control","focus_settings","navigate","set_alarm_sound","manage_knowledge") && it["operation"]?.jsonPrimitive?.content!="list" && it["status"]?.jsonPrimitive?.content in setOf("ok", "partial") }
                    val misleading = Regex("已(?:设置|保存|安排|删除|完成|改到|记住|撤销|开始|暂停|启动|打开|切换)").containsMatchIn(decision.text) && !actualWrite
                    val incomplete = results.lastOrNull { it["status"]?.jsonPrimitive?.content == "partial" && it["tool_name"]?.jsonPrimitive?.content in setOf("create_item", "update_item", "apply_proposal","habit_manage","gentle_settings","focus_control","focus_settings","set_alarm_sound") }
                    val response = if(misleading) "铭哥，本轮还没有成功执行操作。请补充信息，或通过事项卡片手动处理。" else if(incomplete != null) "铭哥，${incomplete["message"]?.jsonPrimitive?.content ?: "部分操作已保存，仍有步骤未完成"}。请检查对应事项的提醒状态。" else decision.text
                    append(ChatLine("assistant", response, sources = sources.toList(), changeId = results.lastOrNull()?.get("change_id")?.jsonPrimitive?.content,sourceIds=sourceIds.toList(),sourceVersions=sourceVersions)); return
                }
                is Decision.Ask -> { mutableSession.value = mutableSession.value.copy(pendingUserRequest = text); append(ChatLine("assistant", decision.question, decision.options, sources.toList(),sourceIds=sourceIds.toList(),sourceVersions=sourceVersions)); return }
                is Decision.Call -> {
                    if(calls >= MAX_TOOL_CALLS) {
                        mutableSession.value=mutableSession.value.copy(activeTurn=PendingAgentTurn(text,requestId,results.toList(),ProposedAction(decision.tool,decision.args)))
                        save()
                        error("本轮已调用 $MAX_TOOL_CALLS 次工具，下一步已保存；请继续剩余事项")
                    }
                    calls++
                    val result = execute(decision)
                    result["sources"]?.jsonArray?.forEach { source -> source.jsonObject["title"]?.jsonPrimitive?.content?.let { title -> sources.add("$title（v${source.jsonObject["version"]?.jsonPrimitive?.content ?: "1"}）") }; source.jsonObject["sourceId"]?.jsonPrimitive?.content?.let { id -> sourceIds.add(id); sourceVersions[id]=source.jsonObject["version"]?.jsonPrimitive?.int ?: 1 } }
                    if(showPendingProposal(result)) return
                    if(results.size>=3 && results.takeLast(3).all { it==result }) error("模型连续重复同一步且没有新进展：${decision.tool}。需要更正参数、询问缺项或结束说明")
                }
            }
        }
        error("已达到本轮处理上限，原话与进度已保留")
    }
    private fun append(line: ChatLine) { mutableSession.value = mutableSession.value.copy(history = (mutableSession.value.history + line).takeLast(80)) }
    private fun pause(draft: LocalDraft) { mutableSession.value = mutableSession.value.copy(pausedLocal = (mutableSession.value.pausedLocal + draft).takeLast(10)) }
    private suspend fun save() { services.sessions.write("agent_session", json.encodeToString(mutableSession.value)) }
    private fun periodLabel(period: String?) = when(period) { "morning" -> "上午"; "afternoon" -> "下午"; "evening" -> "晚上"; else -> "待办" }
    private companion object {
        const val MAX_TOOL_CALLS=6
        const val MAX_FORMAT_REPAIRS=2
    }
}
