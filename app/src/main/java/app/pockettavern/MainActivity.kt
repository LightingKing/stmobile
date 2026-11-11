package app.pockettavern

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.pockettavern.ui.PocketApp
import app.pockettavern.ui.DoubaoWebResearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : ComponentActivity(), NativeHost {
    private lateinit var controller: AppController
    private var importKind = ""
    private var exportContent: String? = null
    private var exportSource: File? = null
    private var exportMime = "application/json"
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val name=displayName(uri)
                val bytes=withContext(Dispatchers.IO) { readBounded(uri,if(importKind=="backup") 128*1024*1024 else 32*1024*1024) }
                val kind=importKind
                if(kind=="backup") AlertDialog.Builder(this@MainActivity).setTitle("恢复备份")
                    .setMessage("将用“$name”替换本机的会话、角色、预设、记忆和技能配置。API 密钥不从备份导入。建议先导出当前备份。")
                    .setNegativeButton("取消",null).setPositiveButton("恢复") { _,_ -> controller.importBytes(kind,name,bytes) }.show()
                else controller.importBytes(kind,name,bytes)
            } catch(error:Exception) { controller.reportError("无法读取文件：${error.message}") }
        }
    }
    private var avatarCharacterId = ""
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val characterId = avatarCharacterId
        if (uri != null && characterId.isNotBlank()) lifecycleScope.launch {
            try { controller.setCharacterAvatar(characterId, withContext(Dispatchers.IO) { readBounded(uri, 16 * 1024 * 1024) }) }
            catch (error: Exception) { controller.reportError("无法读取图片：${error.message}") }
        }
    }
    override fun pickAvatar(characterId: String) { avatarCharacterId = characterId; avatarPicker.launch(arrayOf("image/*")) }
    private val attachmentPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val name=displayName(uri); val mime=contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes=withContext(Dispatchers.IO) { readBounded(uri,8*1024*1024) }
                controller.addAttachment(name,mime,bytes)
            } catch(error:Exception) { controller.reportError("无法添加附件：${error.message}") }
        }
    }
    private val exporter=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data
        if(result.resultCode==RESULT_OK && uri!=null) {
            val text=exportContent; val file=exportSource
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri,"wt")?.use { output ->
                            if(file!=null) file.inputStream().use { it.copyTo(output) } else output.write((text ?: error("导出内容已丢失，请重试")).toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开目标文件")
                    }
                    controller.reportError("文件已保存")
                } catch(error:Exception) { controller.reportError("保存失败：${error.message}") }
                finally { exportContent=null; exportSource=null }
            }
        }
    }
    private val doubaoResearchLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val requestId = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_REQUEST_ID).orEmpty()
        val chatId = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_CHAT_ID).orEmpty()
        val subject = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_SUBJECT).orEmpty()
        val categories = runCatching { JSONArray(data.getStringExtra(DoubaoWebResearchActivity.EXTRA_CATEGORIES).orEmpty()).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrDefault(emptyList())
        val text = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_ANSWER).orEmpty()
        val pageUrl = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_PAGE_URL).orEmpty()
        val links = runCatching { JSONArray(data.getStringExtra(DoubaoWebResearchActivity.EXTRA_SOURCE_LINKS).orEmpty()).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrDefault(emptyList())
        val mediaDescription = data.getStringExtra(DoubaoWebResearchActivity.EXTRA_MEDIA_DESCRIPTION).orEmpty()
        controller.importDoubaoWebAnswer(requestId, chatId, subject, categories, data.getBooleanExtra(DoubaoWebResearchActivity.EXTRA_ALLOW_FICTION, true), text, pageUrl, links, mediaDescription)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller=(application as PocketApplication).controller
        controller.host=this
        importKind=savedInstanceState?.getString("importKind") ?: ""
        savedInstanceState?.getString("exportSource")?.let { exportSource=File(it) }
        exportMime=savedInstanceState?.getString("exportMime") ?: "application/json"
        setContent { val state by controller.state.collectAsState(); PocketApp(state,controller) }
        openChatFrom(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openChatFrom(intent)
    }
    /** A 自己刷视频 notification opens the chat that sent it. */
    private fun openChatFrom(intent: Intent?) {
        if (intent?.action == VideoBrowseWorker.ACTION_OPEN_CHAT) intent.getStringExtra(VideoBrowseWorker.EXTRA_CHAT_ID)?.let(controller::openChatWhenReady)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importKind",importKind); outState.putString("exportSource",exportSource?.absolutePath); outState.putString("exportMime",exportMime)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if(controller.host===this) controller.host=null; super.onDestroy() }
    override fun requestGenerationNotifications() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("notification-permission", MODE_PRIVATE)
        if (!preferences.getBoolean("requested", false)) {
            preferences.edit().putBoolean("requested", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onStart() { super.onStart(); controller.uiVisible = true }
    override fun onStop() {
        controller.uiVisible = false
        controller.checkpoint()
        if (!isChangingConfigurations) controller.onBackgrounded()
        super.onStop()
    }
    override fun importFile(kind: String) { importKind=kind; importer.launch(arrayOf("*/*")) }
    override fun pickAttachment() { attachmentPicker.launch(arrayOf("image/*","audio/*","text/*","application/json","application/octet-stream")) }
    override fun openDoubaoWebResearch(requestId: String, chatId: String, subject: String, categories: List<String>, allowFiction: Boolean, prompt: String) {
        val intent = Intent(this, DoubaoWebResearchActivity::class.java)
            .putExtra(DoubaoWebResearchActivity.EXTRA_REQUEST_ID, requestId)
            .putExtra(DoubaoWebResearchActivity.EXTRA_CHAT_ID, chatId)
            .putExtra(DoubaoWebResearchActivity.EXTRA_SUBJECT, subject)
            .putExtra(DoubaoWebResearchActivity.EXTRA_CATEGORIES, JSONArray(categories).toString())
            .putExtra(DoubaoWebResearchActivity.EXTRA_ALLOW_FICTION, allowFiction)
            .putExtra(DoubaoWebResearchActivity.EXTRA_PROMPT, prompt)
        doubaoResearchLauncher.launch(intent)
    }
    override fun exportText(name: String, mime: String, content: String) {
        val file=File(cacheDir,"exports/${java.util.UUID.randomUUID()}.txt"); file.parentFile?.mkdirs(); file.writeText(content)
        exportBinary(name,mime,file)
    }
    override fun exportBinary(name: String, mime: String, file: File) {
        exportContent=null; exportSource=file; exportMime=mime
        exporter.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name))
    }
    override fun openFile(file: File, mime: String) {
        try {
            val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(error:Exception) { controller.reportError("没有可打开此文件的应用，请先保存文件") }
    }
    private fun displayName(uri: Uri): String = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "import" } ?: "import"
    private fun readBounded(uri: Uri, max: Int): ByteArray = contentResolver.openInputStream(uri)?.use { input ->
        val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer); if(count<0) break; require(output.size()+count<=max) { "文件超过 ${max/1024/1024} MB 限制" }; output.write(buffer,0,count) }
        output.toByteArray()
    } ?: error("文件不可读")
}
