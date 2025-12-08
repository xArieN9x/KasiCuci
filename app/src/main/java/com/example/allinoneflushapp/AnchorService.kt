package com.example.allinoneflushapp

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder

class AnchorService : Service() {

    override fun onCreate() {
        super.onCreate()

        val channelId = "cb_anchor_channel"
        val nm = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            "CedokBooster Anchor",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Cedok Booster Active")
            .setContentText("Stabilizing accessibility service…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // KEEP ALIVE
        startForeground(77, notif)
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
