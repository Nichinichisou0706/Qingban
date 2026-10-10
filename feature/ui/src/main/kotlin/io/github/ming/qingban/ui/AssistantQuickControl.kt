package io.github.ming.qingban.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.ItemKind
import io.github.ming.qingban.domain.CompanionMenuLayout
import kotlin.math.roundToInt

/** The single GPT-girl entry: tap to open the radial shortcuts, drag to move, centre portrait opens chat. */
@Composable fun AssistantQuickControl(expanded: Boolean, onToggle: () -> Unit, onChat: () -> Unit, onCreate: (ItemKind) -> Unit, onFocus: () -> Unit, onCalendar: () -> Unit) {
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    val screen=LocalConfiguration.current
    val width=if(expanded) CompanionMenuLayout.WIDTH else 60
    val height=if(expanded) CompanionMenuLayout.HEIGHT else 60
    val minX=with(density) { -(screen.screenWidthDp.dp-width.dp-32.dp).toPx().coerceAtLeast(0f) }
    val minY=with(density) { -(screen.screenHeightDp.dp-height.dp-180.dp).toPx().coerceAtLeast(0f) }
    val inwardRight=x < with(density) { -(screen.screenWidthDp.dp/2-56.dp).toPx() }
    val shownX=x.coerceIn(minX,0f)
    val shownY=y.coerceIn(minY,0f)
    val currentToggle by rememberUpdatedState(onToggle)
    val reveal by animateFloatAsState(if(expanded) 1f else 0f,label="shortcut-reveal")
    Box(Modifier.offset { IntOffset(shownX.roundToInt(),shownY.roundToInt()) }.size(width.dp,height.dp)) {
        if(expanded) {
            val actions=listOf<Triple<String,ImageVector,()->Unit>>(
                Triple("对话",Icons.AutoMirrored.Filled.Chat,onChat),Triple("事项",Icons.Default.AddTask,{ onCreate(ItemKind.TASK) }),Triple("备忘",Icons.Default.EditNote,{ onCreate(ItemKind.NOTE) }),
                Triple("日历",Icons.Default.CalendarMonth,onCalendar),Triple("闹钟",Icons.Default.Alarm,{ onCreate(ItemKind.ALARM) }),Triple("专注",Icons.Default.Timer,onFocus),
            )
            CompanionMenuLayout.points(actions.size,inwardRight).forEachIndexed { index,point ->
                val (label,icon,action)=actions[index]
                Surface(onClick=action,modifier=Modifier.offset((point.x-24).dp,(point.y-24).dp).size(48.dp).graphicsLayer { alpha=reveal; scaleX=.6f+.4f*reveal; scaleY=.6f+.4f*reveal }.semantics { contentDescription=label },shape=CircleShape,color=MaterialTheme.colorScheme.surface,shadowElevation=4.dp,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.primaryContainer)) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(label,style=MaterialTheme.typography.labelSmall,fontSize=9.sp,lineHeight=11.sp,color=MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        val drag=Modifier.pointerInput(minX,minY) {
            detectDragGestures { change,amount -> change.consume(); x=(x+amount.x).coerceIn(minX,0f); y=(y+amount.y).coerceIn(minY,0f) }
        }
        val avatar=if(expanded) Modifier.offset((CompanionMenuLayout.portraitLeft(inwardRight)-7).dp,102.dp) else Modifier
        Box(avatar.size(60.dp).shadow(6.dp,CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).border(2.dp,MaterialTheme.colorScheme.surface,CircleShape).then(drag).clickable { currentToggle() }.semantics { contentDescription="展开或收起助手快捷功能" },contentAlignment=Alignment.Center) {
            Image(painterResource(R.drawable.gpt_icon),null,Modifier.fillMaxSize().clip(CircleShape))
            if(expanded) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center) { Icon(Icons.Default.Close,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary) }
        }
    }
}
