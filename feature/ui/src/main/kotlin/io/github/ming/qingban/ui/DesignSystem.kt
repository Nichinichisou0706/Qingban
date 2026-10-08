package io.github.ming.qingban.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import io.github.ming.qingban.domain.*
import java.time.LocalDate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

val LocalPetAnimations = compositionLocalOf { true }

private val light = lightColorScheme(primary = Color(0xFF237B61), onPrimary = Color.White, primaryContainer = Color(0xFFDDF3E7), secondary = Color(0xFF75668F), secondaryContainer = Color(0xFFEDE8F7), background = Color(0xFFF6FAF8), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEBF2EE), onSurface = Color(0xFF263E35), onSurfaceVariant = Color(0xFF61776C), outlineVariant = Color(0xFFDDE8E1))
private val dark = darkColorScheme(primary = Color(0xFF93D8BB), primaryContainer = Color(0xFF1E4838), secondary = Color(0xFFCABCE4), secondaryContainer = Color(0xFF40364F), background = Color(0xFF141F1B), surface = Color(0xFF1D2B25), surfaceVariant = Color(0xFF2B3B33), onSurface = Color(0xFFE3EEE7), onSurfaceVariant = Color(0xFFB0C4B7), outlineVariant = Color(0xFF354C3E))
@Composable fun QingbanTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = if(isSystemInDarkTheme()) dark else light, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content) }
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) } }
        trailing?.invoke()
    }
}
@Composable fun SoftTag(text: String, lavender: Boolean = false) { Text(text, color = if(lavender) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if(lavender) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 4.dp)) }
@Composable fun EmptyCard(text: String, hint: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.EditNote,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) { Text(text, fontWeight = FontWeight.Medium); Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp)) }
        }
    }
}
fun periodLabel(value: String?) = when(value) { "morning" -> "上午"; "afternoon" -> "下午"; "evening" -> "晚上"; else -> "待安排" }
fun itemTime(item: PlanItem): String = item.schedule?.time ?: item.schedule?.period?.let(::periodLabel) ?: item.schedule?.rangeStart?.let { "${it.substring(5)} — ${item.schedule?.rangeEnd?.substring(5)}" } ?: "随时"
fun dayOccurrence(item: PlanItem, day: LocalDate): PlanItem? {
    return ScheduleEngine.onDate(item,day)
}
