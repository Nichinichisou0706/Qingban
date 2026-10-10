package io.github.ming.qingban.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ming.qingban.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ItemSelectionBar(active: Boolean, count: Int, total: Int, onStart: () -> Unit, onAll: () -> Unit, onClear: () -> Unit, onExit: () -> Unit, onDelete: () -> Unit, onUndo: () -> Unit) {
    if(!active) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick=onStart,enabled=total>0) { Text("多选") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick=onUndo) { Text("撤销") }
    } else Surface(Modifier.fillMaxWidth().padding(vertical=6.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(start=6.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onExit) { Icon(Icons.Default.Close,"退出多选") }
            Text("已选 $count 项",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.weight(1f))
            TextButton(onClick=if(count==total) onClear else onAll,enabled=total>0) { Text(if(count==total) "全不选" else "全选") }
            FilledTonalButton(onClick=onDelete,enabled=count>0,colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteOutline,null,Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("删除") }
        }
    }
}

@Composable fun SelectedItemCard(item: PlanItem, selected: Boolean, onSelect: () -> Unit) {
    QCard(Modifier.fillMaxWidth(),onClick=onSelect,color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start=4.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            RoundCheck(selected,onSelect,"选择事项：${item.title}")
            Column(Modifier.weight(1f)) {
                Text(item.title,style=MaterialTheme.typography.titleSmall)
                Text(itemTime(item)+if(item.isHabitTask) " · 习惯 · ${RecurrenceRules.label(item.schedule!!.recurrence!!)}" else "",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
