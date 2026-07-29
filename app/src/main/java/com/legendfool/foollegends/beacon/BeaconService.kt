package com.legendfool.foollegends.beacon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.legendfool.foollegends.R
import com.legendfool.foollegends.ignition.GateKeeper
import com.legendfool.foollegends.strongbox.StrongBox
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class BeaconService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        StrongBox(applicationContext).pushToken = token
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val remote = message.notification
        val url = pickUrl(data, remote)
        val title = data["title"] ?: remote?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: remote?.body ?: ""
        val image = data["image"] ?: remote?.imageUrl?.toString() ?: ""
        if (body.isBlank() && url.isBlank()) return

        // WebView already on screen: load it there and skip the notification.
        if (url.isNotBlank() && BeaconBus.offer(url)) return

        if (url.isNotBlank()) StrongBox(applicationContext).chilledPushUrl = url
        raise(title, body, url, image)
    }

    /** Backends label the destination differently; take the first key that holds one. */
    private fun pickUrl(data: Map<String, String>, remote: RemoteMessage.Notification?): String {
        for (key in URL_KEYS) {
            val value = data[key]
            if (!value.isNullOrBlank() && value.startsWith("http")) return value
        }
        val click = remote?.clickAction
        return if (!click.isNullOrBlank() && click.startsWith("http")) click else ""
    }

    private fun raise(title: String, body: String, url: String, image: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val tap = Intent(this, GateKeeper::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(GateKeeper.EXTRA_FROM_PUSH, true)
            if (url.isNotBlank()) putExtra(GateKeeper.EXTRA_PUSH_URL, url)
        }
        val pending = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_beacon_ember)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val picture = if (image.isBlank()) null else downloadPicture(image)
        if (picture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        manager.notify(nextId.getAndIncrement(), builder.build())
    }

    private fun downloadPicture(url: String): Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bonuses & Promos",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Special offers, bonuses and promo alerts"
            enableLights(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "jester_bonus_alerts"
        private val nextId = AtomicInteger(4100)
        private val URL_KEYS = listOf(
            "url", "link", "deeplink", "deep_link", "target_url", "click_action"
        )
    }
}
