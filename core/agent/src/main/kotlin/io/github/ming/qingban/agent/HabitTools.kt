package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Habit rules stay deterministic and offline; a model only supplies validated commands. */
internal class HabitTools(private val services: Services) {
    private val json=Json { encodeDefaults=true }
    suspend fun execute(call: Decision.Call,key: String,request: String): JsonObject? {
        val a=call.args
        return when(call.tool) {
            "habit_status" -> {
                a.allowed("query"); val state=services.habits.load(); val q=a.string("query").orEmpty()
                buildJsonObject { put("status","ok"); put("local_date",LocalDate.now(services.clock).toString()); put("gentle",state.gentle); put("habits",JsonArray(state.habits.filter { q.isBlank() || q in it.title }.map { h ->
                    val p=HabitRules.progress(h,state,LocalDate.now(services.clock))
                    buildJsonObject { put("habit",json.encodeToJsonElement(h)); put("done_last_7",p.done); put("planned_last_7",p.planned); put("streak",p.streak); put("today_done",p.todayDone) }
                })); put("message","已查询本地习惯进度；打卡代表用户记录，不证明实际行为") }
            }
            "habit_manage" -> {
                a.allowed("operation","habit_id","expected_version","title","weekdays","time","remind","date")
                require(!Regex("如何|怎么|是不是|是否|了吗|有哪些|有什么").containsMatchIn(request) || Regex("帮我|现在|改成").containsMatchIn(request)) { "询问习惯不会改动打卡或规则" }
                val op=a.required("operation")
                val old=if(op=="create") null else services.habits.load().habits.firstOrNull { it.id==a.required("habit_id") } ?: error("习惯不存在，请先查询")
                val message=when(op) {
                    "delete" -> { require(Regex("删除|删掉").containsMatchIn(request)) { "删除习惯需要明确要求" }; services.habits.delete(old!!.id,a.requiredInt("expected_version"),key); "已删除习惯和打卡记录：${old.title}" }
                    "check","uncheck" -> {
                        require(if(op=="check") Regex("打卡|已完成|做完|完成了").containsMatchIn(request) else Regex("撤销|取消|清除|没完成|未完成").containsMatchIn(request)) { "没有明确授权更改打卡" }
                        val date=a.string("date") ?: LocalDate.now(services.clock).toString()
                        services.habits.check(old!!.id,date,op=="check",key); "${if(op=="check") "已打卡" else "已撤销打卡"}：${old.title} · $date"
                    }
                    "create","update","pause","resume" -> {
                        require(Regex("创建|新增|建立|养成|添加|改|设置|提醒|暂停|停用|关闭|继续|恢复|启用").containsMatchIn(request)) { "请明确要建立、修改或暂停哪个习惯" }
                        require(op!="pause" || Regex("暂停|停用|关闭").containsMatchIn(request))
                        require(op!="resume" || Regex("继续|恢复|启用").containsMatchIn(request))
                        val h=services.habits.save(old?.id,if(old!=null) a.requiredInt("expected_version") else null,a.string("title") ?: old?.title ?: error("缺少习惯名称"),a["weekdays"]?.let { json.decodeFromJsonElement<Set<Int>>(it) } ?: old?.weekdays ?: (1..7).toSet(),if("time" in a) a.string("time") else old?.time,a["remind"]?.jsonPrimitive?.boolean ?: old?.remind ?: false,when(op) { "pause" -> false; "resume" -> true; else -> old?.enabled ?: true },key)
                        "${when(op) { "create" -> "已创建习惯"; "pause" -> "已暂停习惯，记录保留"; "resume" -> "已继续习惯"; else -> "已更新习惯" }}：${h.title}${if(h.remind) " · ${h.time} 轻提醒" else " · 未设置定时提醒"}"
                    }
                    else -> error("未知习惯操作")
                }
                buildJsonObject { put("status",if(services.habits.notice.value==null) "ok" else "partial"); put("message",message+(services.habits.notice.value?.let { "；$it" } ?: "")); put("state",json.encodeToJsonElement(services.habits.state.value)) }
            }
            "gentle_settings" -> {
                a.allowed("enabled")
                require(!Regex("怎么|如何|是否|是不是").containsMatchIn(request) || Regex("帮我|现在|改成").containsMatchIn(request)) { "询问设置方法不会开启监督，请明确说开启或关闭" }
                require(Regex("督促|监督").containsMatchIn(request) && Regex("开启|启用|打开|关闭|停用|关掉|帮我").containsMatchIn(request)) { "需要明确授权开启或关闭温和督促" }
                val enabled=a["enabled"]?.jsonPrimitive?.boolean ?: error("缺少 enabled")
                services.habits.gentle(enabled)
                buildJsonObject { put("status",if(services.habits.notice.value==null) "ok" else "partial"); put("message",if(enabled) "已开启本地温和督促，每天最多 2 次追加提醒" else "已关闭温和督促，习惯与闹钟保留") }
            }
            else -> null
        }
    }
}
