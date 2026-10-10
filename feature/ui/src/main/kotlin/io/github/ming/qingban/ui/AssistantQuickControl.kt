package io.github.ming.qingban.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.ItemKind
import io.github.ming.qingban.domain.CompanionMenuLayout
import kotlin.math.roundToInt

@Composable fun AssistantQuickControl(expanded: Boolean, onToggle: () -> Unit, onChat: () -> Unit, onCreate: (ItemKind) -> Unit, onFocus: () -> Unit, onCalendar: () -> Unit) {
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    val screen=LocalConfiguration.current
    val width=if(expanded) CompanionMenuLayout.WIDTH else 56
    val height=if(expanded) CompanionMenuLayout.HEIGHT else 56
    val minX=with(density) { -(screen.screenWidthDp.dp-width.dp-32.dp).toPx().coerceAtLeast(0f) }
    val minY=with(density) { -(screen.screenHeightDp.dp-height.dp-180.dp).toPx().coerceAtLeast(0f) }
    val inwardRight=x < with(density) { -(screen.screenWidthDp.dp/2-56.dp).toPx() }
    val shownX=x.coerceIn(minX,0f)
    val shownY=y.coerceIn(minY,0f)
    val currentToggle by rememberUpdatedState(onToggle)
    val alpha by animateFloatAsState(if(expanded) 1f else .96f,label="shortcut-alpha")
    Box(Modifier.offset { IntOffset(shownX.roundToInt(),shownY.roundToInt()) }.size(width.dp,height.dp).graphicsLayer { this.alpha=alpha }) {
        if(expanded) {
            val actions=listOf<Pair<String,()->Unit>>(
                "对话" to onChat,"事项" to { onCreate(ItemKind.TASK) },"备忘" to { onCreate(ItemKind.NOTE) },
                "日历" to onCalendar,"闹钟" to { onCreate(ItemKind.ALARM) },"专注" to onFocus,
            )
            CompanionMenuLayout.points(actions.size,inwardRight).forEachIndexed { index,point ->
                val action=actions[index]
                FilledTonalButton(onClick=action.second,modifier=Modifier.offset((point.x-24).dp,(point.y-24).dp).size(48.dp),shape=CircleShape,contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) { Text(action.first,style=MaterialTheme.typography.labelMedium) }
            }
        }
        val drag=Modifier.pointerInput(minX,minY) {
            detectDragGestures { change,amount -> change.consume(); x=(x+amount.x).coerceIn(minX,0f); y=(y+amount.y).coerceIn(minY,0f) }
        }
        FloatingActionButton(onClick={ currentToggle() },modifier=(if(expanded) Modifier.offset((CompanionMenuLayout.portraitLeft(inwardRight)-5).dp,104.dp) else Modifier).size(56.dp).then(drag),shape=CircleShape,containerColor=MaterialTheme.colorScheme.primaryContainer,elevation=FloatingActionButtonDefaults.elevation(2.dp)) {
            Icon(if(expanded) Icons.Default.Close else Icons.Default.AutoAwesome,"展开或收起助手快捷功能")
        }
    }
}
