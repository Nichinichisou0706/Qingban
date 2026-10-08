package io.github.ming.qingban.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ming.qingban.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ItemSelectionBar(active: Boolean, count: Int, total: Int, onStart: () -> Unit, onAll: () -> Unit, onClear: () -> Unit, onExit: () -> Unit, onDelete: () -> Unit, onUndo: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        if(!active) TextButton(onClick=onStart,enabled=total>0) { Text("多选") }
        else {
            Text("已选 $count / $total",Modifier.align(Alignment.CenterVertically))
            TextButton(onClick=onAll,enabled=total>0) { Text("全选") }
            TextButton(onClick=onClear,enabled=count>0) { Text("全不选") }
            TextButton(onClick=onDelete,enabled=count>0) { Text("删除 ($count)") }
            TextButton(onClick=onExit) { Text("退出多选") }
        }
        TextButton(onClick=onUndo) { Text("撤销") }
    }
}

@Composable fun SelectedItemCard(item: PlanItem, selected: Boolean, onSelect: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onSelect)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(selected,{ onSelect() },modifier=Modifier.semantics { contentDescription="选择事项：${item.title}" })
            Column(Modifier.weight(1f)) { Text(item.title,style=MaterialTheme.typography.titleMedium); Text(itemTime(item)); if(item.isHabitTask) Text("习惯 · ${RecurrenceRules.label(item.schedule!!.recurrence!!)}",style=MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DeleteItemsDialog(targets: List<PlanItem>, date: String?, onDismiss: () -> Unit, onConfirm: (EditScope) -> Unit) {
    var scope by remember { mutableStateOf(if(date!=null) EditScope.THIS_OCCURRENCE else EditScope.WHOLE_SERIES) }
    val recurring=targets.any { it.schedule?.recurrence!=null }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("删除 ${targets.size} 件事项？") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(targets.take(5).joinToString("\n") { "· ${it.title}" } + if(targets.size>5) "\n…共 ${targets.size} 件" else "")
        if(recurring && date!=null) {
            Text("重复事项的删除范围")
            FlowRow { FilterChip(scope==EditScope.THIS_OCCURRENCE,{ scope=EditScope.THIS_OCCURRENCE },label={ Text("仅本次") }); FilterChip(scope==EditScope.WHOLE_SERIES,{ scope=EditScope.WHOLE_SERIES },label={ Text("整个系列") }) }
            Text(if(scope==EditScope.THIS_OCCURRENCE) "只移除 $date 的这一次，之后仍按周期出现。" else "删除整个重复系列，包括以后的安排。")
        } else if(recurring) Text("重复事项会删除整个系列，包括以后的安排。")
        Text("删除后停止相关提醒，可以点击“撤销”恢复。")
    } },confirmButton={ TextButton(onClick={ onConfirm(scope) }) { Text("确认删除") } },dismissButton={ TextButton(onClick=onDismiss) { Text("保留") } })
}
