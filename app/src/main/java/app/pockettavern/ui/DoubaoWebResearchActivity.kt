package app.pockettavern.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Visible, user-account-backed Doubao web research bridge.
 *
 * This activity deliberately automates only the rendered page. It does not
 * inspect cookies, tokens, storage, or the site's private network endpoints.
 * The page remains visible so a user can complete login or CAPTCHA and then
 * resume the pending prompt.
 */
class DoubaoWebResearchActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var continueButton: Button
    private lateinit var subjectView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var automationStarted = false
    private var sent = false
    private var awaitingUser = false
    private var pollStartedAt = 0L
    private var pollRunnable: Runnable? = null
    private var beforeSignatures = emptySet<String>()
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val attachmentUris = ArrayList<Uri>()

    private val fileChooserRequestCode = 4107

    private val subject: String by lazy { intent.getStringExtra(EXTRA_SUBJECT).orEmpty().trim() }
    private val prompt: String by lazy { intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim() }
    private val requestId: String by lazy { intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().trim() }
    private val chatId: String by lazy { intent.getStringExtra(EXTRA_CHAT_ID).orEmpty().trim() }
    private val categories: String by lazy { intent.getStringExtra(EXTRA_CATEGORIES).orEmpty() }
    private val allowFiction: Boolean by lazy { intent.getBooleanExtra(EXTRA_ALLOW_FICTION, true) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val supplied = collectAttachmentUris(intent)
        attachmentUris.addAll(supplied.take(MAX_ATTACHMENTS))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        subjectView = TextView(this).apply {
            text = if (subject.isBlank()) "豆包网页版自动研究" else "豆包网页版：$subject"
            textSize = 16f
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(5), 0, dp(5))
            text = if (attachmentUris.isEmpty()) {
                "正在打开豆包网页…"
            } else {
                "已收到 ${attachmentUris.size} 个附件；请在豆包页面自行点上传并选择它们，网页文件选择器完成后会继续发送文字。"
            }
        }
        continueButton = Button(this).apply {
            text = "继续自动发送"
            visibility = View.GONE
            setOnClickListener { resumeAutomation() }
        }
        header.addView(subjectView)
        header.addView(statusView)
        header.addView(continueButton, LinearLayout.LayoutParams(-1, -2))

        webView = WebView(this)
        configureWebView(webView)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (prompt.isBlank()) {
            showError("没有收到要发送的研究问题。")
        } else {
            webView.loadUrl(CHAT_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (isAllowedPage(uri)) {
                    false
                } else {
                    v.loadUrl(CHAT_URL)
                    true
                }
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                return if (uri != null && isAllowedPage(uri)) {
                    false
                } else {
                    v.loadUrl(CHAT_URL)
                    true
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                pageReady = true
                if (!awaitingUser && !automationStarted && prompt.isNotBlank()) {
                    setStatus("网页已打开，正在查找输入框…")
                    handler.postDelayed({ startAutomation() }, PAGE_SETTLE_DELAY_MS)
                }
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) showError("豆包网页加载失败，请检查网络后重试。")
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val chooser = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val accepted = params.acceptTypes.filter { it.isNotBlank() }.toTypedArray()
                    if (accepted.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, accepted)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return runCatching {
                    startActivityForResult(chooser, fileChooserRequestCode)
                    true
                }.getOrElse {
                    fileChooserCallback = null
                    false
                }
            }

            override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ ->
            // Do not turn web-page downloads into an implicit external action.
            setStatus("豆包页面请求下载内容；自动研究不会下载网页文件。")
        }
    }

    private fun startAutomation() {
        if (!pageReady || automationStarted || awaitingUser || prompt.isBlank()) return
        automationStarted = true
        val promptJson = JSONObject.quote(prompt)
        val script = """
            (function() {
              const root = document.querySelector('[data-container-name="conversation-message-area"]') || document;
              const visible = (el) => {
                if (!el) return false;
                const s = getComputedStyle(el);
                return s.display !== 'none' && s.visibility !== 'hidden' && el.getAttribute('aria-hidden') !== 'true' && el.getClientRects().length > 0;
              };
              if (document.querySelector('[data-testid="to_login_button"]') || document.querySelector('#captcha_container')) {
                return JSON.stringify({kind:'needs_login'});
              }
              const input = document.querySelector('[data-testid="chat_input_input"]') || document.querySelector('textarea[contenteditable="true"]');
              const editable = input || document.querySelector('[data-testid="chat_input_input"] [contenteditable="true"]') || document.querySelector('div[contenteditable="true"].tiptap.ProseMirror');
              const send = document.querySelector('button[data-testid="chat_input_send_button"], [data-testid="chat_input_send_button"]');
              if (!editable) return JSON.stringify({kind:'wait',reason:'input'});
              if (!send || send.disabled || send.getAttribute('aria-disabled') === 'true') return JSON.stringify({kind:'wait',reason:'send'});
              const setValue = (el, value) => {
                el.focus();
                if (el.isContentEditable) {
                  document.execCommand('selectAll', false, null);
                  document.execCommand('insertText', false, value);
                } else {
                  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                  if (setter) setter.call(el, value); else el.value = value;
                }
                el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:value}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              };
              const assistant = Array.from(root.querySelectorAll('[data-testid="receive_message"], [data-message-role="assistant"], [data-role="assistant"]')).filter(el => {
                const role = (el.getAttribute('data-message-role') || el.getAttribute('data-role') || '').toLowerCase();
                return role !== 'user' && role !== 'question' && role !== 'human';
              });
              const textOf = (el) => (el.querySelector('[data-testid="message_text_content"]')?.innerText || el.innerText || '').trim();
              const signature = (el) => {
                const id = el.getAttribute('data-message-id') || el.id;
                return id ? 'id:' + id : 'text:' + textOf(el).slice(0, 1200);
              };
              const before = assistant.map(el => signature(el));
              setValue(editable, $promptJson);
              if (send.disabled || send.getAttribute('aria-disabled') === 'true') return JSON.stringify({kind:'wait',reason:'send-after-input',before:before});
              send.click();
              return JSON.stringify({kind:'sent',before:before,startedAt:Date.now()});
            })()
        """.trimIndent()
        evaluate(script) { result ->
            when (result?.optString("kind")) {
                "needs_login" -> needsLogin()
                "wait" -> {
                    automationStarted = false
                    setStatus("正在等待豆包输入框可用…")
                    handler.postDelayed({ startAutomation() }, RETRY_DELAY_MS)
                }
                "sent" -> {
                    sent = true
                    pollStartedAt = System.currentTimeMillis()
                    beforeSignatures = result.optJSONArray("before").toStringSet()
                    setStatus("已自动发送，等待豆包完成回答…")
                    startPolling()
                }
                else -> {
                    automationStarted = false
                    setStatus("豆包页面尚未准备好，正在重试…")
                    handler.postDelayed({ startAutomation() }, RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun startPolling() {
        pollRunnable?.let(handler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                if (!sent || isFinishing) return
                pollAnswer()
            }
        }
        pollRunnable = runnable
        handler.post(runnable)
    }

    private fun pollAnswer() {
        val elapsed = System.currentTimeMillis() - pollStartedAt
        if (elapsed > RESPONSE_TIMEOUT_MS) {
            showError("豆包回答等待超时；原问题仍保留在网页中，可以点“继续自动发送”重试。")
            automationStarted = false
            return
        }
        val seen = JSONArray().apply { beforeSignatures.forEach { put(it) } }
        val script = """
            (function() {
              const root = document.querySelector('[data-container-name="conversation-message-area"]') || document;
              const visible = (el) => {
                if (!el) return false;
                const s = getComputedStyle(el);
                return s.display !== 'none' && s.visibility !== 'hidden' && el.getAttribute('aria-hidden') !== 'true' && el.getClientRects().length > 0;
              };
              if (document.querySelector('[data-testid="to_login_button"]') || document.querySelector('#captcha_container')) return JSON.stringify({kind:'needs_login'});
              const pending = Array.from(root.querySelectorAll('[data-testid="message_thinking"], [data-testid="message_loading"]')).some(visible);
              const candidates = Array.from(root.querySelectorAll('[data-testid="receive_message"], [data-message-role="assistant"], [data-role="assistant"]')).filter(el => {
                const role = (el.getAttribute('data-message-role') || el.getAttribute('data-role') || '').toLowerCase();
                return role !== 'user' && role !== 'question' && role !== 'human';
              });
              const textOf = (el) => (el.querySelector('[data-testid="message_text_content"]')?.innerText || el.innerText || '').trim();
              const signature = (el) => {
                const id = el.getAttribute('data-message-id') || el.id;
                return id ? 'id:' + id : 'text:' + textOf(el).slice(0, 1200);
              };
              const known = new Set(${"$"}seenJson);
              const fresh = candidates.filter(el => !known.has(signature(el)) && textOf(el));
              if (pending || !fresh.length) return JSON.stringify({kind:'wait'});
              const answer = textOf(fresh[fresh.length - 1]);
              if (!answer) return JSON.stringify({kind:'wait'});
              return JSON.stringify({kind:'done',answer:answer,url:location.href});
            })()
        """.trimIndent().replace("${'$'}seenJson", seen.toString())
        evaluate(script) { result ->
            when (result?.optString("kind")) {
                "needs_login" -> needsLogin()
                "done" -> complete(result.optString("answer"), result.optString("url", webView.url.orEmpty()))
                else -> handler.postDelayed({ pollAnswer() }, POLL_DELAY_MS)
            }
        }
    }

    private fun resumeAutomation() {
        awaitingUser = false
        continueButton.visibility = View.GONE
        setResult(RESULT_CANCELED)
        if (sent) {
            setStatus("已继续等待豆包回答…")
            pollStartedAt = System.currentTimeMillis()
            startPolling()
        } else {
            setStatus("正在继续自动发送…")
            automationStarted = false
            startAutomation()
        }
    }

    private fun needsLogin() {
        awaitingUser = true
        automationStarted = false
        continueButton.visibility = View.VISIBLE
        setStatus("请在上方豆包页面完成登录或验证码；完成后点击“继续自动发送”。问题不会丢失。")
        setResult(RESULT_NEEDS_LOGIN, Intent().putExtra(EXTRA_ACTION, ACTION_NEEDS_LOGIN).putExtra(EXTRA_SUBJECT, subject))
    }

    private fun complete(answer: String, pageUrl: String) {
        val clean = answer.trim()
        if (clean.isBlank()) {
            showError("豆包返回了空回答，未导入当前会话。")
            automationStarted = false
            return
        }
        pollRunnable?.let(handler::removeCallbacks)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_ACTION, ACTION_RESULT)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_CATEGORIES, categories.toString())
            putExtra(EXTRA_ALLOW_FICTION, allowFiction)
            putExtra(EXTRA_SUBJECT, subject)
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_ANSWER, clean)
            putExtra(EXTRA_PAGE_URL, pageUrl)
            putExtra(EXTRA_SOURCE_LINKS, JSONArray(extractLinks(clean)).toString())
            putExtra(EXTRA_MEDIA_DESCRIPTION, "附件由用户在豆包网页中自行上传；App 未读取网页内部媒体状态。")
            putExtra(EXTRA_UNVERIFIED, true)
        })
        finish()
    }

    private fun showError(message: String) {
        setStatus(message)
        continueButton.visibility = View.VISIBLE
        continueButton.text = "重试自动发送"
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun evaluate(script: String, callback: (JSONObject?) -> Unit) {
        if (!::webView.isInitialized || isFinishing) return
        runCatching {
            webView.evaluateJavascript(script) { raw -> callback(parseJsonResult(raw)) }
        }.onFailure { showError("无法读取豆包页面状态，请重试。") }
    }

    private fun parseJsonResult(raw: String?): JSONObject? = runCatching {
        val value = JSONTokener(raw ?: "null").nextValue()
        when (value) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> null
        }
    }.getOrNull()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequestCode) return
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return
        if (resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, MAX_ATTACHMENTS)) uris += clip.getItemAt(i).uri
        } ?: data.data?.let(uris::add)
        callback.onReceiveValue(uris.distinct().take(MAX_ATTACHMENTS).toTypedArray())
    }

    override fun onDestroy() {
        pollRunnable?.let(handler::removeCallbacks)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isAllowedPage(uri: Uri): Boolean {
        return uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host.equals("doubao.com", ignoreCase = true) || uri.host.equals("www.doubao.com", ignoreCase = true))
    }

    private fun collectAttachmentUris(source: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::add)
        source.clipData?.let { clip ->
            for (i in 0 until minOf(clip.itemCount, MAX_ATTACHMENTS)) result += clip.getItemAt(i).uri
        }
        return result.distinct().take(MAX_ATTACHMENTS)
    }

    private fun extractLinks(text: String): List<String> = Regex("https://[^\\s<>\\\"]+")
        .findAll(text)
        .map { it.value.trimEnd(')', '）', ']', '】', ',', '，', '。', ';', '；') }
        .filter { runCatching { val uri = Uri.parse(it); uri.host != null && uri.userInfo == null }.getOrDefault(false) }
        .distinct()
        .take(64)
        .toList()

    private fun JSONArray?.toStringSet(): Set<String> {
        val source = this ?: return emptySet()
        return buildSet { for (i in 0 until source.length()) source.optString(i).takeIf(String::isNotBlank)?.let(::add) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val CHAT_URL = "https://www.doubao.com/chat/"
        const val ACTION_RESULT = "app.pockettavern.action.DOUBAO_WEB_RESULT"
        const val ACTION_NEEDS_LOGIN = "app.pockettavern.action.DOUBAO_WEB_NEEDS_LOGIN"
        const val EXTRA_ACTION = "app.pockettavern.extra.DOUBAO_ACTION"
        const val EXTRA_REQUEST_ID = "app.pockettavern.extra.DOUBAO_REQUEST_ID"
        const val EXTRA_CHAT_ID = "app.pockettavern.extra.DOUBAO_CHAT_ID"
        const val EXTRA_CATEGORIES = "app.pockettavern.extra.DOUBAO_CATEGORIES"
        const val EXTRA_ALLOW_FICTION = "app.pockettavern.extra.DOUBAO_ALLOW_FICTION"
        const val EXTRA_SUBJECT = "app.pockettavern.extra.DOUBAO_SUBJECT"
        const val EXTRA_PROMPT = "app.pockettavern.extra.DOUBAO_PROMPT"
        const val EXTRA_ANSWER = "app.pockettavern.extra.DOUBAO_ANSWER"
        const val EXTRA_PAGE_URL = "app.pockettavern.extra.DOUBAO_PAGE_URL"
        const val EXTRA_SOURCE_LINKS = "app.pockettavern.extra.DOUBAO_SOURCE_LINKS"
        const val EXTRA_MEDIA_DESCRIPTION = "app.pockettavern.extra.DOUBAO_MEDIA_DESCRIPTION"
        const val EXTRA_UNVERIFIED = "app.pockettavern.extra.DOUBAO_UNVERIFIED"
        const val RESULT_NEEDS_LOGIN = 1002
        const val MAX_ATTACHMENTS = 4
        const val PAGE_SETTLE_DELAY_MS = 900L
        const val RETRY_DELAY_MS = 900L
        const val POLL_DELAY_MS = 800L
        const val RESPONSE_TIMEOUT_MS = 120_000L
    }
}
