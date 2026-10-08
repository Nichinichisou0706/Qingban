package io.github.ming.qingban.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiCommand(val route: String, val date: String? = null, val view: String? = null, val sequence: Long = 0)
class UiCommands(private val store: SessionStore) {
    private val mutable=MutableStateFlow<UiCommand?>(null)
    val events=mutable.asStateFlow()
    suspend fun open(route: String, date: String? = null, view: String? = null) {
        require(route in setOf("today","calendar","memo","alarm","focus","library","memory","settings","habits")) { "页面不存在" }
        date?.let(LocalDate::parse)
        require(view==null || route=="calendar") { "视图参数只适用于日历" }
        view?.let { require(it in setOf("month","week","three_day","day")); store.write("calendar_view",it) }
        val sequence=store.transaction { val next=(store.read("ui_navigation_sequence")?.toLongOrNull() ?: 0)+1; store.write("ui_navigation_sequence",next.toString()); next }
        mutable.value=UiCommand(route,date,view,sequence)
    }
}
