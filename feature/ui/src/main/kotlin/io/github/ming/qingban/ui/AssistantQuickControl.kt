package io.github.ming.qingban.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.ItemKind
import kotlin.math.roundToInt

@Composable fun AssistantQuickControl(expanded: Boolean, onToggle: () -> Unit, onChat: () -> Unit, onCreate: (ItemKind) -> Unit, onFocus: () -> Unit, onCalendar: () -> Unit) {
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    val screen=LocalConfiguration.current
    val minX=with(density) { -(screen.screenWidthDp.dp-(if(expanded) 264 else 80).dp-32.dp).toPx().coerceAtLeast(0f) }
    val minY=with(density) { -(screen.screenHeightDp.dp-400.dp).toPx().coerceAtLeast(0f) }
    LaunchedEffect(expanded,screen.screenWidthDp,screen.screenHeightDp) { x=x.coerceIn(minX,0f); y=y.coerceIn(minY,0f) }
    Column(Modifier.offset { IntOffset(x.roundToInt(),y.roundToInt()) }.pointerInput(minX,minY) {
        detectDragGestures { change,drag -> change.consume(); x=(x+drag.x).coerceIn(minX,0f); y=(y+drag.y).coerceIn(minY,0f) }
    },horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(expanded) {
            Card(Modifier.width(264.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(5.dp)) {
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("铭哥，这次想做什么？",style=MaterialTheme.typography.titleSmall)
                    TextButton(onClick=onChat,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.ChatBubbleOutline,null); Text(" 和 GPT 娘说一句") }
                    Row { TextButton(onClick={ onCreate(ItemKind.TASK) },modifier=Modifier.weight(1f)) { Text("＋ 事项") }; TextButton(onClick={ onCreate(ItemKind.NOTE) },modifier=Modifier.weight(1f)) { Text("✎ 备忘") } }
                    Row { TextButton(onClick={ onCreate(ItemKind.ALARM) },modifier=Modifier.weight(1f)) { Icon(Icons.Default.Alarm,null,Modifier.size(17.dp)); Text(" 闹钟") }; TextButton(onClick=onFocus,modifier=Modifier.weight(1f)) { Icon(Icons.Default.Timer,null,Modifier.size(17.dp)); Text(" 番茄钟") } }
                    TextButton(onClick=onCalendar,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth,null,Modifier.size(17.dp)); Text(" 看日历") }
                    Text("拖动入口可左右、上下移动",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FloatingActionButton(onClick=onToggle,containerColor=MaterialTheme.colorScheme.primaryContainer) { Icon(if(expanded) Icons.Default.Close else Icons.Default.AutoAwesome,"展开或收起助手快捷功能") }
    }
}
