package io.github.ming.qingban.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

@Composable fun PlatformSettingsScreen(deps: UiDependencies) {
    var permissionStatus by remember { mutableStateOf(deps.platform.permissions()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val petConfig=remember { deps.platform.petSettings() }
    val petSize=46
    var petAlpha by remember { mutableFloatStateOf(petConfig["alpha"]?.toFloatOrNull() ?: 1f) }
    var animations by remember { mutableStateOf(petConfig["animations"]!="false") }
    var archiveBusy by remember { mutableStateOf(false) }
    val portableExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { scope.launch { archiveBusy=true; runCatching { deps.platform.exportPortable(it.toString()) }.onSuccess { Toast.makeText(context,"含音乐的完整备份已保存",Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }; archiveBusy=false } } }
    val portableRestore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { archiveBusy=true; runCatching { deps.platform.restorePortable(it.toString()) }.onSuccess { Toast.makeText(context,"完整备份已合并，对话与提醒已重新加载",Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }; archiveBusy=false } } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { scope.launch { runCatching { deps.platform.exportBackup(it.toString()) }.onSuccess { Toast.makeText(context, "备份已保存", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } } } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { scope.launch { runCatching { deps.platform.restoreBackup(it.toString()) }.onSuccess { Toast.makeText(context, "备份已合并，提醒已重新检查", Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("提醒与闹钟")
        listOf("notifications" to "通知", "exact_alarm" to "精确闹钟", "full_screen" to "锁屏响铃页", "overlay" to "悬浮窗", "battery" to "电池优化豁免").forEach { (key, label) -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); SoftTag(if(permissionStatus[key] == true) "已允许" else "待开启", permissionStatus[key] != true); TextButton(onClick = { deps.platform.requestPermission(key) }) { Text("设置") } } }
        OutlinedButton(onClick = { permissionStatus = deps.platform.permissions(); scope.launch { deps.services.items.restoreSchedules() } }, modifier = Modifier.fillMaxWidth()) { Text("权限已调整 · 重新检查提醒") }
        Text("HyperOS 的自启动与省电限制需在系统应用设置中调整，当前无法自动确认。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={ deps.platform.requestPermission("autostart") },modifier=Modifier.fillMaxWidth()) { Text("打开 HyperOS 自启动设置") }
        SectionTitle("手机桌面陪伴")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { deps.platform.startPet() }) { Text("开启悬浮 GPT 娘") }; OutlinedButton(onClick = { deps.platform.stopPet() }) { Text("关闭") } }
        OutlinedButton(onClick = { deps.platform.pinWidget() }, modifier = Modifier.fillMaxWidth()) { Text("添加桌面小组件") }
        Text("角色可以拖动、贴边、点击记事；关闭桌宠不会取消闹钟。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("常态尺寸与轮盘中心角色一致 · 46 × 76",style=MaterialTheme.typography.bodySmall)
        Text("透明度：${(petAlpha*100).toInt()}%"); Slider(petAlpha,{ petAlpha=it },valueRange=0.3f..1f)
        Row { Text("轻量角色动画",Modifier.weight(1f)); Switch(animations,{ animations=it }) }
        Row { TextButton(onClick={ deps.platform.savePetSettings(petSize.toInt(),petAlpha,animations); Toast.makeText(context,"设置已保存，重新开启桌宠后生效",Toast.LENGTH_SHORT).show() }) { Text("保存角色设置") }; TextButton(onClick=deps.platform::hidePet) { Text("隐藏 10 分钟") } }
        SectionTitle("本地备份")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { export.launch("轻伴备份.json") }) { Text("导出备份") }; OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain")) }) { Text("合并备份") } }
        Text("备份包含事项、资料和偏好，不含 API 密钥与音乐文件。合并时保留本机已有内容。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row { OutlinedButton(onClick={ portableExport.launch("轻伴完整备份.qbzip") },enabled=!archiveBusy) { Text("完整导出") }; OutlinedButton(onClick={ portableRestore.launch(arrayOf("application/zip","application/octet-stream")) },enabled=!archiveBusy) { Text("合并完整备份") } }
        if(archiveBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("完整备份额外包含所有缓存音乐、对话、学习设置和观察；仍不含 API 密钥。导入后保留本机同 ID 内容。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
