package io.github.jqssun.gpssetter.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.access.AccessActivity
import io.github.jqssun.gpssetter.utils.PrefManager
import java.util.concurrent.TimeUnit

/** Checks for a new release every 12 hours and shows a notification once per version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PrefManager.isUpdateCheckEnabled) return Result.success()
        val update = LatestRelease.fetchNewer(LatestRelease.defaultService()) ?: return Result.success()
        if (PrefManager.lastNotifiedUpdate == update.releaseUrl) return Result.success()
        if (notifyUpdate(applicationContext, update)) {
            PrefManager.lastNotifiedUpdate = update.releaseUrl
        }
        return Result.success()
    }

    private fun notifyUpdate(ctx: Context, update: UpdateChecker.Update): Boolean {
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(ctx.getString(R.string.update_channel_name))
                .build()
        )
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, AccessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spls_notif)
            .setContentTitle(ctx.getString(R.string.update_available))
            .setContentText(ctx.getString(R.string.update_notif_text, update.name))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        private const val WORK_NAME = "khpal_update_check"
        private const val CHANNEL_ID = "khpal.app.update"
        private const val NOTIFICATION_ID = 4502

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
