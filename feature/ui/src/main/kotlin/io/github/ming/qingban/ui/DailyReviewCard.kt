package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.launch

@Composable internal fun DailyReviewCard(deps: UiDependencies) {
    val pending by deps.services.review.pending.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var selected by remember(pending?.id) { mutableStateOf(pending?.moves?.map { it.id }?.toSet() ?: emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OperationResult?>(null) }
    val scope=rememberCoroutineScope()
    pending?.let { p ->
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("日终调整建议",style=MaterialTheme.typography.titleMedium)
                Text("${p.day} 的未完成事项：建议调整 ${p.moves.size} 件。确认后才会移动。",style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick={ open=true }) { Text("查看调整建议") }; TextButton(onClick={ scope.launch { deps.services.review.dismiss(p.id) } }) { Text("今天先不调整") } }
                TextButton(onClick={ scope.launch { deps.services.review.refresh(force=true) } },enabled=!busy) { Text("重新检查") }
            }
        }
        if(open) AlertDialog(onDismissRequest={ if(!busy) open=false },title={ Text("确认日终安排") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            Row { TextButton(onClick={ selected=p.moves.map { it.id }.toSet() },enabled=!busy) { Text("全选") }; TextButton(onClick={ selected=emptySet() },enabled=!busy) { Text("全不选") } }
            p.moves.forEach { m ->
                Row {
                    Checkbox(m.id in selected,{ checked -> selected=if(checked) selected+m.id else selected-m.id },enabled=!busy)
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                        Text(m.title,style=MaterialTheme.typography.titleSmall)
                        Text("${reviewWhen(m.before)} → ${reviewWhen(m.after)}",style=MaterialTheme.typography.bodySmall)
                        Text(m.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider()
            }
            p.notes.forEach { Text(it,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall) }
            result?.takeIf { it.status=="error" }?.let { Text(it.message,color=MaterialTheme.colorScheme.error) }
        } },confirmButton={ Button(onClick={ scope.launch { busy=true; result=deps.services.review.approve(p.id,selected); busy=false; if(result?.status!="error") open=false } },enabled=!busy && selected.isNotEmpty()) { Text(if(busy) "保存中" else "同意并调整") } },dismissButton={ TextButton(onClick={ open=false },enabled=!busy) { Text("再想想") } })
    }
    result?.takeIf { it.status in setOf("ok","partial") }?.let { saved -> Card(colors=qCardColors(),border=qCardBorder()) { Column(Modifier.padding(12.dp)) {
        Text(saved.message+if(saved.status=="partial") "，部分提醒需检查权限" else "，日终安排已调整")
        Row { TextButton(onClick={ scope.launch { val undone=deps.services.undo(saved.changeId); result=undone.takeIf { it.status=="error" } } }) { Text("撤销这次调整") }; TextButton(onClick={ result=null }) { Text("知道了") } }
    } } }
}
private fun reviewWhen(s: ScheduleSpec?): String = listOfNotNull(s?.date ?: s?.rangeEnd,s?.time ?: s?.period?.let(::periodLabel)).joinToString(" ").ifBlank { "待安排" }
