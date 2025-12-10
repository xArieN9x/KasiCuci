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
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var forwardingActive = false
    private val tcpConnections = ConcurrentHashMap<Int, Socket>()
    private val threadPool: ExecutorService = Executors.newCachedThreadPool()
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
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
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
            forwardingActive = true
            startPacketForwarding()
        }
    }

    private fun startPacketForwarding() {
        threadPool.execute {
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 8) return

            val protocol = packet[9].toUByte().toInt()
            if (protocol != 6) return // Hanya TCP (protocol 6)

            val destIpBytes = packet.sliceArray(16..19)
            val destIp = destIpBytes.joinToString(".") { it.toUByte().toString() }
            val srcPort = ((packet[ipHeaderLen].toUByte().toInt() shl 8) or packet[ipHeaderLen + 1].toUByte().toInt())
            val destPort = ((packet[ipHeaderLen + 2].toUByte().toInt() shl 8) or packet[ipHeaderLen + 3].toUByte().toInt())
            val payload = packet.copyOfRange(ipHeaderLen + 20, packet.size)

            if (!tcpConnections.containsKey(srcPort)) {
                threadPool.execute {
                    try {
                        val socket = Socket(destIp, destPort)
                        socket.tcpNoDelay = true
                        tcpConnections[srcPort] = socket

                        // Relay: Internet → App
                        threadPool.execute {
                            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                            val buf = ByteArray(2048)
                            try {
                                val inputStream = socket.getInputStream()
                                while (forwardingActive && !socket.isClosed) {
                                    val n = inputStream.read(buf)
                                    if (n <= 0) break
                                    val replyPacket = buildTcpReplyPacket(destIp, destPort, "10.0.0.2", srcPort, buf, n)
                                    outStream.write(replyPacket)
                                    outStream.flush()
                                }
                            } catch (_: Exception) {}
                            tcpConnections.remove(srcPort)
                            socket.close()
                        }

                        if (payload.isNotEmpty()) {
                            socket.getOutputStream().write(payload)
                            socket.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                        tcpConnections.remove(srcPort)
                    }
                }
            } else {
                tcpConnections[srcPort]?.getOutputStream()?.let {
                    it.write(payload)
                    it.flush()
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildTcpReplyPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray, len: Int): ByteArray {
        val totalLen = 40 + len
        val packet = ByteArray(totalLen)
        // IP header
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[9] = 6 // TCP
        val srcParts = srcIp.split(".")
        packet[12] = srcParts[0].toUByte().toByte()
        packet[13] = srcParts[1].toUByte().toByte()
        packet[14] = srcParts[2].toUByte().toByte()
        packet[15] = srcParts[3].toUByte().toByte()
        val destParts = destIp.split(".")
        packet[16] = destParts[0].toUByte().toByte()
        packet[17] = destParts[1].toUByte().toByte()
        packet[18] = destParts[2].toUByte().toByte()
        packet[19] = destParts[3].toUByte().toByte()
        // TCP header (min 20 bytes)
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        // Skip sequence/ack — cukup untuk local tunnel
        packet[32] = 0x01 // Window size (min)
        packet[33] = 0x00
        // Payload
        System.arraycopy(payload, 0, packet, 40, len)
        return packet
    }

    override fun onDestroy() {
        forwardingActive = false
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        threadPool.shutdownNow()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        pandaActive = false
        instance = null
        super.onDestroy()
    }
}
