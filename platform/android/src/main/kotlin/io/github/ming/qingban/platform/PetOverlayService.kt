package io.github.ming.qingban.platform

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.animation.ObjectAnimator
import kotlinx.coroutines.*
import io.github.ming.qingban.domain.CompanionMenuLayout

class PetOverlayService : Service() {
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var pet: FrameLayout?=null
    private var portrait: ImageView?=null
    private var menu: PetRadialView?=null
    private var bubble: TextView?=null
    private var animation: ObjectAnimator?=null
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var visual=PetVisual.IDLE
    private var hiddenUntil=0L
    private var expanded=false
    private var fading=false
    private var dragging=false
    private var baseAlpha=1f
    private val size=46
    private var compactX=0
    private var compactY=0
    private var outwardRight=true
    private val density get()=resources.displayMetrics.density
    private fun dp(n: Float)=(n*density).toInt()
    private lateinit var layout: WindowManager.LayoutParams
    private val idle: Runnable=object: Runnable {
        override fun run() {
            if(dragging) { handler.postDelayed(this,5000); return }
            collapse(); fading=true; pet?.animate()?.alpha((baseAlpha*0.2f).coerceAtMost(0.2f))?.setDuration(350)?.start()
            describe(); animation?.pause()
        }
    }
    private val screen=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) { if(intent.action==Intent.ACTION_SCREEN_OFF) hide() else show() }
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this,screen,IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { dependencies.petState.collect { visual=it; portrait?.setImageResource(dependencies.petDrawables[it] ?: dependencies.petDrawable) } }
        scope.launch { dependencies.appForeground.collect { if(it) hide() else show() } }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop" || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if(intent?.action=="hide") { hiddenUntil=SystemClock.elapsedRealtime()+600_000; hide(); handler.postDelayed({ show() },600_000); return START_NOT_STICKY }
        if(intent?.action=="refresh") { hide(); show(); return START_NOT_STICKY }
        createChannels(this)
        val stop=PendingIntent.getService(this,811,Intent(this,PetOverlayService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(811,NotificationCompat.Builder(this,PET_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("GPT 娘陪伴已开启").setContentText("点击展开快捷功能 · 5 秒闲置淡化").addAction(0,"关闭桌宠",stop).setOngoing(true).build())
        getSharedPreferences("pet_settings",MODE_PRIVATE).edit().putBoolean("running",true).apply()
        show()
        if(intent?.action=="bubble") { wake(); bubble?.apply { text=intent.getStringExtra("title")?.take(24); visibility=View.VISIBLE }; handler.postDelayed({ bubble?.visibility=View.GONE },5000) }
        return START_NOT_STICKY
    }
    private fun show() {
        if(pet!=null || dependencies.appForeground.value || !Settings.canDrawOverlays(this) || SystemClock.elapsedRealtime()<hiddenUntil || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val settings=getSharedPreferences("pet_settings",MODE_PRIVATE)
         baseAlpha=settings.getFloat("alpha",1f).coerceIn(0.3f,1f); expanded=false; fading=false
        val prefs=getSharedPreferences("pet_position",MODE_PRIVATE)
        layout=WindowManager.LayoutParams(dp(size.toFloat()),dp(76f),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START; x=prefs.getInt("x",resources.displayMetrics.widthPixels-width); y=prefs.getInt("y",(resources.displayMetrics.heightPixels*0.55).toInt())
        }
        val root=FrameLayout(this).apply { alpha=baseAlpha; clipChildren=false }; pet=root
        val radial=PetRadialView(this) { route ->
            wake()
            when(route) {
                "close" -> collapse()
                "motion" -> {
                    val next=(settings.getInt("motion",0)+1)%4; settings.edit().putInt("motion",next).apply(); startMotion()
                    Toast.makeText(this,"动态：${listOf("呼吸","轻跳","摇摆","静止")[next]}",Toast.LENGTH_SHORT).show()
                }
                else -> { collapse(); val launch=packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if(route=="assistant") launch?.putExtra("open_assistant",true) else launch?.putExtra("open_route",route)
                    launch?.let(::startActivity)
                }
            }
        }; menu=radial; radial.visibility=View.GONE; root.addView(radial,FrameLayout.LayoutParams(dp(CompanionMenuLayout.WIDTH.toFloat()),dp(CompanionMenuLayout.HEIGHT.toFloat())))
        val image=PetImageView(this).apply { setImageResource(dependencies.petDrawables[visual] ?: dependencies.petDrawable); scaleType=ImageView.ScaleType.FIT_CENTER; setOnClickListener { wake(); if(expanded) collapse() else expand() } }; portrait=image
        root.addView(image,FrameLayout.LayoutParams(layout.width,layout.height,Gravity.CENTER))
        bubble=TextView(this).apply { visibility=View.GONE; textSize=10f; setTextColor(android.graphics.Color.rgb(30,75,56)); setBackgroundColor(android.graphics.Color.rgb(221,243,231)); maxLines=2 }.also { root.addView(it,FrameLayout.LayoutParams(-1,-2,Gravity.TOP)) }
        var x=0f; var y=0f; var oldX=0; var oldY=0; var moved=false
        image.setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { wake(); dragging=true; x=event.rawX; y=event.rawY; oldX=layout.x; oldY=layout.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> { val dx=event.rawX-x; val dy=event.rawY-y
                    if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(10f)) moved=true
                    if(moved) { val previousX=layout.x; val previousY=layout.y; layout.x=oldX+dx.toInt(); layout.y=oldY+dy.toInt(); clamp()
                        if(expanded) { compactX+=layout.x-previousX; compactY+=layout.y-previousY }; windows.updateViewLayout(root,layout) }; true }
                MotionEvent.ACTION_UP -> { dragging=false; wake()
                    if(moved) { collapse(); layout.x=if(layout.x<resources.displayMetrics.widthPixels/2) 0 else resources.displayMetrics.widthPixels-layout.width; clamp(); windows.updateViewLayout(root,layout); prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply() }
                    else view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { dragging=false; wake(); true }
                else -> false
            }
        }
        clamp(); describe()
        runCatching { windows.addView(root,layout); startMotion(); wake() }.onFailure { pet=null; stopSelf() }
    }
    private fun resize(open: Boolean) {
        val root=pet ?: return
        if(open) { compactX=layout.x; compactY=layout.y; outwardRight=layout.x+layout.width/2<resources.displayMetrics.widthPixels/2 }
        expanded=open; layout.width=dp(if(open) CompanionMenuLayout.WIDTH.toFloat() else size.toFloat()); layout.height=dp(if(open) CompanionMenuLayout.HEIGHT.toFloat() else 76f)
        val portraitLeft=CompanionMenuLayout.portraitLeft(outwardRight)
        layout.x=if(open) compactX-dp(portraitLeft.toFloat()) else compactX; layout.y=if(open) compactY-dp(CompanionMenuLayout.PORTRAIT_TOP.toFloat()) else compactY; clamp()
        menu?.arrange(outwardRight)
        menu?.visibility=if(open) View.VISIBLE else View.GONE
        portrait?.layoutParams=FrameLayout.LayoutParams(dp(size.toFloat()),dp(76f),Gravity.TOP or Gravity.START).apply { if(open) { leftMargin=dp(portraitLeft.toFloat()); topMargin=dp(CompanionMenuLayout.PORTRAIT_TOP.toFloat()) } }
        windows.updateViewLayout(root,layout); startMotion(); describe()
        if(open) menu?.apply { alpha=0f; scaleX=.94f; scaleY=.94f; animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start() }
    }
    private fun expand()=resize(true)
    private fun collapse() { if(expanded) resize(false) }
    private fun clamp() { layout.x=layout.x.coerceIn(0,(resources.displayMetrics.widthPixels-layout.width).coerceAtLeast(0)); layout.y=layout.y.coerceIn(dp(24f),(resources.displayMetrics.heightPixels-layout.height-dp(30f)).coerceAtLeast(dp(24f))) }
    private fun describe() { portrait?.contentDescription="GPT 娘，${if(expanded) "快捷功能已展开，点击收起" else "点击展开快捷功能"}，${if(fading) "闲置淡化" else "已唤醒"}，拖动移动" }
    private fun wake() { handler.removeCallbacks(idle); fading=false; pet?.animate()?.cancel(); pet?.alpha=baseAlpha; animation?.resume(); describe(); handler.postDelayed(idle,5000) }
    private fun startMotion() {
        animation?.cancel(); val image=portrait ?: return; image.translationY=0f; image.rotation=0f; image.scaleX=1f; image.scaleY=1f
        val settings=getSharedPreferences("pet_settings",MODE_PRIVATE)
        if(!settings.getBoolean("animations",true)) return
        animation=when(settings.getInt("motion",0)) {
            1 -> ObjectAnimator.ofFloat(image,View.TRANSLATION_Y,0f,-7*density,0f).apply { duration=1400 }
            2 -> ObjectAnimator.ofFloat(image,View.ROTATION,-4f,4f,-4f).apply { duration=2400 }
            3 -> null
            else -> ObjectAnimator.ofFloat(image,View.TRANSLATION_Y,0f,-3*density,0f).apply { duration=3500 }
        }?.apply { repeatCount=ObjectAnimator.INFINITE; start() }
    }
    private fun hide() { handler.removeCallbacks(idle); animation?.cancel(); animation=null; pet?.let { runCatching { windows.removeView(it) } }; pet=null; portrait=null; menu=null; bubble=null; dragging=false }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); hide(); show() }
    override fun onDestroy() { hide(); handler.removeCallbacksAndMessages(null); scope.cancel(); getSharedPreferences("pet_settings",MODE_PRIVATE).edit().putBoolean("running",false).apply(); unregisterReceiver(screen); super.onDestroy() }
}
@android.annotation.SuppressLint("AppCompatCustomView")
private class PetImageView(context: Context): ImageView(context) { override fun performClick(): Boolean { super.performClick(); return true } }
