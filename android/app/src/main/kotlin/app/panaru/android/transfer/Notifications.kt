package app.panaru.android.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.panaru.android.MainActivity
import app.panaru.android.R
import app.panaru.android.util.Links
import app.panaru.data.UploadEntity

object Notifications {
    const val CHANNEL_PROGRESS = "transfers"
    const val CHANNEL_DONE = "links"
    const val ID_FOREGROUND = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notifica del servizio in foreground con l'avanzamento complessivo. */
    fun progress(context: Context, title: String, text: String?, percent: Int?): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_panaru)
            .setColor(ContextCompat.getColor(context, R.color.panaru_arancio))
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp(context))
            .build()

    /** Pubblica una notifica solo se il permesso è concesso (Android 13+): altrimenti nulla. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (canNotify(context)) runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "Link pronto": azioni Copia e Condividi direttamente dalla notifica. */
    fun linkReady(context: Context, upload: UploadEntity, copied: Boolean) {
        if (!canNotify(context)) return
        val url = upload.url ?: return
        val copy = PendingIntent.getBroadcast(
            context, upload.id.toInt(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_COPY)
                .putExtra(NotificationActionReceiver.EXTRA_URL, url)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION, notificationId(upload.id)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val share = PendingIntent.getActivity(
            context, upload.id.toInt(),
            Links.shareIntent(context, listOf(upload.fileName to url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_panaru)
            .setColor(ContextCompat.getColor(context, R.color.panaru_arancio))
            .setContentTitle(context.getString(R.string.notif_link_ready, upload.fileName))
            .setContentText(if (copied) context.getString(R.string.notif_link_copied) else url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_copy_link), copy)
            .addAction(0, context.getString(R.string.action_share), share)
            .build()
        post(context, notificationId(upload.id), n)
    }

    fun failed(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_panaru)
            .setColor(ContextCompat.getColor(context, R.color.panaru_arancio))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, title.hashCode(), n)
    }

    fun notificationId(uploadId: Long) = 1000 + (uploadId % 100_000).toInt()
}
