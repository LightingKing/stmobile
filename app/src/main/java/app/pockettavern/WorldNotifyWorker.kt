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
import app.pockettavern.domain.WorldItem
import app.pockettavern.domain.WorldSummary
import app.pockettavern.domain.Worlds
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小世界 run on the PC, so the phone has nothing to compute: about every 15 minutes it only asks the
 * PC whether a resident wrote to the user privately (no model request), and shows a notification
 * that opens that private chat.
 */
class WorldNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PocketApplication
        val notices = withContext(Dispatchers.Main) { app.controller.checkWorldDms() }
        notices.forEach { (world, dms) -> notify(applicationContext, world, dms) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "world-dms"
        private const val CHANNEL_ID = "world_dms"
        const val ACTION_OPEN_WORLD = "app.pockettavern.OPEN_WORLD"
        const val EXTRA_WORLD_ID = "world_id"
        const val EXTRA_CHANNEL = "world_channel"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(WORK_NAME); return }
            val request = PeriodicWorkRequestBuilder<WorldNotifyWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        internal fun notify(context: Context, world: WorldSummary, dms: List<WorldItem>) {
            if (dms.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "小世界私信", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "小世界里有人私聊你时提醒" }
            )
            val last = dms.last()
            val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_WORLD)
                .putExtra(EXTRA_WORLD_ID, world.id).putExtra(EXTRA_CHANNEL, last.channel)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, world.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val body = dms.takeLast(6).joinToString("\n") { "${it.name}：${it.text}" }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_generation)
                .setContentTitle(Worlds.noticeTitle(world.name, dms))
                .setContentText("${last.name}：${last.text}".take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(600)))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(world.id.hashCode(), notification) }
        }
    }
}
