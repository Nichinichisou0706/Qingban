package io.github.ming.qingban

import android.content.*
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.ming.qingban.platform.RingingService
import io.github.ming.qingban.ui.RingingScreen
import kotlinx.coroutines.launch

class RingActivity : ComponentActivity() {
    private var generation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        showItem(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); showItem(intent) }
    private fun showItem(request: Intent) {
        val current = ++generation
        lifecycleScope.launch {
            val container = (application as QingbanApplication).container
            val title = container.services.items.store.get(request.getStringExtra("item_id") ?: "")?.title ?: "到点提醒"
            if(current != generation) return@launch
            setContent { RingingScreen(title, { act("stop") }, { act("snooze") }) }
        }
    }
    private fun act(action: String) { startService(Intent(this, RingingService::class.java).setAction(action)); finish() }
}
