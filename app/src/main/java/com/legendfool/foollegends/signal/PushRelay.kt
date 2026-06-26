package com.legendfool.foollegends.signal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.legendfool.foollegends.R
import com.legendfool.foollegends.startup.WelcomePortal
import com.legendfool.foollegends.vault.DataVault

/**
 * Firebase Cloud Messaging receiver. Different class name, package, and channel ID
 * from any other project in the portfolio.
 */
class PushRelay : FirebaseMessagingService() {

    private val channelId = "fl_bonus_channel"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DataVault(applicationContext).fcmToken = token
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        val data    = msg.data
        val notif   = msg.notification
        val title   = data["title"]   ?: notif?.title   ?: return
        val body    = data["body"]    ?: notif?.body    ?: return
        val url     = data["url"]     ?: data["link"]   ?: ""
        val imgUrl  = data["image"]   ?: notif?.imageUrl?.toString() ?: ""

        // App in foreground with WebView visible → hand the URL off live and
        // skip the notification (warm-tap UX). Per spec: do NOT save warm URLs.
        val handler = com.legendfool.foollegends.signal.PushBus.onWarmUrl
        if (handler != null && url.isNotBlank()) {
            try { handler.invoke(url); return } catch (_: Exception) { /* fall through */ }
        }

        // Cold-start case: save the URL so WelcomePortal can pick it up next launch.
        val vault = DataVault(applicationContext)
        if (url.isNotBlank()) vault.coldPushUrl = url

        showNotification(title, body, url, imgUrl)
    }

    private fun showNotification(title: String, body: String, url: String, imgUrl: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        // Route through WelcomePortal so it can re-check routing if the user
        // somehow ended up in NATIVE mode again. WelcomePortal forwards the URL
        // to StreamPortal via vault.coldPushUrl.
        val tap = Intent(this, WelcomePortal::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (url.isNotBlank()) putExtra(WelcomePortal.EXTRA_PUSH_URL, url)
            putExtra(WelcomePortal.EXTRA_FROM_PUSH, true)
        }
        val pi = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (imgUrl.isNotBlank()) {
            try {
                val bmp = BitmapFactory.decodeStream(
                    java.net.URL(imgUrl).openConnection().inputStream
                )
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bmp)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                )
            } catch (_: Exception) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        nm.notify(NOTIF_ID++, builder.build())
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(channelId) != null) return
        val ch = NotificationChannel(channelId, "Bonuses & Promos",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Fool Legends bonus alerts"
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        @Volatile private var NOTIF_ID = 1001
    }
}
