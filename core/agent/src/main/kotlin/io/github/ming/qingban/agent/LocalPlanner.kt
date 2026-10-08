package io.github.ming.qingban.agent

import java.time.Clock
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import io.github.ming.qingban.domain.AlarmSpec
import io.github.ming.qingban.domain.RecurrenceSpec
import io.github.ming.qingban.domain.RecurrenceRules

sealed interface LocalAction {
    data class Create(val draft: LocalDraft) : LocalAction
    data class Ask(val question: String, val draft: LocalDraft, val options: List<String> = emptyList()) : LocalAction
    data class Query(val date: String) : LocalAction
    data object Undo : LocalAction
    data class Unsupported(val message: String) : LocalAction
}
class LocalPlanner(private val clock: Clock) {
    fun parse(text: String, pending: LocalDraft?): LocalAction {
        if(text.trim().trimEnd('？', '?', '。') in listOf("你好", "嗨", "在吗", "你能做什么", "帮助", "怎么使用")) return LocalAction.Unsupported("铭哥，我在。可以说“明天买牛奶”来记事，或说“明早叫我起床”，我会接着问时间。")
        val time = time(text)
        if(pending?.pendingField=="weekdays" && text.length<24) {
            val weekdays=when { Regex("每天|每日").containsMatchIn(text) -> (1..7).toSet(); Regex("工作日|周一至周五").containsMatchIn(text) -> (1..5).toSet(); "周末" in text -> setOf(6,7); else -> Regex("(?:周|星期)([一二三四五六日天])").findAll(text).map { "一二三四五六日".indexOf(it.groupValues[1].replace("天","日"))+1 }.toSet() }
            if(weekdays.isNotEmpty()) {
                val answer=pending.copy(recurrence=pending.recurrence?.copy(weekdays=weekdays),pendingField="time",time=time ?: pending.time)
                return if((answer.kind=="alarm" || answer.needsReminder) && answer.time==null) LocalAction.Ask("铭哥，这几天几点响铃？",answer) else LocalAction.Create(answer)
            }
            if(time!=null || Regex("先记|暂不提醒|还没想好").containsMatchIn(text)) return LocalAction.Ask("铭哥，这个长期习惯每周哪几天出现？",pending.copy(time=time ?: pending.time),listOf("每天","周一至周五","周末"))
        }
        if (pending != null && (time != null || date(text) != null) && text.length < 18 && !Regex("买|整理|会议|新增|再记").containsMatchIn(text)) {
            val answer = pending.copy(date = date(text) ?: pending.date ?: LocalDate.now(clock).toString(), time = time ?: pending.time, period = if(time != null) null else pending.period, alarm = audio(text,pending.alarm))
            return if(answer.time == null) LocalAction.Ask("铭哥，几点${if(answer.kind == "alarm") "响铃" else "提醒"}？", answer) else LocalAction.Create(answer)
        }
        if (pending != null && Regex("先记|还没想好|暂不提醒").containsMatchIn(text)) return LocalAction.Create(pending.copy(kind = "task", time = null, needsReminder = false,recurrence=if(pending.recurrence?.frequency==RecurrenceRules.WEEKLY && pending.recurrence.weekdays.isEmpty()) null else pending.recurrence))
        if (text.trim() in listOf("撤销", "撤销刚才的操作", "撤销刚才")) return LocalAction.Undo
        if (Regex("有什么|有哪些|什么安排|做什么|看.*待办").containsMatchIn(text)) return LocalAction.Query(date(text) ?: LocalDate.now(clock).toString())
        if (Regex("挪到|改到|删除|取消.*闹钟|记住|以后|忘记|完成了").containsMatchIn(text)) return LocalAction.Unsupported("铭哥，离线记录支持新增事项、时间追问与查询。这个操作请点事项卡片修改，或配置模型后交给我处理。")
        if(Regex("轮班|上\\s*[一二三四五六七八九十两\\d]+\\s*天.*休").containsMatchIn(text)) return LocalAction.Unsupported("铭哥，轮班要确认上班天数、休息天数和首个上班日。请在闹钟的“重复 → 轮班制”里选取，或配置模型后让我询问并设置。")
        val kind = when { Regex("叫我|叫醒|闹钟|起床").containsMatchIn(text) -> "alarm"; "习惯" in text && "打卡" !in text -> "task"; Regex("想看|想买|记一下|记下|备忘|先记着").containsMatchIn(text) && time == null -> "note"; else -> "task" }
        val reminder = kind != "alarm" && "提醒" in text
        val day = date(text) ?: if(time != null) LocalDate.now(clock).toString() else null
        val period = when { Regex("明早|早上|上午").containsMatchIn(text) -> "morning"; "下午" in text -> "afternoon"; Regex("明晚|今晚|晚上|晚间").containsMatchIn(text) -> "evening"; else -> null }
        val title = text.replace("周一至周五", "").replace(Regex("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}月\\d{1,2}[日号]?|(?:下周|这周|本周|周|星期)[一二三四五六日天]|今天|明天|后天|明早|明晚|今晚|早上|上午|下午|晚上|晚间|[零一二三四五六七八九十两\\d]{1,3}(?:点|:|：)[零一二三四五六七八九十\\d]{0,3}(?:分|半)?|提醒我|叫醒我|叫我|设置闹钟|记一下|记下|先记着|记得"), "").trim(' ', '，', '。').ifBlank { if(kind == "alarm") "起床" else "待办事项" }
        val isDeadline = day != null && "前" in text && "提前" !in text
        val start=LocalDate.now(clock).toString()
        val legalWorkday=Regex("法定工作日|工作日.*跳过.*节假日|跳过.*节假日.*工作日").containsMatchIn(text)
        val legalRestday=Regex("法定节假日|法定休息日").containsMatchIn(text)
        val recurring = when {
            legalWorkday -> RecurrenceSpec(frequency=RecurrenceRules.WORKDAY,startDate=start)
            legalRestday -> RecurrenceSpec(frequency=RecurrenceRules.RESTDAY,startDate=start)
            Regex("每天|每日|每周|工作日|周末都|周一至周五").containsMatchIn(text) -> RecurrenceSpec(weekdays = when { "工作日" in text || "周一至周五" in text -> setOf(1,2,3,4,5); "周末" in text -> setOf(6,7); "每周" in text -> Regex("周([一二三四五六日天])").findAll(text).map { "一二三四五六日".indexOf(it.groupValues[1].replace("天","日"))+1 }.toSet(); else -> (1..7).toSet() },startDate=start)
            "习惯" in text && kind=="task" -> RecurrenceSpec(weekdays=emptySet(),startDate=start)
            else -> null
        }
        val draft = LocalDraft(kind, title.removePrefix("之前").removePrefix("以前").removePrefix("前").replace(Regex("法定工作日|法定节假日|法定休息日|周一至周五|每天|每日|每周|工作日|周末都|帮我|建立|创建|新增|养成|安排|一个|习惯任务|习惯|待办|[，, ]*跳过节假日"),"").trim().let { if(kind=="task" && "习惯" in text) it.trimEnd('的').trim() else it }.take(240).ifBlank { "起床" }, if(isDeadline) null else day ?: if(period != null) LocalDate.now(clock).toString() else null, time, if(time == null) period else null, reminder, if(isDeadline) day else null, audio(text,AlarmSpec()),recurring)
        if(recurring?.frequency==RecurrenceRules.WEEKLY && recurring.weekdays.isEmpty()) return LocalAction.Ask("铭哥，每周哪天安排这件事？",draft.copy(pendingField="weekdays"))
        if(kind == "alarm" && time == null) return LocalAction.Ask("铭哥，${if(day != null) "${day} " else ""}几点叫你？", draft)
        if(reminder && time == null) return LocalAction.Ask("铭哥，几点提醒你${title}？也可以先存待办。", draft, listOf("先存待办，暂不提醒"))
        return LocalAction.Create(draft)
    }
    private fun audio(text: String, old: AlarmSpec): AlarmSpec = old.copy(headphonesOnly=if(Regex("别外放|不外放|仅耳机|只用耳机").containsMatchIn(text)) true else old.headphonesOnly,playAudio=if(Regex("只振动|纯振动").containsMatchIn(text)) false else old.playAudio,vibrate=if(Regex("只振动|纯振动").containsMatchIn(text)) true else old.vibrate)
    fun date(text: String): String? {
        Regex("\\d{4}-\\d{2}-\\d{2}").find(text)?.let { return LocalDate.parse(it.value).toString() }
        val now = LocalDate.now(clock)
        if("后天" in text) return now.plusDays(2).toString()
        if(Regex("明天|明早|明晚").containsMatchIn(text)) return now.plusDays(1).toString()
        if(Regex("今天|今晚").containsMatchIn(text)) return now.toString()
        Regex("(\\d{1,2})月(\\d{1,2})[日号]?").find(text)?.let { return LocalDate.of(now.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()).toString() }
        Regex("(下周|这周|本周|周|星期)([一二三四五六日天])").find(text)?.let {
            val day = "一二三四五六日".indexOf(if(it.groupValues[2] == "天") "日" else it.groupValues[2]) + 1
            return if(it.groupValues[1] == "下周") now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).plusDays(day - 1L).toString() else now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day))).toString()
        }
        return null
    }
    fun time(text: String): String? {
        val m = Regex("([零一二三四五六七八九十两\\d]{1,3})(?:点|:|：)([零一二三四五六七八九十\\d]{0,3})(半|分)?").find(text) ?: return null
        fun number(s: String): Int {
            s.toIntOrNull()?.let { return it }
            val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            return if('十' in s) { val parts = s.split('十'); (parts[0].firstOrNull()?.let { digits[it] } ?: 1) * 10 + (parts[1].firstOrNull()?.let { digits[it] } ?: 0) } else s.firstOrNull()?.let { digits[it] } ?: 0
        }
        var hour = number(m.groupValues[1]); val minute = if(m.groupValues[3] == "半") 30 else number(m.groupValues[2])
        if(Regex("下午|晚上|明晚|今晚").containsMatchIn(text) && hour in 1..11) hour += 12
        if(hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }
}
