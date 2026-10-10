package io.github.ming.qingban.ui

import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.*

/** A shared native wheel: no keyboard, one-minute precision, accessible swipe actions. */
@Composable fun NumberWheel(value: Int, range: IntRange, suffix: String, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    val foreground=MaterialTheme.colorScheme.onSurface.toArgb()
    val selected=MaterialTheme.colorScheme.primary.toArgb()
    val current by rememberUpdatedState(onValue)
    AndroidView(modifier=modifier.height(154.dp),factory={ context -> NumberPicker(context).apply {
        descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS
        minValue=range.first; maxValue=range.last; wrapSelectorWheel=false
        setFormatter { n -> (if(suffix in setOf("时","分")) n.toString().padStart(2,'0') else n.toString())+suffix }
        if(android.os.Build.VERSION.SDK_INT>=29) { textColor=foreground; textSize=20*resources.displayMetrics.scaledDensity; selectionDividerHeight=2 }
        setOnValueChangedListener { _,_,new -> current(new) }
    } },update={ picker ->
        picker.setOnValueChangedListener(null)
        // NumberPicker requires the new maximum before a larger minimum.
        picker.maxValue=maxOf(picker.maxValue,range.last); picker.minValue=range.first; picker.maxValue=range.last
        picker.value=value.coerceIn(range); picker.contentDescription="选择${suffix}，当前${picker.value}"
        if(android.os.Build.VERSION.SDK_INT>=29) { picker.textColor=selected; picker.textSize=20*picker.resources.displayMetrics.scaledDensity }
        picker.setOnValueChangedListener { _,_,new -> current(new) }
    })
}
@Composable fun TimeWheels(value: String, onChange: (String) -> Unit) {
    val parsed=runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(8,0))
    Row(Modifier.fillMaxWidth()) {
        NumberWheel(parsed.hour,0..23,"时",Modifier.weight(1f)) { onChange(LocalTime.of(it,parsed.minute).toString()) }
        NumberWheel(parsed.minute,0..59,"分",Modifier.weight(1f)) { onChange(LocalTime.of(parsed.hour,it).toString()) }
    }
}
@Composable fun DateWheels(value: String, onChange: (String) -> Unit) {
    val d=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    val now=LocalDate.now().year
    val years=minOf(now-10,d.year)..maxOf(now+30,d.year)
    Row(Modifier.fillMaxWidth()) {
        NumberWheel(d.year,years,"年",Modifier.weight(1.35f)) { y -> onChange(LocalDate.of(y,d.monthValue,minOf(d.dayOfMonth,YearMonth.of(y,d.monthValue).lengthOfMonth())).toString()) }
        NumberWheel(d.monthValue,1..12,"月",Modifier.weight(1f)) { m -> onChange(LocalDate.of(d.year,m,minOf(d.dayOfMonth,YearMonth.of(d.year,m).lengthOfMonth())).toString()) }
        NumberWheel(d.dayOfMonth,1..d.lengthOfMonth(),"日",Modifier.weight(1f)) { onChange(d.withDayOfMonth(it).toString()) }
    }
}
@Composable fun DateWheelField(value: String, label: String, optional: Boolean = true, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("$label：${value.ifBlank { "选择日期" }}") }
    if(open) {
        var selected by rememberSaveable { mutableStateOf(value.ifBlank { LocalDate.now().toString() }) }
        AlertDialog(onDismissRequest={ open=false },title={ Text(label) },text={ Column { Row { TextButton(onClick={ selected=LocalDate.now().toString() }) { Text("今天") }; TextButton(onClick={ selected=LocalDate.now().plusDays(1).toString() }) { Text("明天") } }; DateWheels(selected) { selected=it } } },confirmButton={ TextButton(onClick={ onChange(selected); open=false }) { Text("确定") } },dismissButton={ Row { if(optional) TextButton(onClick={ onChange(""); open=false }) { Text("清除") }; TextButton(onClick={ open=false }) { Text("返回") } } })
    }
}
@Composable fun TimeWheelField(value: String, label: String, optional: Boolean = true, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("$label：${value.ifBlank { "选择时间" }}") }
    if(open) {
        var selected by rememberSaveable { mutableStateOf(value.ifBlank { "08:00" }) }
        AlertDialog(onDismissRequest={ open=false },title={ Text(label) },text={ TimeWheels(selected) { selected=it } },confirmButton={ TextButton(onClick={ onChange(selected); open=false }) { Text("确定") } },dismissButton={ Row { if(optional) TextButton(onClick={ onChange(""); open=false }) { Text("清除") }; TextButton(onClick={ open=false }) { Text("返回") } } })
    }
}
@Composable fun MinutesWheelField(value: String, label: String, range: IntRange = 1..1440, optional: Boolean = true, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("$label：${if(value.isBlank()) "未设置" else "$value 分钟"}") }
    if(open) {
        var selected by rememberSaveable { mutableIntStateOf(value.toIntOrNull()?.coerceIn(range) ?: range.first) }
        AlertDialog(onDismissRequest={ open=false },title={ Text(label) },text={ Column {
            if(range.last>240) Row {
                NumberWheel(selected/60,0..range.last/60,"时",Modifier.weight(1f)) { selected=(it*60+selected%60).coerceIn(range) }
                NumberWheel(selected%60,0..59,"分",Modifier.weight(1f)) { selected=(selected/60*60+it).coerceIn(range) }
            } else NumberWheel(selected,range,"分钟",Modifier.fillMaxWidth()) { selected=it }
            Row { listOf(5,15,30,60).filter { it in range }.forEach { preset -> TextButton(onClick={ selected=preset },modifier=Modifier.weight(1f)) { Text("$preset") } } }
        } },confirmButton={ TextButton(onClick={ onChange(selected.toString()); open=false }) { Text("确定") } },dismissButton={ Row { if(optional) TextButton(onClick={ onChange(""); open=false }) { Text("清除") }; TextButton(onClick={ open=false }) { Text("返回") } } })
    }
}
@Composable fun DateTimeWheelField(value: String, label: String, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("$label：${value.replace('T',' ').ifBlank { "选择日期和时间" }}") }
    if(open) {
        val initial=runCatching { LocalDateTime.parse(value) }.getOrDefault(LocalDate.now().atTime(8,0))
        var date by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
        var time by rememberSaveable { mutableStateOf(initial.toLocalTime().toString()) }
        AlertDialog(onDismissRequest={ open=false },title={ Text(label) },text={ Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { DateWheels(date) { date=it }; TimeWheels(time) { time=it } } },confirmButton={ TextButton(onClick={ onChange("${date}T$time"); open=false }) { Text("确定") } },dismissButton={ Row { TextButton(onClick={ onChange(""); open=false }) { Text("清除") }; TextButton(onClick={ open=false }) { Text("返回") } } })
    }
}
