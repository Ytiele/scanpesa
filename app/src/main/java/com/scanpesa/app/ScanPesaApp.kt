package com.scanpesa.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ScanPesaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
    }
}

object Notifier {
    private const val CHANNEL = "autofill"
    private const val ID = 4201

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Auto-fill status", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tells you when to enter the amount and PIN, or which step failed"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(context).notify(ID, n) } catch (_: SecurityException) {}
    }
}
