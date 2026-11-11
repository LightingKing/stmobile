package app.pockettavern

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pockettavern.domain.Chat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wakes about once an hour while some chat has 自己刷视频 on. Each wake lets the controller run at
 * most one chat whose slot is due; a share that arrives while the app is closed gets a notification.
 */
class VideoBrowseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PocketApplication
        val posted = withContext(Dispatchers.Main) { app.controller.runDueBrowse() }
        if (posted != null) notify(applicationContext, posted.first, posted.second)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "video-browse"
        private const val CHANNEL_ID = "video_browse"
        const val ACTION_OPEN_CHAT = "app.pockettavern.OPEN_CHAT"
        const val EXTRA_CHAT_ID = "chat_id"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(WORK_NAME); return }
            val request = PeriodicWorkRequestBuilder<VideoBrowseWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, chat: Chat, reply: String) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自己刷视频", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "角色刷到视频想分享给你时提醒" }
            )
            val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CHAT).putExtra(EXTRA_CHAT_ID, chat.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, chat.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_generation)
                .setContentTitle(chat.title.ifBlank { "口袋酒馆" })
                .setContentText(reply.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "刷到一个视频想跟你分享")
                .setStyle(NotificationCompat.BigTextStyle().bigText(reply.take(300)))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(chat.id.hashCode(), notification) }
        }
    }
}
