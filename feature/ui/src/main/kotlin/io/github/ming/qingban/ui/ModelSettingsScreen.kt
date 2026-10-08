package io.github.ming.qingban.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable fun ModelSettingsScreen(platform: PlatformActions) {
    var profiles by remember { mutableStateOf(platform.modelProfiles()) }
    var activeId by remember { mutableStateOf(platform.activeModelId()) }
    var search by rememberSaveable { mutableStateOf("") }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<ModelProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var evaluating by remember { mutableStateOf(false) }
    var showEvaluation by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = profiles.firstOrNull { it.id == activeId }
    val visible = profiles.filter { listOf(it.name, it.model, it.baseUrl, ModelProviders.name(it.providerId)).any { text -> text.contains(search.trim(), true) } }
    fun reload() { profiles = platform.modelProfiles(); activeId = platform.activeModelId() }
    fun operate(action: () -> Unit): Boolean = runCatching { action(); reload() }.fold({ true }, { message = it.message; false })
    fun evaluate(conversations: Boolean,scenario: String?=null) {
        scope.launch {
            evaluating = true; message = if (conversations) "正在测试 12 个多轮场景（任务、闹钟、习惯与专注）…" else "正在测试 6 个场景…"
            try { message = platform.evaluateModel(conversations,scenario) }
            catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
            finally { evaluating = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(rotatingCopy("settings.model", CopyCatalog.modelIntro), lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Text(if (active == null) "离线记录模式" else "当前使用 · ${active.name}", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                        if (active != null) TextButton(onClick = { if (operate { platform.activateModel(null) }) message = "已切换为离线记录，配置仍保留。" }, enabled = !evaluating) { Text("停用") }
                    }
                    Text(active?.model ?: "添加接口后，选择模型并启用。", fontSize = 13.sp)
                    Text("切换配置会保留事项、对话和已确认的习惯。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("供应商配置 · ${profiles.size}", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { editorId = "new" }, enabled = !evaluating) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text("添加", Modifier.padding(start = 6.dp)) }
            }
            if (profiles.isNotEmpty()) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 10.dp),
                placeholder = { Text("搜索名称、模型或接口") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "清除搜索") } })
        }
        if (visible.isEmpty()) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (profiles.isEmpty()) "连接你的第一个模型" else "没有匹配的配置", fontWeight = FontWeight.Medium)
                    Text(if (profiles.isEmpty()) "选供应商，填入密钥，自动寻找可用模型。也支持自己的兼容接口。" else "试试其他关键词。", lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(visible, key = { it.id }) { profile ->
            ModelProfileRow(profile, profile.id == activeId, !evaluating,
                onActivate = { operate { platform.activateModel(profile.id) }; if (platform.activeModelId() == profile.id) message = "已启用 ${profile.name} · ${profile.model}" },
                onEdit = { editorId = profile.id }, onDelete = { deletion = profile })
        }
        message?.let { text -> item { Text(text, lineHeight = 22.sp, color = MaterialTheme.colorScheme.primary) } }
        item {
            Text("密钥分别加密存于本机。自动查找只读取模型列表；聊天时才发送你允许参考的内容。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showEvaluation = !showEvaluation }) { Text(if (showEvaluation) "收起模型验收" else "模型验收") }
            if (showEvaluation) {
                OutlinedButton(onClick = { evaluate(false) }, enabled = active?.hasKey == true && !evaluating, modifier = Modifier.fillMaxWidth()) { Text("测试当前模型（6 次请求）") }
                OutlinedButton(onClick = { evaluate(true) }, enabled = active?.hasKey == true && !evaluating, modifier = Modifier.fillMaxWidth()) { Text("测试多轮操作（12 个场景）") }
                if(message?.contains("× 任务通知与闹钟独立")==true) TextButton(onClick={ evaluate(true,"任务通知与闹钟独立") },enabled=!evaluating) { Text("只复测任务通知与闹钟") }
                Text("只使用内置测试场景，请求按服务商计费。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editorId?.let { id ->
        key(id) {
            ModelProfileEditor(platform, profiles.firstOrNull { it.id == id }, activeId,
                onDismiss = { editorId = null }, onSaved = { reload(); editorId = null; message = "配置已保存。" })
        }
    }
    deletion?.let { profile ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${profile.name}？") },
            text = { Text(if (profile.id == activeId) "删除此配置和密钥后，助手切换为离线记录。事项与资料会保留。" else "仅删除这套配置和密钥。") },
            confirmButton = { TextButton(onClick = { if (operate { platform.deleteModelProfile(profile.id) }) { message = "配置已删除。"; deletion = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
}

@Composable private fun ModelProfileRow(profile: ModelProfile, active: Boolean, enabled: Boolean, onActivate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(profile.name.take(1).uppercase(), Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ModelProviders.name(profile.providerId), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active) SoftTag("使用中")
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "${profile.name}更多操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑配置 / 查找模型") }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("删除配置") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                    }
                }
            }
            Text(profile.model, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(profile.baseUrl, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (profile.modelIds.isEmpty()) "支持自动查找模型" else "已发现 ${profile.modelIds.size} 个模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onActivate, enabled = enabled && !active && profile.hasKey) { Text(if (active) "已启用" else "启用") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ModelProfileEditor(platform: PlatformActions, original: ModelProfile?, activeId: String?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var provider by rememberSaveable { mutableStateOf(original?.providerId ?: "custom") }
    var base by rememberSaveable { mutableStateOf(original?.baseUrl ?: "") }
    var model by rememberSaveable { mutableStateOf(original?.model ?: "") }
    // Credentials stay in memory and are never included in saved UI state.
    var secret by remember { mutableStateOf("") }
    var jsonMode by rememberSaveable { mutableStateOf(original?.jsonMode ?: true) }
    var useNow by rememberSaveable { mutableStateOf(original == null || original.id == activeId) }
    var providerMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(original?.modelIds ?: emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf("填写接口和密钥后自动寻找模型") }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val normalized = runCatching { ModelConnectionAddress.validate(base) }.getOrNull()
    val canDiscover = normalized != null && (secret.isNotBlank() || original?.hasKey == true && normalized == original.baseUrl)
    LaunchedEffect(base, secret, refresh) {
        fetching = false
        models = if (normalized == original?.baseUrl && secret.isBlank()) original?.modelIds ?: emptyList() else emptyList()
        if (!canDiscover) {
            discoveryMessage = if (original?.hasKey == true && base.isNotBlank() && normalized != original.baseUrl) "接口已变化，请填写该接口的密钥" else "填写接口和密钥后自动寻找模型"
            return@LaunchedEffect
        }
        delay(900)
        fetching = true; discoveryMessage = "正在读取接口的模型列表…"
        try {
            models = platform.discoverModels(base, secret, original?.id)
            discoveryMessage = "已发现 ${models.size} 个模型，点击下方选择"
            if (model.isBlank() && models.size == 1) model = models.first()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            discoveryMessage = e.message ?: "获取失败，可刷新重试或手动填写"
        } finally { fetching = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (original == null) "添加模型配置" else "编辑模型配置", style = MaterialTheme.typography.titleLarge)
            Box {
                OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(ModelProviders.name(provider), Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(providerMenu, { providerMenu = false }) {
                    ModelProviders.presets.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                            if (provider != preset.id) {
                                provider = preset.id; base = preset.baseUrl; secret = ""; model = ""; models = emptyList(); jsonMode = preset.jsonMode
                                if (name.isBlank() || ModelProviders.presets.any { it.name == name }) name = if (preset.id == "custom") "" else preset.name
                            }
                            providerMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, placeholder = { Text("例如：日常助手") }, singleLine = true)
            OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("接口地址") }, placeholder = { Text("https://你的接口地址/v1") }, singleLine = true)
            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text(if (original?.hasKey == true && normalized == original.baseUrl) "API 密钥（留空保留）" else "API 密钥") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fetching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(discoveryMessage, Modifier.weight(1f).padding(start = if (fetching) 8.dp else 0.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { refresh++ }, enabled = canDiscover && !fetching) { Icon(Icons.Default.Refresh, "刷新模型列表") }
            }
            OutlinedButton(onClick = { picker = true }, enabled = models.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp)); Text(if (models.isEmpty()) "等待获取模型列表" else "从 ${models.size} 个模型中选择", Modifier.padding(start = 8.dp))
            }
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, supportingText = { Text("从列表选取，也可手动填写接口支持的模型 ID") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("请求 JSON 格式"); Text("接口不支持时可关闭", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(jsonMode, { jsonMode = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("保存后启用", Modifier.weight(1f)); Switch(useNow, { useNow = it }) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Button(onClick = {
                runCatching { platform.saveModelProfile(ModelProfile(id, name, provider, base, model, jsonMode, modelIds = models), secret, useNow) }
                    .onSuccess { secret = ""; onSaved() }.onFailure { error = it.message }
            }, enabled = name.isNotBlank() && model.isNotBlank() && canDiscover, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
        }
    }
    if (picker) ModelPickerDialog(models, model, onDismiss = { picker = false }, onSelect = { model = it; picker = false })
}

@Composable private fun ModelPickerDialog(models: List<String>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val visible = models.filter { it.contains(search.trim(), true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索模型名称") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            Text("${visible.size} 个匹配模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                if (visible.isEmpty()) item { Text("没有匹配模型，可返回手动填写。", Modifier.padding(12.dp)) }
                items(visible, key = { it }) { model ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(model == selected, onClick = { onSelect(model) })
                        Text(model, Modifier.weight(1f), fontSize = 14.sp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
