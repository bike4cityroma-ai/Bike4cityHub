package it.bike4city.hub.trackerpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import it.bike4city.hub.R

internal object NotificationFactory {

    fun ensureChannel(context: Context, cfg: ProTrackerConfig) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(cfg.channelId) != null) return

        nm.createNotificationChannel(
            NotificationChannel(cfg.channelId, cfg.channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Registrazione traccia GPS"
                setShowBadge(false)
            }
        )
    }

    fun build(context: Context, cfg: ProTrackerConfig): Notification {
        return NotificationCompat.Builder(context, cfg.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(cfg.notificationTitle)
            .setContentText(cfg.notificationText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
