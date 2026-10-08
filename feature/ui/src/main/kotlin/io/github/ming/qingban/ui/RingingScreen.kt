package io.github.ming.qingban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable fun RingingScreen(title: String, onStop: () -> Unit, onSnooze: () -> Unit) {
    SmartCopyProvider { QingbanTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val tag=rotatingCopy("ring.tag",CopyCatalog.ringTag)
        val support=rotatingCopy("ring.support",CopyCatalog.ringSupport(Regex("起床|叫醒|唤醒").containsMatchIn(title)))
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            SoftTag(tag)
            GptPet(modifier = Modifier.height(250.dp).width(170.dp))
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(support, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("停止响铃") }
            OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("稍后再提醒") }
        }
    } } }
}
