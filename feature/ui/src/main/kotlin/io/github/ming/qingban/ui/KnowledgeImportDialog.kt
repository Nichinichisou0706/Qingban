package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ming.qingban.domain.KnowledgeAttachment

@Composable
internal fun KnowledgeImportDialog(
    title: String,
    text: String,
    remote: Boolean,
    attachment: KnowledgeAttachment?,
    manualEntry: Boolean,
    nameHint: String,
    bodyHint: String,
    saving: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onRemoteChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest={ if(!saving) onDismiss() },
        title={ Text(if(manualEntry) "手动输入文字" else "保存资料") },
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title,onTitleChange,Modifier.fillMaxWidth(),label={ Text("资料名称") },placeholder={ Text(nameHint) },singleLine=true,isError=title.length>240,supportingText={ Text("${title.length} / 240 字") })
            OutlinedTextField(text,onTextChange,Modifier.fillMaxWidth(),label={ Text("资料正文") },placeholder={ Text(bodyHint) },minLines=5,maxLines=10,isError=text.length>250_000,supportingText={ Text(if(text.length>250_000) "正文最多 25 万字" else "${text.length} 字 · 支持多行输入和粘贴") })
            Row { Column(Modifier.weight(1f)) { Text("允许模型参考"); Text("联网请求时可发送相关片段",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(remote,onRemoteChange) }
            Text("保存资料不会自动创建闹钟或更改你的偏好。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            attachment?.let { Text("同时保留原文件：${it.fileName}。原文件留在本机，模型只接收获准的正文片段。",fontSize=12.sp) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        } },
        confirmButton={ Button(onClick=onSave,enabled=!saving && title.isNotBlank() && title.length<=240 && text.isNotBlank() && text.length<=250_000) { Text(if(saving) "保存中…" else "保存到资料库") } },
        dismissButton={ TextButton(onClick=onDismiss,enabled=!saving) { Text("返回") } },
    )
}
