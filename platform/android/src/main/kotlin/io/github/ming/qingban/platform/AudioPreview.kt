package io.github.ming.qingban.platform

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

class AudioPreview(private val context: Context) {
    private var generation=0
    private var player: MediaPlayer? = null
    private val audio=context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest?=null
    private var watching=false
    private fun headphone()=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_USB_HEADSET) || (Build.VERSION.SDK_INT>=31 && it.type==AudioDeviceInfo.TYPE_BLE_HEADSET) }
    private val devices=object : AudioDeviceCallback() { override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { if(watching && headphone()==null) stop() } }
    private val handler = Handler(Looper.getMainLooper())
    private val stop = Runnable { stop() }
    fun play(path: String?, headphonesOnly: Boolean) {
        stop()
        val current=++generation
        val headphone = headphone()
        require(!headphonesOnly || (headphone != null && Build.VERSION.SDK_INT >= 28)) { "仅耳机试听需要已连接的耳机和 Android 9+" }
        val next = MediaPlayer()
        try {
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()).setOnAudioFocusChangeListener { change -> if(change in setOf(AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) stop() }.build()
            require(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "暂时无法试听，请稍后再试" }; focus=request
            if(headphonesOnly) { watching=true; audio.registerAudioDeviceCallback(devices,handler) }
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            if(headphonesOnly && Build.VERSION.SDK_INT >= 28) require(next.setPreferredDevice(headphone)) { "无法设置耳机音频路由" }
            if(path != null) { require(File(path).isFile); next.setDataSource(path) } else next.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            player=next
            next.setOnCompletionListener { stop() }; next.setOnErrorListener { _,_,_ -> stop(); true }
            next.setOnPreparedListener { prepared -> if(current==generation && player===prepared) { prepared.start(); handler.postDelayed(stop,15_000) } }
            next.prepareAsync()
        } catch(e: Exception) { if(player!==next) next.release(); stop(); throw e }
    }
    fun stop() { generation++; handler.removeCallbacks(stop); player?.release(); player = null; focus?.let { audio.abandonAudioFocusRequest(it) }; focus=null; if(watching) audio.unregisterAudioDeviceCallback(devices); watching=false }
}
