package com.instasave.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.instasave.app.R

class Notifier(private val context: Context, private val enabled: () -> Boolean = { true }) {

    private val channelId = "instasave_results"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "Результаты загрузки",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "Уведомления о скачанных видео"
        manager.createNotificationChannel(channel)
    }

    fun notifySaved(fileName: String, uri: Uri?) {
        if (!enabled()) return
        val contentIntent = Intent(Intent.ACTION_VIEW).apply {
            if (uri != null) {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val notifId = fileName.hashCode()
        val pi = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Сохранено в галерею")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted yet
        }
    }
}