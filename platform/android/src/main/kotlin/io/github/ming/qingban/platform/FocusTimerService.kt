package io.github.ming.qingban.platform

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Runs only during an active countdown; the persisted domain timer remains authoritative. */
class FocusTimerService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var ticker: Job?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("qingban_focus_running","正在计时",NotificationManager.IMPORTANCE_LOW).apply { setSound(null,null) })
        val launch=packageManager.getLaunchIntentForPackage(packageName)?.putExtra("open_focus",true)
        val action=PendingIntent.getActivity(this,7303,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end=intent?.getLongExtra("end",System.currentTimeMillis()) ?: System.currentTimeMillis()
        startForeground(7303,NotificationCompat.Builder(this,"qingban_focus_running")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if(intent?.getStringExtra("phase")=="rest") "休息计时中" else "番茄专注中")
            .setContentText(intent?.getStringExtra("title") ?: "轻伴计时").setContentIntent(action)
            .setWhen(end).setUsesChronometer(true).setChronometerCountDown(true).setOnlyAlertOnce(true).setOngoing(true).build())
        if(ticker?.isActive!=true) ticker=scope.launch {
            val timer=dependencies.services.focus
            while(isActive) {
                // Completion persists and posts its notification even as the service stops itself.
                withContext(NonCancellable) { timer.tick() }
                val run=timer.state.value.run
                if(run?.status!="running" || run.mode!="pomodoro") { stopSelf(); break }
                delay(1000)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
