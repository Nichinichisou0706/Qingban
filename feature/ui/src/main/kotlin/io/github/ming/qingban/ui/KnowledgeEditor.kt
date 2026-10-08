package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ming.qingban.domain.KnowledgeSource
import java.time.LocalDate

@Composable fun KnowledgeEditor(source: KnowledgeSource, onDismiss: () -> Unit, onSave: (String,String,Boolean,String?,String?,String,String) -> Unit) {
    var title by remember { mutableStateOf(source.title) }; var text by remember { mutableStateOf(source.text) }
    var enabled by remember { mutableStateOf(source.enabled) }; var from by remember { mutableStateOf(source.validFrom.orEmpty()) }; var until by remember { mutableStateOf(source.validUntil.orEmpty()) }
    var origin by remember { mutableStateOf(source.origin) }; var reliability by remember { mutableStateOf(source.reliability) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("编辑资料 · v${source.version}") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title,{ title=it },label={ Text("名称") }); OutlinedTextField(text,{ text=it },label={ Text("正文") },minLines=5,maxLines=10)
        Row { Text("用于助手参考",Modifier.weight(1f)); Switch(enabled,{ enabled=it }) }
        DateWheelField(from,"适用开始日期") { from=it }
        DateWheelField(until,"适用结束日期") { until=it }
        OutlinedTextField(origin,{ origin=it },label={ Text("来源说明或网页链接") })
        OutlinedTextField(reliability,{ reliability=it },label={ Text("可靠性说明") })
        if(source.revisions.isNotEmpty()) { Text("历史版本（保留最近 5 版）"); source.revisions.reversed().forEach { revision -> OutlinedButton(onClick={ title=revision.title; text=revision.text }) { Text("载入 v${revision.version}，检查后另存新版") } } }
        Text("修改正文会生成新版本。停用或过期资料默认不会进入模型上下文。")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ Button(onClick={
        val valid=runCatching { if(from.isNotBlank()) LocalDate.parse(from); if(until.isNotBlank()) LocalDate.parse(until); require(from.isBlank() || until.isBlank() || from<=until) }.isSuccess
        if(!valid) error="请检查日期格式和先后顺序" else onSave(title,text,enabled,from.takeIf { it.isNotBlank() },until.takeIf { it.isNotBlank() },origin,reliability)
    },enabled=title.isNotBlank() && text.isNotBlank() && text.length<=250_000) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("返回") } })
}
