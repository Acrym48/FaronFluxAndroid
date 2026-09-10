package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.ui.MainActivity

class VpnNotificationManager(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "io.github.p1neapplexpress.libp1npplydtransport.so.vpn"
        const val NOTIFICATION_ID = 1
    }

    fun startForeground() {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notify_title))
            .setContentText(service.getString(R.string.notify_msg))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        service.startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(ch)
    }
}
