package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.*
import java.io.File

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active: PlanItem? = null
    private var wake: PowerManager.WakeLock? = null
    private var stopJob: Job? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var routedToHeadphones = false
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if(routedToHeadphones && headphone() == null) { stopSound(); vibrate() }
        }
    }
    override fun onCreate() {
        super.onCreate(); createChannels(this); audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        scope.launch { dependencies.services.items.store.observe().collect { items -> active?.let { current -> if(items.none { it.id == current.id && it.version == current.version && it.status == ItemStatus.OPEN }) finishRing() } } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "stop" -> { finishRing(); return START_NOT_STICKY }
            "snooze" -> {
                scope.launch {
                    val item = active
                    val latest = item?.let { withContext(Dispatchers.IO) { dependencies.services.items.store.get(it.id) } }
                    if(item == null || latest?.version != item.version || latest.status != ItemStatus.OPEN) { finishRing(); return@launch }
                    runCatching { AndroidReminderScheduler.snooze(this@RingingService, item) }.onSuccess {
                        val selected=java.time.LocalDateTime.now(dependencies.services.clock).plusMinutes(item.alarm.snoozeMinutes.toLong())
                        runCatching { withContext(Dispatchers.IO) { dependencies.services.memory.recordChoice(item.copy(schedule=ScheduleSpec(selected.toLocalDate().toString(),selected.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))))) } }; finishRing()
                    }.onFailure {
                        startForeground(701, NotificationCompat.Builder(this@RingingService, ALARM_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("稍后提醒未能设置").setContentText("请检查精确闹钟权限，当前响铃仍可手动停止").setOngoing(true).addAction(0, "停止", PendingIntent.getService(this@RingingService, "stop".hashCode(), Intent(this@RingingService, RingingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)).build())
                    }
                }
                return START_NOT_STICKY
            }
        }
        startForeground(701, NotificationCompat.Builder(this, ALARM_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("正在准备响铃").build())
        val requestGeneration = ++generation
        scope.launch {
            val item = withContext(Dispatchers.IO) { dependencies.services.items.store.get(intent?.getStringExtra("item_id") ?: "") }
            if(requestGeneration != generation) return@launch
            if(item == null || item.version != intent?.getIntExtra("version", -1) || item.status != ItemStatus.OPEN) { finishRing(); return@launch }
            clearPlayback(); active = item
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"qingban:alarm").apply { setReferenceCounted(false); acquire(10*60_000L+15_000) }
            val ringing = Intent().setClassName(packageName, "$packageName.RingActivity").putExtra("item_id", item.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val fullScreen = PendingIntent.getActivity(this@RingingService, 701, ringing, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            fun action(name: String) = PendingIntent.getService(this@RingingService, name.hashCode(), Intent(this@RingingService, RingingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startForeground(701, NotificationCompat.Builder(this@RingingService, ALARM_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(item.title).setContentText("铭哥，到点啦").setOngoing(true).setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX).setContentIntent(fullScreen).setFullScreenIntent(fullScreen, true).addAction(0, "停止", action("stop")).addAction(0, "稍后 ${item.alarm.snoozeMinutes} 分钟", action("snooze")).build())
            if(item.alarm.vibrate) vibrate()
            val preferred = headphone()
            if(item.alarm.playAudio) { if(item.alarm.headphonesOnly && (preferred == null || Build.VERSION.SDK_INT < 28)) vibrate() else play(item, preferred) }
            stopJob = scope.launch { delay(10 * 60_000L); finishRing() }
        }
        return START_NOT_STICKY
    }
    private fun headphone() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
    private fun play(item: PlanItem, device: AudioDeviceInfo?) {
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener { change -> if(active != null && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { stopSound(); vibrate() } }.build()
            require(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "音频暂不可用" }; focus = request
            val instance = MediaPlayer(); player = instance
            instance.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                val custom = item.alarm.soundPath?.let(::File)?.takeIf { it.exists() }
                setDataSource(this@RingingService, custom?.let { Uri.fromFile(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                if(Build.VERSION.SDK_INT >= 28) { val routed = setPreferredDevice(device); if(item.alarm.headphonesOnly) require(routed) { "无法使用耳机" } }
                routedToHeadphones = device != null
                setOnErrorListener { _, _, _ -> stopSound(); vibrate(); true }
                val requestGeneration=generation
                setOnPreparedListener { prepared -> if(requestGeneration==generation && player===prepared && active?.id==item.id) prepared.start() }
                prepareAsync()
            }
        }.onFailure { stopSound(); vibrate() }
    }
    private fun vibrate() {
        vibrator = if(Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600, 400, 1200), 0))
    }
    private fun stopSound() { player?.release(); player = null; focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null; routedToHeadphones = false }
    private fun clearPlayback() { stopJob?.cancel(); stopJob = null; stopSound(); vibrator?.cancel(); wake?.let { if(it.isHeld) it.release() }; wake=null }
    private fun finishRing() { generation++; clearPlayback(); active = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { clearPlayback(); audio.unregisterAudioDeviceCallback(devices); scope.cancel(); super.onDestroy() }
}
