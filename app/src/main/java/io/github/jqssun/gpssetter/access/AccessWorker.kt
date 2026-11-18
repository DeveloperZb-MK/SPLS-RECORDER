package io.github.jqssun.gpssetter.access

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
import java.util.concurrent.TimeUnit

/** Runs every 12 hours and calls /api/verify with the saved token. */
class AccessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return when (AccessVerifier.verify(ctx)) {
            VerifyResult.Rejected -> {
                AccessGuard.revoke(ctx)
                notifyExpired(ctx)
                Result.success()
            }
            VerifyResult.Error -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private fun notifyExpired(ctx: Context) {
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(ctx.getString(R.string.access_channel_name))
                .build()
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, AccessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spls_notif)
            .setContentTitle(ctx.getString(R.string.access_expired_title))
            .setContentText(ctx.getString(R.string.access_expired_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // notification permission missing; nothing else to do
        }
    }

    companion object {
        private const val WORK_NAME = "khpal_access_verify"
        private const val CHANNEL_ID = "khpal.access.session"
        private const val NOTIFICATION_ID = 4501

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AccessWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
