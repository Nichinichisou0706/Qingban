package io.github.ming.qingban.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.*
import java.time.LocalDate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

val LocalPetAnimations = compositionLocalOf { true }
/** App-wide snackbar; screens show results and undo actions here instead of Toast. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

// 薄荷绿为主色、淡紫为辅色；surfaceContainer* 必须显式给出，否则 M3 默认卡片会落到基线紫灰色
private val light = lightColorScheme(
    primary = Color(0xFF237B61), onPrimary = Color.White, primaryContainer = Color(0xFFDDF3E7), onPrimaryContainer = Color(0xFF0B3D2E),
    secondary = Color(0xFF75668F), onSecondary = Color.White, secondaryContainer = Color(0xFFEDE8F7), onSecondaryContainer = Color(0xFF2E2442),
    tertiary = Color(0xFFB4697C), onTertiary = Color.White, tertiaryContainer = Color(0xFFFBE4EA), onTertiaryContainer = Color(0xFF4A1B27),
    background = Color(0xFFF4F8F6), onBackground = Color(0xFF1F3329),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF22372E), surfaceVariant = Color(0xFFEBF2EE), onSurfaceVariant = Color(0xFF5E7368),
    outline = Color(0xFF9CB0A5), outlineVariant = Color(0xFFDDE8E1),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9FBFA), surfaceContainer = Color(0xFFF1F6F3),
    surfaceContainerHigh = Color(0xFFEBF2EE), surfaceContainerHighest = Color(0xFFE4EDE8), surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDCE5E0),
)
private val dark = darkColorScheme(
    primary = Color(0xFF93D8BB), onPrimary = Color(0xFF003828), primaryContainer = Color(0xFF1E4838), onPrimaryContainer = Color(0xFFC4F0DA),
    secondary = Color(0xFFCABCE4), onSecondary = Color(0xFF332A45), secondaryContainer = Color(0xFF40364F), onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFFF0B6C4), onTertiary = Color(0xFF4A1B27), tertiaryContainer = Color(0xFF5E2E3B), onTertiaryContainer = Color(0xFFFFD9E1),
    background = Color(0xFF121C18), onBackground = Color(0xFFE3EEE7),
    surface = Color(0xFF1A2722), onSurface = Color(0xFFE3EEE7), surfaceVariant = Color(0xFF2B3B33), onSurfaceVariant = Color(0xFFB0C4B7),
    outline = Color(0xFF7D9287), outlineVariant = Color(0xFF354C3E),
    surfaceContainerLowest = Color(0xFF0E1713), surfaceContainerLow = Color(0xFF17231E), surfaceContainer = Color(0xFF1C2A24),
    surfaceContainerHigh = Color(0xFF24332C), surfaceContainerHighest = Color(0xFF2E3E36), surfaceBright = Color(0xFF34443C), surfaceDim = Color(0xFF121C18),
)
private fun uiText(size: Int,line: Int,weight: FontWeight=FontWeight.Normal)=TextStyle(fontSize=size.sp,lineHeight=line.sp,fontWeight=weight,letterSpacing=0.sp,lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.Both))
private val qingbanTypography=Typography(
    headlineLarge=uiText(30,38,FontWeight.SemiBold),headlineMedium=uiText(26,34,FontWeight.SemiBold),headlineSmall=uiText(22,30,FontWeight.SemiBold),
    titleLarge=uiText(20,28,FontWeight.SemiBold),titleMedium=uiText(16,24,FontWeight.Medium),titleSmall=uiText(14,21,FontWeight.Medium),
    bodyLarge=uiText(15,24),bodyMedium=uiText(14,22),bodySmall=uiText(12,19),
    labelLarge=uiText(14,20,FontWeight.Medium),labelMedium=uiText(12,18,FontWeight.Medium),labelSmall=uiText(11,16),
)
@Composable fun QingbanTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = if(isSystemInDarkTheme()) dark else light, typography=qingbanTypography,shapes = Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)), content = content) }

/** Spacing scale shared by all screens. */
object QSpace { val xs=4.dp; val s=8.dp; val m=12.dp; val l=16.dp; val xl=20.dp; val xxl=28.dp; val page=20.dp; val fabClearance=110.dp }

/** White card with a hairline border and a soft mint shadow; the base surface of the card-flow style. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun QCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape=MaterialTheme.shapes.large
    val tint=MaterialTheme.colorScheme.primary.copy(alpha=.10f)
    var surface=modifier.shadow(2.dp,shape,clip=false,ambientColor=tint,spotColor=tint).clip(shape)
    surface=if(brush!=null) surface.background(brush) else surface.background(color)
    surface=surface.border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f),shape)
    if(onClick!=null || onLongClick!=null) surface=surface.combinedClickable(onClick=onClick ?: {},onLongClick=onLongClick)
    Column(surface,content=content)
}

/** Round check used for tasks; keeps a 44dp touch target. */
@Composable fun RoundCheck(checked: Boolean, onToggle: () -> Unit, description: String, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    val ring=if(checked) tint else MaterialTheme.colorScheme.outline
    Box(Modifier.size(44.dp).clip(CircleShape).toggleable(checked,enabled=enabled,role=Role.Checkbox) { onToggle() }.semantics { contentDescription=description },contentAlignment=Alignment.Center) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if(checked) tint else Color.Transparent).border(2.dp,if(enabled) ring else ring.copy(alpha=.4f),CircleShape),contentAlignment=Alignment.Center) {
            if(checked) Icon(Icons.Default.Check,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.surface)
        }
    }
}

/** Tinted rounded-square icon badge. */
@Composable fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, lavender: Boolean = false, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size*0.32f)).background(if(lavender) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(size*0.52f),tint=if(lavender) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
    }
}

/** Group header such as “上午 ──── 3”. */
@Composable fun PeriodHeader(label: String, count: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(top=QSpace.s,bottom=QSpace.xs),verticalAlignment=Alignment.CenterVertically) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f).padding(horizontal=QSpace.s),color=MaterialTheme.colorScheme.outlineVariant)
        count?.let { Text(it.toString(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun GptPet(thinking: Boolean = false, waiting: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = LocalPetAnimations.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val y=if(animate) { val motion=rememberInfiniteTransition(label="pet"); val offset by motion.animateFloat(0f,-5f,infiniteRepeatable(tween(2300,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="breath"); offset } else 0f
    var touched by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(touched && animate) 1.06f else 1f,label="pet-touch")
    LaunchedEffect(touched) { if(touched) { kotlinx.coroutines.delay(450); touched=false } }
    Image(painterResource(if(thinking) R.drawable.gpt_thinking else if(waiting) R.drawable.gpt_waiting else if(touched) R.drawable.gpt_welcome else R.drawable.gpt_idle), "GPT 娘", modifier.graphicsLayer { translationY = if(animate) y else 0f; scaleX=scale; scaleY=scale }.clickable { touched=true; onClick?.invoke() })
}
@Composable fun SectionTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = QSpace.m, bottom = QSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleLarge); subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) } }
        trailing?.invoke()
    }
}
@Composable fun SoftTag(text: String, lavender: Boolean = false) { Text(text, color = if(lavender) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis, modifier = Modifier.clip(CircleShape).background(if(lavender) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 3.dp)) }
@Composable fun EmptyCard(text: String, hint: String, onClick: () -> Unit) {
    QCard(Modifier.fillMaxWidth(),onClick=onClick) {
        Row(Modifier.padding(QSpace.xl), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.EditNote,size=48.dp)
            Column(Modifier.padding(start = 14.dp)) { Text(text,style=MaterialTheme.typography.titleSmall); Text(hint,style=MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
fun periodLabel(value: String?) = when(value) { "morning" -> "上午"; "afternoon" -> "下午"; "evening" -> "晚上"; else -> "待安排" }
fun itemTime(item: PlanItem): String = item.schedule?.time ?: item.schedule?.period?.let(::periodLabel) ?: item.schedule?.rangeStart?.let { "${it.substring(5)} — ${item.schedule?.rangeEnd?.substring(5)}" } ?: "随时"
fun dayOccurrence(item: PlanItem, day: LocalDate): PlanItem? {
    return ScheduleEngine.onDate(item,day)
}
/** Hairline border and white container so plain M3 Cards match [QCard]. */
@Composable fun qCardBorder() = BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))
@Composable fun qCardColors() = CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)
