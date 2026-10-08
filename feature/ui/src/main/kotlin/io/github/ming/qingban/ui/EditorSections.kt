package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ming.qingban.domain.ItemKind
import java.time.*

@Composable internal fun EditorSection(title: String,content: @Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.4f))
            content()
        }
    }
}
@Composable internal fun EditorDialog(kind: ItemKind,onDismiss: ()->Unit,title: @Composable ()->Unit,text: @Composable ()->Unit,confirmButton: @Composable ()->Unit,dismissButton: @Composable ()->Unit) {
    if(kind==ItemKind.ALARM) AlertDialog(onDismissRequest=onDismiss,title=title,text=text,confirmButton=confirmButton,dismissButton=dismissButton)
    else Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal=12.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Box(Modifier.padding(bottom=14.dp)) { title() }
                Box(Modifier.weight(1f)) { text() }
                HorizontalDivider(Modifier.padding(vertical=10.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { dismissButton(); confirmButton() }
            }
        }
    }
}
@Composable internal fun TaskEndWheelField(date: String,time: String,duration: String,onDuration: (String)->Unit) {
    val start=LocalDate.parse(date).atTime(LocalTime.parse(time))
    val finish=duration.toLongOrNull()?.let(start::plusMinutes)
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("结束：${finish?.let { (if(it.toLocalDate()>start.toLocalDate()) "次日 " else "")+it.toLocalTime() } ?: "未设置"}") }
    if(open) {
        var day by rememberSaveable { mutableStateOf((finish ?: start).toLocalDate().toString()) }
        var end by rememberSaveable { mutableStateOf((finish ?: start).toLocalTime().toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest={ open=false },title={ Text("结束时间") },text={ Column {
            DateWheelField(day,"结束日期",false) { day=it }; TimeWheels(end) { end=it }
            Text("可跨到次日，单个任务最长 24 小时。",style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        } },confirmButton={ TextButton(onClick={ val minutes=Duration.between(start,LocalDate.parse(day).atTime(LocalTime.parse(end))).toMinutes(); if(minutes in 1..1440) { onDuration(minutes.toString()); open=false } else error="结束需晚于开始，且不超过 24 小时" }) { Text("确定") } },dismissButton={ Row { TextButton(onClick={ onDuration(""); open=false }) { Text("清除") }; TextButton(onClick={ open=false }) { Text("返回") } } })
    }
}
