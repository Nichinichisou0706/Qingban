package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun AboutScreen(platform:PlatformActions) {
    val info=platform.appInfo()
    val context=LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    val notices=remember(context) { runCatching { context.assets.open("third-party-notices.md").bufferedReader().use { it.readText() } }.getOrElse { "无法读取第三方说明，请查看项目文档。" } }
    val tagline=rotatingCopy("about.tagline",CopyCatalog.aboutTagline)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("轻伴 ${info["version"].orEmpty()}",style=MaterialTheme.typography.headlineSmall)
        Text(tagline)
        SectionTitle("数据与隐私")
        Text("事项、资料正文、导入原文件和习惯观察保存在本机。API 密钥使用 Android Keystore 加密保存，备份不包含密钥。应用没有自建云同步服务器。")
        Text("配置模型后，你发送的对话、与操作相关的事项摘要，以及按份获准的资料片段会发送到你设置的模型接口。关闭某份资料的模型参考权限后，它不再用于后续请求。原文件不会直接发送给模型。")
        Text("语音输入由手机的系统识别服务处理。应用优先请求离线识别，实际是否联网由该服务决定；识别后先填入输入框，由你检查和发送。网页和音乐导入会访问你提供的链接。")
        Text("习惯观察默认关闭。开启后只统计本应用中的时间选择与稍后提醒；推测的习惯须经确认才使用。你可以停止观察、停止使用习惯或清空观察。")
        SectionTitle("备份与提醒")
        Text("完整 ZIP 备份包含资料原文件和缓存音乐，属于你的私人数据。它不包含 API 密钥、方案批准或撤销凭据。普通 JSON 备份只包含文字数据。")
        Text("事项保存与提醒启用分别显示。通知、精确闹钟和后台权限需要在系统中允许；关机、强行停止或系统禁止后台运行时，提醒可能无法触发。")
        SectionTitle("第三方与素材")
        Text("技术依赖包括 Kotlin、AndroidX / Compose / Room、OkHttp、PDFBox Android、中文 ML Kit OCR。依赖版本、许可证链接与素材来源随安装包附带。")
        TextButton(onClick={ showNotices=true }) { Text("查看第三方说明") }
        Text("GPT 娘图片沿用铭哥青铃项目中的处理素材，原始来源为 Whom001x/-。原素材的使用授权尚未在仓库中明确，当前安装包用于开发验收。")
        Text("${info["package"].orEmpty()} · 当前版本待手机验收")
    }
    if(showNotices) AlertDialog(onDismissRequest={ showNotices=false },title={ Text("第三方说明") },text={ Text(notices,Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ showNotices=false }) { Text("关闭") } })
}
