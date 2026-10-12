package app.trazo.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.trazo.MainActivity
import app.trazo.R
import app.trazo.util.Permissions

object Notifications {
    const val CH_TRACKING = "tracking"
    const val CH_SOCIAL = "social"
    const val CH_TRIPS = "trips"
    const val ID_TRACKING = 1
    private var nextId = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, ctx.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_tracking_desc); setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SOCIAL, ctx.getString(R.string.channel_social), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.channel_social_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIPS, ctx.getString(R.string.channel_trips), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_trips_desc) },
        )
    }

    fun openApp(ctx: Context, route: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route?.let { i.putExtra(MainActivity.EXTRA_ROUTE, it) }
        return PendingIntent.getActivity(ctx, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(ctx: Context, channel: String, title: String, text: String, route: String? = null) {
        if (!Permissions.notifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_trazo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, route))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(nextId++, n)
    }
}
