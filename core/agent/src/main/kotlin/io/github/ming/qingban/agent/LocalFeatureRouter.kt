package io.github.ming.qingban.agent

import io.github.ming.qingban.domain.*
import kotlinx.serialization.json.*

/** Deterministic commands for timer/navigation are available even without an API key. */
internal object LocalFeatureRouter {
    private fun minutes(text: String): Int? = Regex("([0-9零一二三四五六七八九十两]{1,3})\\s*分钟").find(text)?.groupValues?.get(1)?.let { raw ->
        raw.toIntOrNull() ?: run {
            val digits="零一二三四五六七八九"
            fun digit(s: String)=if(s=="两") 2 else digits.indexOf(s.firstOrNull() ?: '零').coerceAtLeast(0)
            if('十' in raw) raw.split('十').let { (if(it[0].isEmpty()) 1 else digit(it[0]))*10+digit(it.getOrElse(1) { "" }) } else digit(raw)
        }
    }
    suspend fun decide(text: String, services: Services): Decision? {
        if(Regex("删除|删掉|移除|清空").containsMatchIn(text) && !Regex("资料|偏好|打卡|对话").containsMatchIn(text) && (!("习惯" in text) || services.items.store.all().any { !it.deleted && it.title in text })) {
            val matches=services.items.store.all().filter { !it.deleted && it.title in text }
            if(matches.size!=1) return Decision.Ask("铭哥，要删除哪一件事项？可以在全部事项里多选删除。",matches.take(3).map { it.title })
            val item=matches.single(); val day=LocalPlanner(services.clock).date(text)
            val whole=Regex("整个|全部|所有|长期|以后").containsMatchIn(text)
            if(item.schedule?.recurrence!=null && !whole && day==null) return Decision.Ask("铭哥，只删除今天这次，还是整个系列？",listOf("仅今天这次","整个系列"))
            return Decision.Call("delete_item",buildJsonObject { put("item_id",item.id); put("expected_version",item.version); put("scope",if(item.schedule?.recurrence!=null && !whole) "this_occurrence" else "whole_series"); day?.let { put("occurrence_date",it) } })
        }
        if(Regex("日终|超期|逾期").containsMatchIn(text) && Regex("建议|回顾|检查|调整").containsMatchIn(text)) return Decision.Call("review_overdue",buildJsonObject {})
        val taskHabit=Regex("习惯").containsMatchIn(text) && !Regex("打卡|督促|监督|习惯页").containsMatchIn(text) && (Regex("建立|创建|新增|养成|安排|待办|任务|提醒|每天|每周|工作日|周末").containsMatchIn(text) || services.items.store.all().any { !it.deleted && it.isHabitTask && it.title in text })
        if(!taskHabit && Regex("习惯|打卡|督促|监督").containsMatchIn(text)) {
            if(Regex("温和.*(?:督促|监督)|(?:督促|监督).*温和").containsMatchIn(text) && Regex("开启|启用|打开|关闭|停用|关掉").containsMatchIn(text)) return Decision.Call("gentle_settings",buildJsonObject { put("enabled",!Regex("关闭|停用|关掉").containsMatchIn(text)) })
            if(Regex("打开|查看.*习惯页").containsMatchIn(text)) return Decision.Call("navigate",buildJsonObject { put("page","habits") })
            if(Regex("查询|进度|统计|有哪些|有什么|情况").containsMatchIn(text)) return Decision.Call("habit_status",buildJsonObject {})
            val habits=services.habits.load().habits.filter { it.title in text }
            if(Regex("打卡|暂停|恢复.*习惯|继续.*习惯|撤销.*习惯|删除.*习惯").containsMatchIn(text) && !Regex("建立|创建|养成|新增").containsMatchIn(text)) {
                if(habits.size!=1) return Decision.Ask("铭哥，要操作哪个习惯？",habits.take(3).map { it.title })
                val h=habits.single(); val op=when { "删除" in text -> "delete"; Regex("撤销|取消").containsMatchIn(text) -> "uncheck"; "暂停" in text -> "pause"; Regex("继续|恢复").containsMatchIn(text) -> "resume"; else -> "check" }
                return Decision.Call("habit_manage",buildJsonObject { put("operation",op); put("habit_id",h.id); put("expected_version",h.version) })
            }
            if(Regex("建立|创建|新增|养成").containsMatchIn(text)) {
                val title=text.replace(Regex("帮我|建立|创建|新增|养成|一个|每天|每日|习惯|打卡"),"").trim(' ','，','。','：',':').trimEnd('的')
                if(title.isBlank() || title.length>80 || Regex("提醒|周|点|时|分").containsMatchIn(title)) return Decision.Ask("铭哥，习惯叫什么、每周哪几天？需要提醒的话也告诉我时间。",emptyList())
                return Decision.Call("habit_manage",buildJsonObject { put("operation","create"); put("title",title); put("weekdays",Json.encodeToJsonElement((1..7).toSet())) })
            }
            return Decision.Ask("铭哥，可以说“建立每天阅读习惯”“阅读习惯打卡”，或打开习惯页设置。",listOf("打开习惯页","查询习惯进度"))
        }
        val focus=Regex("专注|番茄|计时|秒表|(?:长|短)?休息.*分钟").containsMatchIn(text)
        if(focus || services.focus.state.value.run?.status in setOf("running","paused","finished") && Regex("^(暂停|继续|恢复|结束|停止|重置|开始休息)").containsMatchIn(text.trim())) {
            if(Regex("状态|统计|多少|还剩|剩余").containsMatchIn(text)) return Decision.Call("focus_status",buildJsonObject {})
            if(Regex("改|调整|设.*分钟|时长").containsMatchIn(text)) {
                val duration=minutes(text) ?: return Decision.Ask("铭哥，要设置多少分钟？",listOf("25 分钟","45 分钟","5 分钟"))
                return Decision.Call("focus_settings",buildJsonObject { put(if("长休息" in text) "long_rest_minutes" else if("休息" in text) "rest_minutes" else "focus_minutes",duration) })
            }
            val action=when { "重置" in text -> "reset"; Regex("结束|停止").containsMatchIn(text) -> "finish"; "暂停" in text -> "pause"; Regex("继续|恢复").containsMatchIn(text) -> "resume"; "开始休息" in text -> "rest"; Regex("开始|开个|来个|帮我|给我").containsMatchIn(text) -> "start"; else -> null }
            if(action!=null) {
                val candidates=services.items.store.all().filter { it.kind!=ItemKind.ALARM && it.status==ItemStatus.OPEN && it.title in text }
                if(candidates.size>1) return Decision.Ask("铭哥，这次专注关联哪件事？",candidates.take(3).map { it.title })
                return Decision.Call("focus_control",buildJsonObject { put("action",action); put("mode",if(Regex("正计时|秒表").containsMatchIn(text)) "stopwatch" else "pomodoro"); minutes(text)?.let { put("minutes",it) }; candidates.singleOrNull()?.let { put("task_id",it.id) } })
            }
        }
        if(Regex("打开|切|看.*(?:日历|视图)").containsMatchIn(text)) {
            val page=when { Regex("日历|周视图|月视图|三日视图|日视图").containsMatchIn(text) -> "calendar"; "番茄" in text || "计时" in text -> "focus"; "闹钟" in text -> "alarm"; "备忘" in text -> "memo"; "资料库" in text -> "library"; "偏好" in text -> "memory"; "今天" in text -> "today"; else -> null }
            if(page!=null) return Decision.Call("navigate",buildJsonObject { put("page",page); if(page=="calendar") {
                val view=when { "三日" in text || "三天" in text -> "three_day"; "周" in text -> "week"; "月" in text -> "month"; "日视图" in text -> "day"; else -> null }; view?.let { put("view",it) }
                LocalPlanner(services.clock).date(text)?.let { put("date",it) }
            } })
        }
        return null
    }
}
