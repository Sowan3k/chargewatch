package io.github.sowan3k.chargewatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notification channels and the notifications that do not belong to the service. */
object Notifier {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_UPDATES = "updates"
    const val NOTIF_STATUS = 1
    private const val NOTIF_UPDATE = 2
    private const val ALERT_BASE = 1000
    const val BRAND = 0xFF16A34A.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Charge alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When another phone reaches your alert level or is fully charged"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of ChargeWatch is ready to install"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Charging status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while one of your phones is charging"
                setShowBadge(false)
            }
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun alert(context: Context, d: Device, alert: Alert, nowSec: Long = System.currentTimeMillis() / 1000) {
        createChannels(context)
        val text = AlertText.text(d, alert, nowSec)
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND)
            .setContentTitle(AlertText.title(d, alert))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            // The time shown is when the phone reported, not when this phone heard about it.
            .setWhen(d.time * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .build()
        // One notification slot per phone: "full" replaces the earlier "80 %" alert.
        context.getSystemService(NotificationManager::class.java)
            ?.notify(ALERT_BASE + (d.id.hashCode() and 0xffff), n)
    }

    fun update(context: Context, info: UpdateInfo) {
        createChannels(context)
        val n = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND)
            .setContentTitle("ChargeWatch ${info.versionName} is available")
            .setContentText("Tap to update. Your group and settings stay.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_UPDATE, n)
    }
}
