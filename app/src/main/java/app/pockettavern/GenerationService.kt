package app.pockettavern

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Keeps a user-started generation alive when MainActivity is no longer visible.
 * The controller owns the HTTP stream; this service only owns the foreground
 * service lifecycle and its token-scoped notification actions.
 */
class GenerationService : Service() {
    private val stateLock = Any()
    private var activeToken: String? = null
    private var foregroundStarted = false
    private var intentionalStop = false
    private val keepAlive by lazy { GenerationKeepAlive.forContext(this) }

    private val appController: AppController
        get() = (application as PocketApplication).controller

    override fun onCreate() {
        super.onCreate()
        synchronized(companionLock) {
            currentService = WeakReference(this)
        }
        // Channel setup is best-effort; startForeground() still reports any
        // platform failure through onStartCommand's guarded path.
        runCatching { ensureNotificationChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        return try {
            if (intent?.action == ACTION_STOP) {
                handleStop(token, startId)
                START_NOT_STICKY
            } else {
                handleStart(token, startId)
                START_NOT_STICKY
            }
        } catch (error: Throwable) {
            // A bad notification/channel or a platform FGS restriction must not
            // crash the application process. The controller marks the matching
            // request as failed and decides how to present the error.
            reportFailureIfMatching(token, "前台服务启动失败：${error.message ?: error.javaClass.simpleName}")
            stopServiceInstance()
            START_NOT_STICKY
        }
    }

    private fun handleStart(token: String, startId: Int) {
        if (token.isBlank()) {
            stopServiceInstance()
            return
        }

        // Promote first so Android sees a foreground service immediately. If
        // this instance already owns another token, use its token for the
        // temporary notification until the controller validates the new one.
        val existingToken = synchronized(stateLock) { activeToken }
        startForegroundFor(existingToken ?: token)

        // The controller is the source of truth for which request is live. A
        // service instance may receive a new start command before the previous
        // instance has finished destroying; accept the new token only when it
        // is already active there. This prevents stale starts from replacing a
        // live request while allowing a fast stop-and-restart handoff.
        val tokenIsActive = runCatching { appController.isGenerationActive(token) }.getOrDefault(false)
        if (!tokenIsActive) {
            val previousIsActive = existingToken?.let { appController.isGenerationActive(it) } == true
            if (!previousIsActive) {
                // Both requests may have finished before this delayed START.
                // Promotion above must not leave an orphaned foreground service.
                markIntentionalStop()
                stopServiceInstance(startId)
            }
            return
        }
        synchronized(stateLock) {
            activeToken = token
            intentionalStop = false
            keepAlive.hold()
        }

        // Refresh the token-scoped notification after a validated handoff.
        if (existingToken != token) startForegroundFor(token)

        runCatching { appController.onGenerationServiceReady(token) }
            .onFailure {
                reportFailureIfMatching(token, "前台服务就绪失败：${it.message ?: it.javaClass.simpleName}")
                markIntentionalStop()
                stopServiceInstance(startId)
                return
            }

    }

    private fun handleStop(token: String, startId: Int) {
        val current = synchronized(stateLock) { activeToken }
        if (current == null) {
            // A stale notification can relaunch a service after the original
            // instance was destroyed. It owns no generation, so clean up this
            // empty started service immediately.
            stopServiceInstance(startId)
            return
        }
        if (token.isBlank() || current != token) return

        // Let the controller cancel the corresponding HTTP job first. The
        // token check above prevents a notification from an old generation
        // from cancelling a newer one.
        // The controller cancels the request and flushes the partial result.
        // It calls finish(context, token) after that flush, so removing the
        // foreground service here cannot race persistence.
        appController.stopGenerationFromService(token, "用户从通知停止生成")
    }

    private fun startForegroundFor(token: String) {
        val notification = buildNotification(token)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            type
        )
        foregroundStarted = true
    }

    private fun buildNotification(token: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            this,
            requestCode("open:$token"),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GenerationService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_TOKEN, token)
        val stopPending = PendingIntent.getService(
            this,
            requestCode("stop:$token"),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Several chats can be working at once; the one notification stands for all of them.
        val count = runCatching { appController.backgroundTaskCount() }.getOrDefault(1)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_generation)
            .setContentTitle("口袋酒馆")
            .setContentText(if (count > 1) "$count 个会话正在进行，可切后台继续" else "正在生成回复，可切后台继续")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(NotificationCompat.Action(0, if (count > 1) "全部停止" else "停止生成", stopPending))
            .build()
    }

    /** The number of working chats changed: say so on the notification that is already showing. */
    private fun refreshNotification() {
        val token = synchronized(stateLock) { activeToken } ?: return
        if (!foregroundStarted) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(token)) }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "回复生成",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在进行的回复生成任务"
                setShowBadge(false)
            }
        )
    }

    private fun reportFailureIfMatching(token: String, reason: String) {
        if (token.isBlank()) return
        val matches = synchronized(stateLock) { activeToken == token || activeToken == null }
        val controllerOwnsToken = if (matches) true else {
            // A validated handoff can fail before activeToken is swapped. Use
            // controller state to report that new token, while stale tokens
            // remain unable to stop the current request.
            runCatching { appController.isGenerationActive(token) }.getOrDefault(false)
        }
        if (!controllerOwnsToken) return
        runCatching { appController.stopGenerationFromService(token, reason) }
    }

    private fun stopServiceInstance(startId: Int? = null) {
        synchronized(stateLock) { keepAlive.release() }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun markIntentionalStop() {
        synchronized(stateLock) { intentionalStop = true }
    }

    override fun onDestroy() {
        val token = synchronized(stateLock) { activeToken }
        val expected = synchronized(stateLock) { intentionalStop }
        if (!expected && !token.isNullOrBlank()) {
            val active = runCatching { appController.isGenerationActive(token) }.getOrDefault(false)
            if (active) runCatching {
                appController.stopGenerationFromService(token, "前台服务意外结束")
            }
        }
        synchronized(stateLock) { keepAlive.release() }
        synchronized(companionLock) {
            if (currentService?.get() === this) currentService = null
        }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        val token = synchronized(stateLock) { activeToken }
        markIntentionalStop()
        // Do not wait for the network coroutine here. Android gives only a
        // small grace period after this callback before treating it as a crash.
        stopServiceInstance(startId)
        if (!token.isNullOrBlank()) {
            runCatching { appController.stopGenerationFromService(token, "后台生成时间达到系统上限") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 7401
        private const val EXTRA_TOKEN = "generation_token"
        private const val ACTION_OPEN = "app.pockettavern.action.OPEN_GENERATION"
        private const val ACTION_STOP = "app.pockettavern.action.STOP_GENERATION"

        private val companionLock = Any()
        private var currentService: WeakReference<GenerationService>? = null

        @JvmStatic
        fun start(context: Context, token: String) {
            if (token.isBlank()) return
            val appContext = context.applicationContext
            // Let ForegroundServiceStartNotAllowedException and other start
            // failures reach the controller's action() catch/finally path.
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GenerationService::class.java)
                    .setAction(Intent.ACTION_RUN)
                    .putExtra(EXTRA_TOKEN, token)
            )
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun finish(context: Context, token: String) {
            if (token.isBlank()) return
            val service = synchronized(companionLock) { currentService?.get() }
            if (service != null) {
                if (service.finishIfMatching(token)) return
                // A live service with another token owns the current task. An
                // old completion callback must not affect the new task.
                val hasOtherToken = synchronized(service.stateLock) {
                    service.activeToken?.let { it != token } == true
                }
                if (hasOtherToken) return
            }
            // If onStartCommand has not run yet, it will consult
            // isGenerationActive(token) and stop itself when the request is no
            // longer live. Do not retain an unbounded pending-token set.
        }

        /** Called by the controller whenever a chat task starts or ends. */
        @JvmStatic
        fun refresh() {
            val service = synchronized(companionLock) { currentService?.get() } ?: return
            service.refreshNotification()
        }

        internal fun holdsWakeLock(): Boolean {
            val service = synchronized(companionLock) { currentService?.get() } ?: return false
            return synchronized(service.stateLock) { service.keepAlive.held }
        }

        private fun requestCode(value: String): Int {
            val hash = value.hashCode() and Int.MAX_VALUE
            return if (hash == 0) 1 else hash
        }
    }

    private fun finishIfMatching(token: String): Boolean {
        val matches = synchronized(stateLock) { activeToken == token }
        if (!matches) return false
        markIntentionalStop()
        stopServiceInstance()
        return true
    }
}
