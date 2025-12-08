package com.example.allinoneflushapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

class AppMonitorVPNService : VpnService() {

    companion object {
        private var pandaActive = false
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive

        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running", connected = false))
        // initial establish with default dns
        establishVPN("8.8.8.8")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Panda Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // use safe built-in icons instead of removed stat_sys_vpn
        val smallIcon = if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Panda Monitor")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun establishVPN(dns: String) {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
    
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addDnsServer(dns)
            .addRoute("1.1.1.1", 32)
            .addRoute("0.0.0.0", 0)
    
        try {
            builder.addAllowedApplication("com.logistics.rider.foodpanda")
        } catch (_: Exception) {}
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
    
        try {
            startForeground(NOTIF_ID, createNotification("Panda Monitor (DNS: $dns)", connected = vpnInterface != null))
        } catch (_: Exception) {}
    
        // start monitoring
        monitorTraffic()
    }

    private fun monitorTraffic() {
        Thread {
            while (true) {
                try {
                    val fd = vpnInterface?.fileDescriptor
                    if (fd == null) {
                        pandaActive = false
                        Thread.sleep(1000)
                        continue
                    }
                    val input = FileInputStream(fd)
                    val available = try { input.available() } catch (_: Exception) { 0 }
                    pandaActive = available > 0
                } catch (e: Exception) {
                    pandaActive = false
                }
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
        }.start()
    }

    override fun onDestroy() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        pandaActive = false
        instance = null
        super.onDestroy()
    }
}
