// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: notices that need the person's attention outside the app.

package com.csqtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

internal object MayakNotices {
    private const val CHANNEL_ID = "mayak_notices"
    private const val HASH_NOTIFICATION_ID = 9101

    /** Tells the person that every VK hash stopped working and the tunnel was turned off. */
    fun showHashExpired(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Важные сообщения", NotificationManager.IMPORTANCE_HIGH),
            )
            val openIntent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPendingIntent = PendingIntent.getActivity(
                appContext,
                HASH_NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_c)
                .setContentTitle("Хэш VK устарел")
                .setContentText("Маяк выключился. Нужен новый хэш — откройте приложение.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Маяк выключился: хэш VK больше не работает. Откройте приложение и добавьте новый хэш."),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(HASH_NOTIFICATION_ID, notification)
        }
    }

    fun clearHashExpired(context: Context) {
        runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(HASH_NOTIFICATION_ID)
        }
    }
}
