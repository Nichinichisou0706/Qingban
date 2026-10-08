package io.github.ming.qingban.platform

import android.content.Context
import io.github.ming.qingban.domain.Services
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

enum class PetVisual { IDLE, THINKING, WAITING, SUCCESS, REMINDER, ERROR }
interface AndroidDependencyOwner {
    val services: Services
    val petDrawable: Int
    val petDrawables: Map<PetVisual,Int> get() = PetVisual.entries.associateWith { petDrawable }
    val petState: kotlinx.coroutines.flow.Flow<PetVisual> get() = kotlinx.coroutines.flow.flowOf(PetVisual.IDLE)
    val appForeground: kotlinx.coroutines.flow.StateFlow<Boolean>
}
internal val Context.dependencies get() = applicationContext as AndroidDependencyOwner
class DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}
