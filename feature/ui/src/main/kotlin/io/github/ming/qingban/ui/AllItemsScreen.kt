package io.github.ming.qingban.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ming.qingban.domain.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable fun AllItemsScreen(deps: UiDependencies) {
    val vm: ItemListViewModel=viewModel(key="all-items",factory=factory { ItemListViewModel(deps.services) })
    val items by vm.items.collectAsStateWithLifecycle(); val result by vm.result.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }; var status by rememberSaveable { mutableStateOf<ItemStatus?>(null) }; var kind by rememberSaveable { mutableStateOf<ItemKind?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    BackHandler(selecting) { selecting=false; selected=emptyList() }
    var deleting by remember { mutableStateOf<List<PlanItem>?>(null) }
    val editing=items.firstOrNull { it.id==editingId }
    val context=LocalContext.current
    LaunchedEffect(result) { result?.let { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show(); if(it.status!="error") { editingId=null; selected=emptyList(); selecting=false }; vm.consumeResult(it) } }
    val shown=items.filter { (status==null || it.status==status) && (kind==null || it.kind==kind) && (query.isBlank() || query in it.title || query in it.detail || query in it.label) }.sortedByDescending { it.createdAt }
    LaunchedEffect(shown.map { it.id }) { selected=selected.filter { id -> shown.any { it.id==id } } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(selecting) stickyHeader { Surface(color=MaterialTheme.colorScheme.background) { ItemSelectionBar(selecting,selected.size,shown.size,{ selecting=true },{ selected=shown.map { it.id } },{ selected=emptyList() },{ selecting=false; selected=emptyList() },{ deleting=shown.filter { it.id in selected } },{ vm.undo() }) } }
        item { OutlinedTextField(query,{ query=it },Modifier.fillMaxWidth(),placeholder={ Text("搜索事项、备忘和闹钟") },singleLine=true,leadingIcon={ Icon(Icons.Default.Search,null) },trailingIcon={ if(query.isNotEmpty()) IconButton(onClick={ query="" }) { Icon(Icons.Default.Close,"清除搜索") } }) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(status==null,{ status=null },label={ Text("全部") })
            ItemStatus.entries.forEach { value -> FilterChip(status==value,{ status=value },label={ Text(when(value) { ItemStatus.OPEN -> "待办"; ItemStatus.COMPLETED -> "已完成"; ItemStatus.CANCELLED -> "已取消" }) }) }
        } }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(kind==null,{ kind=null },label={ Text("所有类型") })
            ItemKind.entries.forEach { value -> FilterChip(kind==value,{ kind=value },label={ Text(when(value) { ItemKind.NOTE -> "备忘"; ItemKind.TASK -> "事项"; ItemKind.ALARM -> "闹钟" }) }) }
        }; Text("${shown.size} 条记录 · 重复事项按系列显示",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(!selecting) item { ItemSelectionBar(selecting,selected.size,shown.size,{ selecting=true },{ selected=shown.map { it.id } },{ selected=emptyList() },{ selecting=false; selected=emptyList() },{ deleting=shown.filter { it.id in selected } },{ vm.undo() }) }
        items(shown,key={ it.id }) { item -> if(selecting) SelectedItemCard(item,item.id in selected) { selected=if(item.id in selected) selected-item.id else selected+item.id } else HistoryItemCard(item,{ editingId=item.id },{ vm.restore(item) }) }
        if(shown.isEmpty()) item { Text("没有符合条件的记录。") }
    }
    deleting?.let { targets -> DeleteItemsDialog(targets,null,{ deleting=null }) { scope -> vm.delete(targets,scope,null); deleting=null } }
    editing?.let { item -> ItemEditor(item.kind,item,null,deps.platform,items,{ editingId=null },{ draft,scope,date -> vm.save(draft,item,scope,date) },{ scope,date -> vm.cancel(item,scope,date) },onSplit={ vm.split(item,it) }) }
}
@Composable private fun HistoryItemCard(item: PlanItem,onEdit: ()->Unit,onRestore: ()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onEdit),colors=qCardColors(),border=qCardBorder()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(item.title,style=MaterialTheme.typography.titleMedium)
        SoftTag(when(item.status) { ItemStatus.OPEN -> "待办"; ItemStatus.COMPLETED -> "已完成"; ItemStatus.CANCELLED -> "已取消" },item.status==ItemStatus.CANCELLED)
        val schedule=item.schedule
        val day=schedule?.date; val repeat=schedule?.recurrence; val range=schedule?.rangeStart
        val dateLabel=when {
            repeat!=null -> "从 ${repeat.startDate} 起 · ${RecurrenceRules.label(repeat)}"
            day!=null -> day
            range!=null -> "$range 至 ${schedule.rangeEnd}"
            else -> "未定日期"
        }
        Text("$dateLabel · ${itemTime(item)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val deadline=item.deadline
        if(deadline!=null) Text("截止：${deadline.date} ${deadline.time.orEmpty()}")
        if(item.status!=ItemStatus.OPEN) TextButton(onClick=onRestore) { Text("恢复待办") }
    } }
}
