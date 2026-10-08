package io.github.ming.qingban

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import io.github.ming.qingban.platform.*
import io.github.ming.qingban.ui.*
import kotlinx.coroutines.*
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private var assistantRequest by mutableIntStateOf(0)
    private var sharedText by mutableStateOf("")
    private var sharedSongText by mutableStateOf("")
    private val preview by lazy { AudioPreview(this) }
    private val container get() = (application as QingbanApplication).container
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { lifecycleScope.launch { container.services.items.restoreSchedules() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null) receiveIntent(intent) else {
            assistantRequest=savedInstanceState.getInt("assistant_request")
            sharedText=savedInstanceState.getString("shared_text").orEmpty()
        }
        val platform = AndroidUiActions()
        setContent { QingbanApp(UiDependencies(container.services, container.assistant, platform), assistantRequest, sharedText) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveIntent(intent) }
    override fun onResume() { super.onResume(); lifecycleScope.launch { container.services.focus.load(); container.services.habits.load(); container.services.review.refresh(); DailyReviewScheduler.schedule(this@MainActivity) } }
    override fun onSaveInstanceState(outState:Bundle) { outState.putInt("assistant_request",assistantRequest); outState.putString("shared_text",sharedText); super.onSaveInstanceState(outState) }
    private fun receiveIntent(intent: Intent) {
        if(intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val incoming=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(8000)
            val prefs=getSharedPreferences("music_selection",MODE_PRIVATE)
            if(prefs.getBoolean("waiting",false) && System.currentTimeMillis()-prefs.getLong("opened_at",0)<15*60_000 && io.github.ming.qingban.domain.MusicLinks.sharedUrl(incoming)!=null) { sharedSongText=incoming; prefs.edit().putString("shared",incoming).apply() }
            else { sharedText=incoming; assistantRequest++ }
        }
        else if(intent.getBooleanExtra("open_assistant",false)) { sharedText = ""; assistantRequest++ }
        else if(intent.getBooleanExtra("open_focus",false)) lifecycleScope.launch { container.services.ui.open("focus") }
        else intent.getStringExtra("open_route")?.let { route -> lifecycleScope.launch { if(route in setOf("today","calendar","memo","alarm","focus","habits")) container.services.ui.open(route) } }
    }
    override fun onStop() { preview.stop(); super.onStop() }
    inner class AndroidUiActions : PlatformActions {
        override fun appInfo(): Map<String,String> = mapOf("version" to (packageManager.getPackageInfo(packageName,0).versionName ?: ""),"package" to packageName)
        override fun permissions(): Map<String, Boolean> = mapOf(
            "notifications" to (getSystemService(NotificationManager::class.java).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)),
            "exact_alarm" to (Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()),
            "full_screen" to (Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()),
            "overlay" to Settings.canDrawOverlays(this@MainActivity),
            "battery" to getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        )
        override fun requestPermission(kind: String) {
            if(kind == "notifications" && Build.VERSION.SDK_INT >= 33) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return }
            val settings = when(kind) {
                "exact_alarm" -> if(Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                "full_screen" -> if(Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"))
                else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            runCatching { startActivity(settings) }.onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        override fun startPet() {
            if(!Settings.canDrawOverlays(this@MainActivity)) { requestPermission("overlay"); return }
            if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return }
            startForegroundService(Intent(this@MainActivity, PetOverlayService::class.java))
        }
        override fun stopPet() { stopService(Intent(this@MainActivity, PetOverlayService::class.java)) }
        override fun pinWidget() {
            val manager = getSystemService(AppWidgetManager::class.java)
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this@MainActivity, QingbanWidget::class.java), null, null)
        }
        override suspend fun readText(uri: String, keepOriginal: Boolean): ImportedText = withContext(Dispatchers.IO) {
            val parsed = container.documents.read(Uri.parse(uri)); ImportedText(parsed.title,parsed.text,if(keepOriginal) container.originals.keep(Uri.parse(uri),parsed.title) else null)
        }
        override fun openOriginal(attachment: io.github.ming.qingban.domain.KnowledgeAttachment) {
            val file=container.originals.file(attachment.id)
            require(file.isFile) { "原文件未随备份恢复，请重新导入" }
            val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"$packageName.documents",file,attachment.fileName)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"查看 ${attachment.fileName}"))
        }
        override suspend fun removeOriginal(id: String) { if(container.services.knowledge.store.all().none { it.attachment?.id==id }) container.originals.discard(id) }
        override suspend fun importMusic(uri: String) = container.music.import(Uri.parse(uri))
        override suspend fun fetchWeb(url: String): ImportedText { val parsed=WebImporter().fetch(url); return ImportedText(parsed.title,"来源：$url\n\n${parsed.text}") }
        override suspend fun musicLibrary() = withContext(Dispatchers.IO) { container.music.library().map { MusicTrack(it.first,it.second) } }
        override suspend fun remotePlaylist(url: String) = RemoteMusic().playlist(url).map { RemoteTrack(it.url,it.title) }
        override suspend fun cacheMusic(url: String, title: String?) = container.music.cache(url,title)
        override fun previewMusic(path: String?, headphonesOnly: Boolean) = preview.play(path,headphonesOnly)
        override fun stopPreview() = preview.stop()
        override fun sharedSong() = sharedSongText.ifBlank { getSharedPreferences("music_selection",MODE_PRIVATE).getString("shared","").orEmpty() }
        override fun endSongSelection() { sharedSongText=""; getSharedPreferences("music_selection",MODE_PRIVATE).edit().clear().apply() }
        override fun openNetease() {
            sharedSongText=""; getSharedPreferences("music_selection",MODE_PRIVATE).edit().putBoolean("waiting",true).putLong("opened_at",System.currentTimeMillis()).remove("shared").apply()
            val launch=packageManager.getLaunchIntentForPackage("com.netease.cloudmusic")
            startActivity(launch ?: Intent(Intent.ACTION_VIEW,Uri.parse("https://music.163.com")))
        }
        override fun petSettings(): Map<String,String> { val prefs = getSharedPreferences("pet_settings",MODE_PRIVATE); return mapOf("size" to prefs.getInt("size",82).toString(),"alpha" to prefs.getFloat("alpha",1f).toString(),"animations" to prefs.getBoolean("animations",true).toString()) }
        override fun savePetSettings(size: Int, alpha: Float, animations: Boolean) { getSharedPreferences("pet_settings",MODE_PRIVATE).edit().putInt("size",size.coerceIn(48,130)).putFloat("alpha",alpha.coerceIn(0.3f,1f)).putBoolean("animations",animations).apply(); stopPet() }
        override fun hidePet() { if(getSharedPreferences("pet_settings",MODE_PRIVATE).getBoolean("running",false)) startService(Intent(this@MainActivity,PetOverlayService::class.java).setAction("hide")) }
        override suspend fun exportPortable(uri: String) = container.portable.export(Uri.parse(uri))
        override suspend fun restorePortable(uri: String) { container.portable.restore(Uri.parse(uri)); container.assistant.reloadFromStorage(); container.services.items.restoreSchedules(); container.services.focus.reload(); container.services.habits.reload(); container.services.review.refresh() }
        override suspend fun evaluateModel(conversations: Boolean,scenario: String?): String = withContext(Dispatchers.IO) { container.evaluateModel(conversations,scenario) }
        override fun modelSummary() = container.secrets.summary()
        override fun modelProfiles() = container.secrets.profiles()
        override fun activeModelId() = container.secrets.activeId()
        override fun saveModelProfile(profile: io.github.ming.qingban.domain.ModelProfile, key: String, makeActive: Boolean) = container.secrets.saveProfile(profile, key, makeActive)
        override fun activateModel(id: String?) = container.secrets.activate(id)
        override fun deleteModelProfile(id: String) = container.secrets.delete(id)
        override suspend fun discoverModels(base: String, key: String, retainedProfileId: String?): List<String> {
            val normalized = io.github.ming.qingban.domain.ModelConnectionAddress.validate(base)
            val secret = key.trim().takeIf { it.isNotEmpty() } ?: run {
                val saved = container.secrets.profiles().firstOrNull { it.id == retainedProfileId }
                require(saved?.baseUrl == normalized) { "请填写当前接口的密钥" }
                container.secrets.apiKey(retainedProfileId) ?: error("密钥不可用，请重新填写")
            }
            return io.github.ming.qingban.model.ModelDiscoveryClient().discover(normalized, secret)
        }
        override fun saveModel(base: String, model: String, key: String, jsonMode: Boolean) {
            val uri = URI(base.trim())
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "请使用 HTTPS 接口地址" }
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "接口地址不正确" }
            container.secrets.save(base, model, key, jsonMode)
        }
        override fun clearModel() = container.secrets.clear()
        override suspend fun exportBackup(uri: String) = withContext(Dispatchers.IO) { contentResolver.openOutputStream(Uri.parse(uri))?.bufferedWriter()?.use { it.write(container.backups.export()) } ?: error("无法保存备份") }
        override suspend fun restoreBackup(uri: String) = withContext(Dispatchers.IO) {
            val raw = contentResolver.openInputStream(Uri.parse(uri))?.reader(Charsets.UTF_8)?.use { reader ->
                val builder = StringBuilder(); val buffer = CharArray(8192)
                while(true) { val count = reader.read(buffer); if(count < 0) break; builder.append(buffer, 0, count); require(builder.length <= 16_000_000) { "备份过大" } }
                builder.toString()
            } ?: error("无法读取备份")
            container.backups.restore(raw); container.services.items.restoreSchedules(); container.services.focus.reload(); container.services.habits.reload(); container.services.review.refresh(); Unit
        }
    }
}
