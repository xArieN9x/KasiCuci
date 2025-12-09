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
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
    private var outSocket: DatagramSocket? = null
    private var forwardingActive = false
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running", connected = false))
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
            forwardingActive = false
            vpnInterface?.close()
            outSocket?.close()
        } catch (_: Exception) {}

        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }

        try {
            startForeground(NOTIF_ID, createNotification("Panda Monitor (DNS: $dns)", connected = vpnInterface != null))
        } catch (_: Exception) {}

        if (vpnInterface != null) {
            outSocket = DatagramSocket()
            forwardingActive = true
            startPacketForwarding()
        }
    }

    // ✅ PACKET FORWARDING ENGINE - FIX INTERNET!
    private fun startPacketForwarding() {
        // Thread 1: VPN → Internet (Outbound)
        Thread {
            val buffer = ByteArray(32767)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor
                    if (fd == null) {
                        pandaActive = false
                        Thread.sleep(100)
                        continue
                    }

                    val input = FileInputStream(fd)
                    val length = input.read(buffer)

                    if (length > 0) {
                        pandaActive = true
                        forwardPacketOut(buffer, length)
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    Thread.sleep(100)
                }
            }
        }.start()

        // Thread 2: Internet → VPN (Inbound)
        Thread {
            val buffer = ByteArray(32767)
            while (forwardingActive) {
                try {
                    val length = receivePacketIn(buffer)
                    if (length > 0) {
                        val fd = vpnInterface?.fileDescriptor
                        if (fd != null) {
                            val output = FileOutputStream(fd)
                            output.write(buffer, 0, length)
                        }
                    }
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
            }
        }.start()
    }

    private fun forwardPacketOut(packet: ByteArray, length: Int) {
        try {
            if (outSocket == null || outSocket?.isClosed == true) {
                outSocket = DatagramSocket()
            }

            // Parse destination IP & port
            val destIP = parseIPDestination(packet)
            val destPort = parseDestPort(packet)

            val dgram = DatagramPacket(
                packet, length,
                InetAddress.getByName(destIP), destPort
            )
            outSocket?.send(dgram)
        } catch (_: Exception) {}
    }

    private fun receivePacketIn(buffer: ByteArray): Int {
        return try {
            val packet = DatagramPacket(buffer, buffer.size)
            outSocket?.receive(packet)
            packet.length
        } catch (_: Exception) {
            0
        }
    }

    // Parse IP destination dari packet header
    private fun parseIPDestination(packet: ByteArray): String {
        return try {
            "${packet[16].toUByte()}.${packet[17].toUByte()}." +
            "${packet[18].toUByte()}.${packet[19].toUByte()}"
        } catch (_: Exception) {
            "8.8.8.8"
        }
    }

    // Parse destination port dari TCP/UDP header
    private fun parseDestPort(packet: ByteArray): Int {
        return try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            val portHigh = packet[ipHeaderLen + 2].toUByte().toInt()
            val portLow = packet[ipHeaderLen + 3].toUByte().toInt()
            (portHigh shl 8) or portLow
        } catch (_: Exception) {
            53 // Default DNS port
        }
    }

    override fun onDestroy() {
        try {
            forwardingActive = false
            vpnInterface?.close()
            outSocket?.close()
        } catch (_: Exception) {}
        pandaActive = false
        instance = null
        super.onDestroy()
    }
}
