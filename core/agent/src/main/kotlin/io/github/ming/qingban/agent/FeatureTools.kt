package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.serialization.json.*

/** Small feature-specific gateway; item/proposal transactions stay in ToolGateway. */
internal class FeatureTools(private val services: Services) {
    private val json=Json { encodeDefaults=true }
    private val habits=HabitTools(services)
    suspend fun execute(call: Decision.Call, key: String, request: String, remote: Boolean): JsonObject? {
        val a=call.args
        habits.execute(call,key,request)?.let { return it }
        return when(call.tool) {
            "focus_status" -> { a.allowed(); val s=services.focus.tick(); focusResult(s) }
            "focus_control" -> {
                a.allowed("action","mode","minutes","task_id")
                val action=a.required("action")
                require(action in setOf("start","pause","resume","reset","finish","rest"))
                require(!Regex("如何|怎么|介绍|有哪些|有什么").containsMatchIn(request) || Regex("帮我|给我|现在").containsMatchIn(request)) { "询问功能不会启动或修改计时" }
                require(Regex("专注|番茄|计时|休息|暂停|继续|结束|重置").containsMatchIn(request) && Regex("开始|开个|来个|暂停|继续|恢复|结束|停止|重置|休息|帮我|给我").containsMatchIn(request)) { "没有授权计时操作，请先询问用户" }
                val task=a.string("task_id")?.let { services.items.store.get(it) ?: error("关联事项不存在") }
                require(task==null || task.kind!=ItemKind.ALARM && task.status==ItemStatus.OPEN) { "只能关联未完成的事项" }
                val state=services.focus.command(action,a.string("mode") ?: "pomodoro",a["minutes"]?.jsonPrimitive?.int,task?.id,task?.title ?: "自由专注",requestKey=key)
                focusResult(state)
            }
            "focus_settings" -> {
                a.allowed("focus_minutes","rest_minutes","long_rest_minutes","long_rest_every","notify","immersive")
                require(Regex("设置|改|调整|时长|关闭|开启|关掉|打开").containsMatchIn(request)) { "没有授权修改专注设置" }
                require(!Regex("怎么|如何|有什么").containsMatchIn(request) || Regex("帮我|给我|改成|改为|现在").containsMatchIn(request)) { "询问设置方法不会修改配置" }
                val old=services.focus.load().settings
                val next=old.copy(focusMinutes=a["focus_minutes"]?.jsonPrimitive?.int ?: old.focusMinutes,restMinutes=a["rest_minutes"]?.jsonPrimitive?.int ?: old.restMinutes,longRestMinutes=a["long_rest_minutes"]?.jsonPrimitive?.int ?: old.longRestMinutes,longRestEvery=a["long_rest_every"]?.jsonPrimitive?.int ?: old.longRestEvery,notify=a["notify"]?.jsonPrimitive?.boolean ?: old.notify,immersive=a["immersive"]?.jsonPrimitive?.boolean ?: old.immersive)
                focusResult(services.focus.command("settings",settings=next,requestKey=key))
            }
            "navigate" -> {
                a.allowed("page","date","view")
                require(Regex("打开|切|看|查看|展示").containsMatchIn(request)) { "没有要求切换页面" }
                services.ui.open(a.required("page"),a.string("date"),a.string("view"))
                buildJsonObject { put("status","ok"); put("message","已打开对应页面"); put("page",a.required("page")) }
            }
            "list_sounds" -> { a.allowed(); buildJsonObject { put("status","ok"); put("sounds",Json.encodeToJsonElement(services.sounds.list())); put("message","只能选择已导入或缓存的真实铃声；空 ID 恢复系统铃声") } }
            "set_alarm_sound" -> {
                a.allowed("item_id","expected_version","sound_id")
                require(Regex("铃声|音乐|选歌|声音|首歌").containsMatchIn(request)) { "没有授权换铃声" }
                val item=services.items.store.get(a.required("item_id")) ?: error("闹钟不存在")
                require(item.kind==ItemKind.ALARM)
                val id=a.string("sound_id")
                val path=id?.let { services.sounds.path(it) ?: error("铃声不存在，请重新查询已缓存曲目") }
                val result=services.items.update(item.id,a.requiredInt("expected_version"),ItemPatch(alarm=item.alarm.copy(soundPath=path)),EditScope.WHOLE_SERIES,requestKey=key)
                buildJsonObject { put("status",result.status); put("message",result.message); put("item",Json.encodeToJsonElement(result.item)); result.changeId?.let { put("change_id",it) } }
            }
            "get_preferences" -> { a.allowed(); buildJsonObject { put("status","ok"); put("preferences",Json.encodeToJsonElement(services.memory.activePreferences(remote=remote))) } }
            "manage_knowledge" -> {
                a.allowed("operation","source_id")
                val operation=a.required("operation")
                if(operation=="list") buildJsonObject { put("status","ok"); put("operation","list"); put("sources",JsonArray(services.knowledge.store.all().filter { !remote || it.allowRemote }.map { source -> buildJsonObject { put("id",source.id); put("title",source.title); put("version",source.version); put("enabled",source.enabled) } })) }
                else {
                    val source=services.knowledge.store.all().firstOrNull { it.id==a.required("source_id") && (!remote || it.allowRemote) } ?: error("资料不可访问")
                    when(operation) {
                        "enable" -> { require(Regex("启用|采用|重新参考").containsMatchIn(request)); services.knowledge.store.save(source.copy(enabled=true)) }
                        "disable" -> { require(Regex("停用|不用|不再参考").containsMatchIn(request)); services.knowledge.store.save(source.copy(enabled=false)) }
                        "delete" -> { require(Regex("删除|删掉").containsMatchIn(request)); services.knowledge.delete(source.id) }
                        else -> error("未知资料操作")
                    }
                    buildJsonObject { put("status","ok"); put("message","资料状态已更新") }
                }
            }
            else -> null
        }
    }
    private fun focusResult(state: FocusState) = buildJsonObject {
        put("status",if(services.focus.notice.value!=null) "partial" else "ok")
        put("settings",json.encodeToJsonElement(state.settings)); put("run",json.encodeToJsonElement(state.run))
        put("elapsed_ms",state.run?.let { services.focus.elapsed(it) } ?: 0L)
        put("records",Json.encodeToJsonElement(state.records.takeLast(14)))
        val today=java.time.LocalDate.now(services.clock)
        val todayRecords=state.records.filter { java.time.Instant.ofEpochMilli(it.endedAt).atZone(services.clock.zone).toLocalDate()==today }
        put("today_minutes",todayRecords.sumOf { it.millis }/60_000)
        put("today_tomatoes",todayRecords.count { it.mode=="pomodoro" && it.completed })
        put("message",services.focus.notice.value ?: "专注计时状态已更新")
    }
}
