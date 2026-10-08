package io.github.ming.qingban.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Clock

private val rotationSaver=mapSaver(
    save={ rotation: CopyRotation -> rotation.snapshot() },
    restore={ saved -> CopyRotation.restore(saved.mapValues { it.value as Int }) }
)
private val LocalCopyRotation=compositionLocalOf<CopyRotation?> { null }
val LocalCopyTime=compositionLocalOf { CopyTime.now(Clock.systemDefaultZone()) }

@Composable fun SmartCopyProvider(clock: Clock = Clock.systemDefaultZone(), content: @Composable () -> Unit) {
    val rotation=rememberSaveable(saver=rotationSaver) { CopyRotation(clock.millis().hashCode()) }
    val owner=LocalLifecycleOwner.current
    val time by produceState(CopyTime.now(clock),clock,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(isActive) { value=CopyTime.now(clock); delay(60_000) }
        }
    }
    CompositionLocalProvider(LocalCopyRotation provides rotation,LocalCopyTime provides time,content=content)
}

// Choose on entry or meaningful context changes, never on ordinary recomposition/typing.
@Composable fun rotatingCopy(slot: String, pool: CopyPool, contextKey: String = ""): String {
    val provided=LocalCopyRotation.current
    val rotation=provided ?: rememberSaveable(saver=rotationSaver) { CopyRotation(System.currentTimeMillis().hashCode()) }
    val time=LocalCopyTime.current
    val index=rememberSaveable(slot,pool.id,time.key,contextKey) { rotation.next(slot,pool.choices.size) }
    return pool.choices[Math.floorMod(index,pool.choices.size)]
}
