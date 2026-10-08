package io.github.ming.qingban.domain

import java.time.LocalDate
import java.time.LocalTime

object DependencyPlanner {
    fun validate(items: Map<String, PlanItem>): String? {
        val active = items.values.filter { it.status != ItemStatus.CANCELLED }
        if(active.any { it.id in it.dependsOn || it.dependsOn.any { id -> id !in items || items[id]?.deleted==true } }) return "前置事项不存在，或依赖了自己"
        val colors=mutableMapOf<String,Int>()
        fun cycle(root: String): Boolean {
            val stack=java.util.ArrayDeque<Pair<String,Boolean>>(); stack.addLast(root to false)
            while(stack.isNotEmpty()) {
                val (id,exit)=stack.removeLast()
                if(exit) { colors[id]=2; continue }
                if(colors[id]==1) return true
                if(colors[id]==2) continue
                colors[id]=1; stack.addLast(id to true)
                items[id]?.dependsOn?.reversed()?.forEach { stack.addLast(it to false) }
            }
            return false
        }
        if(active.any { cycle(it.id) }) return "事项先后关系形成循环，请调整"
        active.forEach { next -> next.dependsOn.mapNotNull(items::get).forEach { prior ->
            val a = prior.schedule; val b = next.schedule
            if(a?.recurrence == null && b?.recurrence == null && a?.date != null && b?.date != null) {
                if(LocalDate.parse(b.date).isBefore(LocalDate.parse(a.date))) return "“${next.title}”不能安排在前置事项“${prior.title}”之前"
                if(a.time != null && b.time != null && LocalDate.parse(b.date).atTime(LocalTime.parse(b.time)).isBefore(LocalDate.parse(a.date).atTime(LocalTime.parse(a.time)).plusMinutes((prior.durationMinutes ?: 0).toLong()))) return "“${next.title}”的时间早于前置事项结束"
            }
        } }
        return null
    }
    fun ordered(items: List<PlanItem>): List<PlanItem> {
        val byId = items.associateBy { it.id }; val done = mutableSetOf<String>(); val result = mutableListOf<PlanItem>()
        fun add(root: PlanItem) {
            val stack=java.util.ArrayDeque<Pair<PlanItem,Boolean>>(); stack.addLast(root to false)
            while(stack.isNotEmpty()) {
                val (item,exit)=stack.removeLast()
                if(exit) { result+=item; continue }
                if(!done.add(item.id)) continue
                stack.addLast(item to true); item.dependsOn.mapNotNull(byId::get).reversed().forEach { stack.addLast(it to false) }
            }
        }
        items.sortedWith(compareBy<PlanItem> { it.schedule?.time ?: when(it.schedule?.period) { "morning" -> "09:00"; "afternoon" -> "14:00"; "evening" -> "20:00"; else -> "99:00" } }.thenByDescending { it.pinned }.thenBy { it.order }.thenBy { it.createdAt }).forEach(::add)
        return result
    }
    fun conflicts(item: PlanItem, items: List<PlanItem>): List<String> {
        val s = item.schedule ?: return emptyList(); val time = s.time ?: return emptyList(); val date = s.date ?: return emptyList()
        val start = LocalTime.parse(time).toSecondOfDay(); val end = start + (item.durationMinutes ?: 0)*60
        return items.filter { it.id != item.id && it.status == ItemStatus.OPEN && it.schedule?.date == date && it.schedule.time != null }.filter {
            val other = LocalTime.parse(it.schedule!!.time).toSecondOfDay(); val otherEnd = other + (it.durationMinutes ?: 0)*60
            if(item.durationMinutes == null || it.durationMinutes == null) start == other else start < otherEnd && other < end
        }.map { it.title }
    }
}
