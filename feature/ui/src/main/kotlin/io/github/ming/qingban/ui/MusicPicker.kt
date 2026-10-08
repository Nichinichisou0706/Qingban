package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable fun MusicPicker(platform: PlatformActions, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var local by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var remote by remember { mutableStateOf<List<RemoteTrack>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val incoming=platform.sharedSong()
    LaunchedEffect(incoming) { if(incoming.isNotBlank()) url=incoming }
    DisposableEffect(Unit) { onDispose { platform.stopPreview(); platform.endSongSelection() } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) scope.launch { busy=true; runCatching { platform.importMusic(uri.toString()) }.onSuccess { onSelect(it) }.onFailure { error=it.message }; busy=false } }
    LaunchedEffect(Unit) { local=platform.musicLibrary() }
    AlertDialog(onDismissRequest={ if(!busy) onDismiss() },title={ Text("选择音乐") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={ runCatching { platform.openNetease() }.onFailure { error=it.message } },modifier=Modifier.fillMaxWidth()) { Text("打开网易云 · 选一首歌") }
        Text("在网易云选好后，将歌曲分享给轻伴或复制链接回来。可公开播放的歌曲尝试缓存；会员、加密和下架曲目需导入你已有的可播放音频。")
        OutlinedButton(onClick={ import.launch(arrayOf("audio/*")) },enabled=!busy) { Text("从手机导入音频") }
        OutlinedTextField(url,{ url=it },label={ Text("网易云歌曲分享／音频／M3U 链接") })
        OutlinedButton(onClick={ scope.launch { busy=true; runCatching { platform.remotePlaylist(url.trim()) }.onSuccess { remote=it }.onFailure { error=it.message }; busy=false } },enabled=url.isNotBlank() && !busy) { Text("查看在线曲目") }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        remote.forEach { track -> OutlinedButton(onClick={ scope.launch { busy=true; runCatching { platform.cacheMusic(track.url,track.title) }.onSuccess { onSelect(it) }.onFailure { error="该曲目未能缓存：${it.message}。可以导入本地音频。" }; busy=false } },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("缓存并选用：${track.title}") } }
        Text("已缓存")
        if(local.isEmpty()) Text("还没有缓存音乐，可以先从闹钟页导入本地音频。")
        local.forEach { track -> Row { TextButton(onClick={ onSelect(track.path) },enabled=!busy,modifier=Modifier.weight(1f)) { Text(track.title) }; TextButton(onClick={ runCatching { platform.previewMusic(track.path,false) }.onFailure { error=it.message } }) { Text("试听") } } }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ TextButton(onClick=onDismiss,enabled=!busy) { Text("返回") } })
}
