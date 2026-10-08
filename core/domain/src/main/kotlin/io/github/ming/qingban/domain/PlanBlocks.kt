package io.github.ming.qingban.domain

import java.time.Duration
import java.time.LocalTime

/** Only explicit time ranges are parsed. Untimed continuation lines stay with their block. */
object PlanBlocks {
    private val range = Regex("(?<!\\d)(\\d{1,2}[:：]\\d{2})\\s*[-–—~～至到]\\s*(\\d{1,2}[:：]\\d{2})\\s*([^\\n；;，,、]+)")
    fun split(draft: ItemDraft): List<ItemDraft>? {
        if(draft.kind != ItemKind.TASK) return null
        val matches=range.findAll(draft.detail).toList()
        if(matches.size<2) return null
        require(Regex("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}月\\d{1,2}[日号]").findAll(draft.detail).map { it.value }.distinct().count()<=1) { "多日期计划需要逐日生成任务，不能把所有时间段合并为同一天" }
        require(Regex("今天|明天|后天").findAll(draft.detail).map { it.value }.distinct().count()<=1) { "多日期计划需要逐日生成任务，请分别确认日期" }
        require(draft.schedule?.date!=null && draft.schedule.recurrence==null) { "多时段计划需要确认具体日期，再分别安排；不能把整天时间表塞进一条任务" }
        require(draft.reminder?.atLocal==null) { "拆分计划前请确认每个时段的提醒，独立提醒不能复制到所有任务" }
        val prefix=draft.detail.substring(0,matches.first().range.first).trim().takeUnless {
            it.replace(Regex("今天|明天|后天|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}月\\d{1,2}[日号]"),"").trim(' ','\n','\r','，',',','、','；',';',':','：').isEmpty()
        }.orEmpty()
        return matches.mapIndexed { i,m ->
            val start=LocalTime.parse(m.groupValues[1].replace('：',':').padStart(5,'0'))
            val end=LocalTime.parse(m.groupValues[2].replace('：',':').padStart(5,'0'))
            val minutes=Duration.between(start,end).toMinutes().let { if(it<=0) it+1440 else it }.toInt()
            val trailing=draft.detail.substring(m.range.last+1, matches.getOrNull(i+1)?.range?.first ?: draft.detail.length).trim(' ','\n','\r','；',';','，',',','、')
            draft.copy(title=m.groupValues[3].trim().trimEnd('；',';'),detail=listOf(prefix,trailing).filter { it.isNotBlank() }.joinToString("\n"),schedule=draft.schedule.copy(time=start.toString(),period=null),durationMinutes=minutes,order=draft.order+i)
        }
    }
    fun endLabel(item: PlanItem): String? = item.schedule?.time?.let { t -> item.durationMinutes?.let { minutes ->
        val start=LocalTime.parse(t); val end=start.plusMinutes(minutes.toLong())
        "$t – $end${if(start.toSecondOfDay()+minutes*60>=86400) "（次日）" else ""}"
    } }
}
