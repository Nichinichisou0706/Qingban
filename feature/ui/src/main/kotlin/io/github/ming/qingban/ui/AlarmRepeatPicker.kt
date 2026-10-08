package io.github.ming.qingban.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ming.qingban.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate

internal val RecurrenceSaver = Saver<RecurrenceSpec, String>(
    save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) }
)

/** An isolated repeat editor; changes are returned only when the user taps Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AlarmRepeatPicker(rule: RecurrenceSpec?, onDismiss: () -> Unit, showDateSettings: Boolean = true, isAlarm: Boolean = true, onSelect: (RecurrenceSpec?) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(rule != null) }
    var draft by rememberSaveable(stateSaver = RecurrenceSaver) { mutableStateOf(rule ?: RecurrenceSpec(weekdays = (1..7).toSet(), startDate = LocalDate.now().toString())) }
    var page by rememberSaveable { mutableStateOf("list") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(showDateSettings) { if(!showDateSettings && page=="dates") page="list" }
    fun weekly(days: Set<Int>) { enabled = true; draft = RecurrenceSpec(weekdays = days, startDate = draft.startDate, endDate = draft.endDate); error = null }
    fun calendar(frequency: String) { enabled = true; draft = RecurrenceSpec(frequency = frequency, startDate = draft.startDate, endDate = draft.endDate); error = null }
    fun finish() {
        error = if(enabled) runCatching { RecurrenceRules.validate(draft) }.exceptionOrNull()?.message else null
        if(error == null) onSelect(if(enabled) draft else null)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if(page == "list") onDismiss() else { page = "list"; error = null } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if(page == "list") "返回" else "返回重复列表") }
                Text(when(page) { "shift" -> "轮班制"; "custom" -> "自定义星期"; "dates" -> "生效及结束日期"; else -> "重复" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = ::finish) { Text("完成") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when(page) {
                    "list" -> {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            RepeatRow(if(isAlarm) "只响一次" else "不重复", selected = !enabled) { enabled = false; error = null }
                            RepeatRow("每天", selected = enabled && draft.frequency == RecurrenceRules.WEEKLY && draft.weekdays.size == 7) { weekly((1..7).toSet()) }
                            RepeatRow("法定工作日", if(isAlarm) "智能跳过节假日，调休上班日照常响铃" else "按法定工作日出现，包含调休上班日", enabled && draft.frequency == RecurrenceRules.WORKDAY) { calendar(RecurrenceRules.WORKDAY) }
                            RepeatRow("法定节假日", "按法定休息日出现，包含普通周末", enabled && draft.frequency == RecurrenceRules.RESTDAY) { calendar(RecurrenceRules.RESTDAY) }
                            RepeatRow("周一至周五", selected = enabled && draft.frequency == RecurrenceRules.WEEKLY && draft.weekdays == setOf(1,2,3,4,5)) { weekly(setOf(1,2,3,4,5)) }
                            RepeatRow("周末", selected = enabled && draft.frequency == RecurrenceRules.WEEKLY && draft.weekdays == setOf(6,7), divider = false) { weekly(setOf(6,7)) }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            RepeatRow("轮班制", if(enabled && draft.frequency == RecurrenceRules.SHIFT) RecurrenceRules.label(draft) else "连续上班与休息，按周期轮换", enabled && draft.frequency == RecurrenceRules.SHIFT, arrow = true) {
                                if(draft.frequency != RecurrenceRules.SHIFT) draft = RecurrenceSpec(frequency = RecurrenceRules.SHIFT, startDate = draft.startDate, endDate = draft.endDate, workDays = 2, restDays = 2, cycleAnchor = draft.startDate)
                                enabled = true; page = "shift"; error = null
                            }
                            RepeatRow("自定义", if(isAlarm) "选择每周响铃的星期" else "选择每周出现的星期", enabled && draft.frequency == RecurrenceRules.WEEKLY && draft.weekdays !in listOf((1..7).toSet(),setOf(1,2,3,4,5),setOf(6,7)), arrow = true, divider = false) {
                                if(draft.frequency != RecurrenceRules.WEEKLY) weekly(setOf(1,2,3,4,5))
                                enabled = true; page = "custom"; error = null
                            }
                        }
                        if(enabled) {
                            if(showDateSettings) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                RepeatRow("生效及结束日期", "从 ${draft.startDate} 起 · ${draft.endDate?.let { "至 $it" } ?: "不设结束日期"}", arrow = true, divider = false) { page = "dates" }
                            }
                            if(RecurrenceRules.usesHolidayCalendar(draft)) Text("${MainlandHolidayCalendar.COVERAGE}，依据国务院放假调休安排。未覆盖的年份需更新日历后才能排程。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    "custom" -> {
                        Text(if(isAlarm) "每周哪些天响铃？至少选择一天。" else "每周哪些天出现？至少选择一天。")
                        (1..7).forEach { day ->
                            val checked = day in draft.weekdays
                            Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(weekdays = if(checked) draft.weekdays - day else draft.weekdays + day) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null)
                                Text("星期${"一二三四五六日"[day-1]}", Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    "shift" -> {
                        Text("从首个上班日开始，连续上班后休息，再重复这个周期。" + if(isAlarm) "闹钟只在上班段响铃。" else "习惯只在上班段出现。")
                        Text("连续上班天数")
                        NumberWheel(draft.workDays ?: 2, 1..365, "天", Modifier.fillMaxWidth()) { draft = draft.copy(workDays = it) }
                        Text("连续休息天数")
                        NumberWheel(draft.restDays ?: 2, 1..365, "天", Modifier.fillMaxWidth()) { draft = draft.copy(restDays = it) }
                        if(showDateSettings) DateWheelField(draft.cycleAnchor ?: draft.startDate, "首个上班日", false) { anchor -> draft = draft.copy(cycleAnchor = anchor, startDate = maxOf(draft.startDate, anchor)) }
                        else {
                            val today=LocalDate.now()
                            val phase=AlarmSchedules.shiftPhase(draft,today)
                            val length=(draft.workDays ?: 2)+(draft.restDays ?: 2)
                            Text("今天是本轮第几天？")
                            Text(if(phase<=(draft.workDays ?: 2)) "上班第 $phase 天" else "休息第 ${phase-(draft.workDays ?: 2)} 天",style=MaterialTheme.typography.bodySmall)
                            NumberWheel(phase,1..length,"天",Modifier.fillMaxWidth()) { draft=AlarmSchedules.withShiftPhase(draft,it,today) }
                        }
                    }
                    "dates" -> {
                        Text("默认从今天起，按重复规则自动计算下一次。")
                        DateWheelField(draft.startDate, "生效日期", false) { draft = draft.copy(startDate = it) }
                        DateWheelField(draft.endDate.orEmpty(), "重复结束日期") { draft = draft.copy(endDate = it.takeIf(String::isNotBlank)) }
                        if(draft.frequency == RecurrenceRules.SHIFT) Text("修改生效日期不改变轮班周期；周期由首个上班日决定。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable private fun RepeatRow(title: String, subtitle: String? = null, selected: Boolean = false, arrow: Boolean = false, divider: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp)) { if(selected) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if(subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
        if(arrow) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
    if(divider) HorizontalDivider(Modifier.padding(start = 44.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
}
