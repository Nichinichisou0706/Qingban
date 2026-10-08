package io.github.ming.qingban.domain

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class CalendarImport(val drafts: List<ItemDraft>, val warnings: List<String>)
object CalendarImporter {
    fun parse(raw: String, zone: ZoneId): CalendarImport {
        require(raw.length<=1_000_000 && "BEGIN:VCALENDAR" in raw && "END:VCALENDAR" in raw) { "不是有效的 ICS 日历文件" }
        val lines=raw.replace("\r\n","\n").replace(Regex("\n[ \\t]"),"").lines()
        val drafts=mutableListOf<ItemDraft>(); val warnings=mutableListOf<String>(); var fields: MutableMap<String,String>?=null; var inAlarm=false
        fun unescape(s: String)=s.replace("\\n","\n").replace("\\N","\n").replace("\\,",",").replace("\\;",";").replace("\\\\","\\")
        fun dateTime(name: String,value: String): LocalDateTime {
            val timezone=Regex("TZID=([^;:]+)").find(name)?.groupValues?.get(1)?.let(ZoneId::of) ?: zone
            if(value.length==8) return LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay()
            val format=DateTimeFormatter.ofPattern(if(value.removeSuffix("Z").length==13) "yyyyMMdd'T'HHmm" else "yyyyMMdd'T'HHmmss")
            val local=LocalDateTime.parse(value.removeSuffix("Z"),format)
            return if(value.endsWith('Z')) local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime() else local.atZone(timezone).withZoneSameInstant(zone).toLocalDateTime()
        }
        lines.forEach { line ->
            if(line=="BEGIN:VALARM") { inAlarm=true; return@forEach }
            if(line=="END:VALARM") { inAlarm=false; return@forEach }
            if(inAlarm) return@forEach
            when(line) {
                "BEGIN:VEVENT" -> { require(fields==null) { "日历事件嵌套不正确" }; fields=mutableMapOf() }
                "END:VEVENT" -> {
                    val values=fields ?: error("日历事件不完整"); fields=null
                    try {
                        require(drafts.size<50) { "一次最多导入 50 项" }
                        val startField=values.entries.firstOrNull { it.key.substringBefore(';')=="DTSTART" } ?: error("没有开始日期")
                        val start=dateTime(startField.key,startField.value); val allDay=startField.value.length==8
                        val title=unescape(values["SUMMARY"] ?: "日历事项").take(240)
                        val rule=values["RRULE"]?.split(';')?.associate { it.substringBefore('=') to it.substringAfter('=') }
                        val recurrence=rule?.let { r ->
                            require(r["FREQ"]=="WEEKLY" && r["INTERVAL"] in setOf(null,"1") && r.keys.all { it in setOf("FREQ","INTERVAL","BYDAY","UNTIL") }) { "仅支持每周重复；其他规则请在原日历调整" }
                            val dayMap=mapOf("MO" to 1,"TU" to 2,"WE" to 3,"TH" to 4,"FR" to 5,"SA" to 6,"SU" to 7)
                            val weekdays=r["BYDAY"]?.split(',')?.map { dayMap[it] ?: error("无效重复星期") }?.toSet() ?: setOf(start.dayOfWeek.value)
                            RecurrenceSpec(weekdays=weekdays,startDate=start.toLocalDate().toString(),endDate=r["UNTIL"]?.let { dateTime("DTEND",it).toLocalDate().toString() })
                        }
                        require(values.keys.none { it.substringBefore(';') in setOf("EXDATE","RDATE","RECURRENCE-ID") }) { "含重复例外，请先导出独立事件" }
                        val endField=values.entries.firstOrNull { it.key.substringBefore(';')=="DTEND" }
                        val duration=if(!allDay && endField!=null) ChronoUnit.MINUTES.between(start,dateTime(endField.key,endField.value)).toInt().also { require(it in 1..1440) { "事件时长须在一天以内" } } else null
                        val schedule=ScheduleSpec(date=start.toLocalDate().toString(),time=if(allDay) null else start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),recurrence=recurrence)
                        val detail=listOfNotNull(values["DESCRIPTION"]?.let(::unescape),values["LOCATION"]?.let { "地点：${unescape(it)}" }).joinToString("\n")
                        drafts+=ItemDraft(ItemKind.TASK,title,detail,schedule,label="日历导入",durationMinutes=duration)
                    } catch(e: Exception) { warnings+="${values["SUMMARY"] ?: "事件"}：${e.message}" }
                }
                else -> fields?.let { if(':' in line) it[line.substringBefore(':')]=line.substringAfter(':') }
            }
        }
        require(fields==null) { "日历事件未结束" }; return CalendarImport(drafts,warnings)
    }
}
