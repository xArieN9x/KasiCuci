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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var lastPacketTime = 0L
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastPacketTime > 3000) {
                pandaActive = false
            }
            return pandaActive
        }

        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    
    // ✅ ENHANCEMENT: New systems
    private val connectionPool = ConnectionPool()
    private val priorityManager = TrafficPriorityManager()
    private val tcpConnections = ConcurrentHashMap<Int, Socket>() // Track active sockets by srcPort
    
    private val workerPool = Executors.newCachedThreadPool()
    private val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("CB_VPN", "🚀 SERVICE STARTED - AppMonitorVPNService.onStartCommand")
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running", connected = false))
        establishVPN("8.8.8.8")
        
        // ✅ Start cleanup scheduler
        scheduledPool.scheduleAtFixedRate({
            connectionPool.cleanupIdleConnections()
        }, 10, 10, TimeUnit.SECONDS)
        
        // ✅ Start packet processor
        startPacketProcessor()
        
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
        android.util.Log.d("CB_VPN", "🔧 Setting up VPN with DNS: $dns")
        
        try {
            forwardingActive = false
            connectionPool.closeAll()
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
            val iface = builder.establish()
            android.util.Log.i("CB_VPN", "✅ VPN Interface CREATED successfully")
            iface
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ VPN Failed to establish: ${e.message}")
            null
        }

        try {
            val status = if (vpnInterface != null) " (DNS: $dns)" else " - Failed"
            startForeground(NOTIF_ID, createNotification("Panda Monitor$status", connected = vpnInterface != null))
        } catch (_: Exception) {}

        if (vpnInterface != null) {
            android.util.Log.i("CB_VPN", "📡 VPN ACTIVE - Starting packet forwarding")
            forwardingActive = true
            startPacketForwarding()
        } else {
            android.util.Log.w("CB_VPN", "⚠️ VPN Interface is NULL after establishment attempt")
        }
    }

    private fun startPacketForwarding() {
        workerPool.execute {
            android.util.Log.d("CB_VPN", "📤 Packet forwarding thread STARTED")
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        lastPacketTime = System.currentTimeMillis()
                        android.util.Log.d("CB_VPN", "📦 Packet CAPTURED - Size: $len bytes")
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    android.util.Log.e("CB_VPN", "❌ Packet forwarding error: ${e.message}")
                    Thread.sleep(50)
                }
            }
            android.util.Log.d("CB_VPN", "📤 Packet forwarding thread STOPPED")
        }
    }

    // ✅ NEW: Process packets from priority queue
    private fun startPacketProcessor() {
        workerPool.execute {
            android.util.Log.d("CB_VPN", "🔄 Packet processor thread STARTED")
            while (forwardingActive) {
                try {
                    val task = priorityManager.takePacket() ?: continue
                    
                    val destKey = "${task.destIp}:${task.destPort}"
                    android.util.Log.d("CB_VPN", "🔍 Processing queued task for: $destKey")
                    
                    // Try to get existing socket from pool
                    var socket = connectionPool.getSocket(task.destIp, task.destPort)
                    
                    if (socket == null || socket.isClosed || !socket.isConnected) {
                        // Create new socket
                        socket = try {
                            Socket(task.destIp, task.destPort).apply {
                                tcpNoDelay = true
                                soTimeout = 15000
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CB_VPN", "❌ Socket creation failed for $destKey: ${e.message}")
                            null
                        }
                    }
                    
                    if (socket != null) {
                        // Track active connection
                        tcpConnections[task.srcPort] = socket
                        
                        // Send data
                        try {
                            socket.getOutputStream().write(task.packet)
                            socket.getOutputStream().flush()
                            android.util.Log.i("CB_VPN", "📤 Data SENT to $destKey (${task.packet.size} bytes)")
                            
                            // Start response handler if not already
                            if (!tcpConnections.containsKey(task.srcPort)) {
                                startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CB_VPN", "❌ Socket write failed: ${e.message}")
                            socket.close()
                            tcpConnections.remove(task.srcPort)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CB_VPN", "❌ Packet processor error: ${e.message}")
                    Thread.sleep(10)
                }
            }
            android.util.Log.d("CB_VPN", "🔄 Packet processor thread STOPPED")
        }
    }

    private fun startResponseHandler(srcPort: Int, socket: Socket, destIp: String, destPort: Int) {
        workerPool.execute {
            android.util.Log.d("CB_VPN", "📥 Response handler STARTED for $destIp:$destPort")
            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val inStream = socket.getInputStream()
            val buffer = ByteArray(2048)
            
            try {
                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                    val n = inStream.read(buffer)
                    if (n <= 0) break
                    
                    android.util.Log.d("CB_VPN", "📥 Received $n bytes response from $destIp:$destPort")
                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buffer.copyOfRange(0, n))
                    outStream.write(reply)
                    outStream.flush()
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "❌ Response handler error: ${e.message}")
            } finally {
                // Return socket to pool if still usable
                if (socket.isConnected && !socket.isClosed) {
                    connectionPool.returnSocket(destIp, destPort, socket)
                    android.util.Log.d("CB_VPN", "🔁 Socket returned to pool for $destIp:$destPort")
                } else {
                    socket.close()
                }
                tcpConnections.remove(srcPort)
                android.util.Log.d("CB_VPN", "📥 Response handler ENDED for $destIp:$destPort")
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 20) {
                android.util.Log.w("CB_VPN", "⚠️ Packet too small or invalid header")
                return
            }
            
            val protocol = packet[9].toInt() and 0xFF
            android.util.Log.d("CB_VPN", "🔍 Raw packet - Protocol: $protocol, Total size: ${packet.size}")
            
            if (protocol != 6) {
                android.util.Log.d("CB_VPN", "📨 Non-TCP packet (UDP/ICMP/etc), skipping")
                return // TCP only
            }

            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 1].toInt() and 0xFF)
            val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 3].toInt() and 0xFF)
            val payload = packet.copyOfRange(ipHeaderLen + 20, packet.size)

            android.util.Log.i("CB_VPN", "🔗 Parsed TCP: $destIp:$destPort ← port $srcPort (payload: ${payload.size} bytes)")
            
            // ✅ Add to priority queue instead of immediate processing
            priorityManager.addPacket(payload, destIp, destPort, srcPort)
            
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ Error parsing packet: ${e.message}")
        }
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray {
        android.util.Log.d("CB_VPN", "🔨 Building TCP response packet: $srcIp:$srcPort → $destIp:$destPort")
        
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 0xFF.toByte()
        packet[9] = 0x06
        val src = srcIp.split(".")
        packet[12] = src[0].toUByte().toByte()
        packet[13] = src[1].toUByte().toByte()
        packet[14] = src[2].toUByte().toByte()
        packet[15] = src[3].toUByte().toByte()
        val dest = destIp.split(".")
        packet[16] = dest[0].toUByte().toByte()
        packet[17] = dest[1].toUByte().toByte()
        packet[18] = dest[2].toUByte().toByte()
        packet[19] = dest[3].toUByte().toByte()
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[32] = 0x50
        packet[33] = if (payload.isEmpty()) 0x02 else 0x18.toByte()
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        System.arraycopy(payload, 0, packet, 40, payload.size)
        
        android.util.Log.d("CB_VPN", "✅ TCP packet built - Total size: $totalLen bytes")
        return packet
    }

    override fun onDestroy() {
        android.util.Log.d("CB_VPN", "🛑 SERVICE DESTROYING - Cleaning up resources")
        forwardingActive = false
        connectionPool.closeAll()
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        
        scheduledPool.shutdownNow()
        workerPool.shutdownNow()
        
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        
        pandaActive = false
        lastPacketTime = 0L
        instance = null
        super.onDestroy()
    }
}
