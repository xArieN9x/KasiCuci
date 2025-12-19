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
import java.net.InetAddress
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
    
    // Simple connection tracking
    private val connectionPool = ConnectionPool()
    private val priorityManager = TrafficPriorityManager()
    private val tcpConnections = ConcurrentHashMap<Int, Socket>()
    
    private val workerPool = Executors.newCachedThreadPool()
    private val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("CB_VPN", "🚀 VPN SERVICE STARTED")
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor - Starting", false))
        establishVPN("8.8.8.8")
        
        // Simple cleanup every 30 seconds
        scheduledPool.scheduleAtFixedRate({
            connectionPool.cleanupIdleConnections()
        }, 30, 30, TimeUnit.SECONDS)
        
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
        android.util.Log.d("CB_VPN", "🔧 Setting up VPN")
        
        try {
            forwardingActive = false
            connectionPool.closeAll()
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
        } catch (_: Exception) {}
    
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("192.168.77.2", 32)           // ✅ PAKAI IP BARU (elak conflict)
            .addRoute("0.0.0.0", 0)                    // ✅ ROUTE SEMUA TRAFFIC
            .addDisallowedRoute("192.168.77.0", 24)    // ✅ ELAK LOCAL CONFLICT
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)
            .addDnsServer("1.1.1.1")
            .setBlocking(true)                         // ✅ FORCE BLOCK NON-VPN TRAFFIC
            .setMtu(1500)
    
        vpnInterface = try {
            val iface = builder.establish()
            android.util.Log.i("CB_VPN", "✅ VPN Interface CREATED")
            iface
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ VPN Failed: ${e.message}")
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            updateNotification("VPN Active | Order Ready 🚀", true)
            startPacketForwarding()
        } else {
            updateNotification("VPN Setup Failed", false)
        }
    }
    
    private fun updateNotification(text: String, connected: Boolean) {
        try {
            startForeground(NOTIF_ID, createNotification(text, connected))
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "Notification error: ${e.message}")
        }
    }

    private fun startPacketForwarding() {
        workerPool.execute {
            android.util.Log.d("CB_VPN", "📤 Packet forwarding STARTED")
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        lastPacketTime = System.currentTimeMillis()
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    Thread.sleep(50)
                }
            }
            android.util.Log.d("CB_VPN", "📤 Packet forwarding STOPPED")
        }
    }

    private fun startPacketProcessor() {
        workerPool.execute {
            android.util.Log.d("CB_VPN", "🔄 Packet processor STARTED")
            while (forwardingActive) {
                try {
                    val task = priorityManager.takePacket() ?: continue
                    processPacketTask(task)
                } catch (e: Exception) {
                    Thread.sleep(10)
                }
            }
        }
    }

    private fun processPacketTask(task: PacketTask) {
        try {
            val socket = Socket(task.destIp, task.destPort).apply {
                tcpNoDelay = true
                soTimeout = 10000
            }
            
            android.util.Log.i("CB_VPN", "✅ Connected to ${task.destIp}:${task.destPort}")
            
            tcpConnections[task.srcPort] = socket
            socket.getOutputStream().write(task.packet)
            socket.getOutputStream().flush()
            
            android.util.Log.d("CB_VPN", "📤 Sent ${task.packet.size} bytes")
            
            startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
            
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ Connection failed: ${e.message}")
            tcpConnections.remove(task.srcPort)
        }
    }

    private fun startResponseHandler(srcPort: Int, socket: Socket, destIp: String, destPort: Int) {
        workerPool.execute {
            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val inStream = socket.getInputStream()
            val buffer = ByteArray(2048)
            
            try {
                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                    val n = inStream.read(buffer)
                    if (n <= 0) break
                    
                    android.util.Log.d("CB_VPN", "📥 Received $n bytes response")
                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buffer.copyOfRange(0, n))
                    outStream.write(reply)
                    outStream.flush()
                }
            } catch (e: Exception) {
                // Connection closed normally
            } finally {
                socket.close()
                tcpConnections.remove(srcPort)
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 8) {
                android.util.Log.w("CB_VPN", "⚠️ Packet too small")
                return
            }
            
            val protocol = packet[9].toInt() and 0xFF
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                        "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or 
                         (packet[ipHeaderLen + 1].toInt() and 0xFF)
            val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or 
                          (packet[ipHeaderLen + 3].toInt() and 0xFF)
            
            android.util.Log.i("CB_VPN", "🌐 Packet: $protocol to $destIp:$destPort")
            
            when (protocol) {
                6 -> { // TCP
                    if (packet.size < ipHeaderLen + 20) return
                    val payload = packet.copyOfRange(ipHeaderLen + 20, packet.size)
                    priorityManager.addPacket(payload, destIp, destPort, srcPort)
                    android.util.Log.d("CB_VPN", "🔗 TCP queued (${payload.size} bytes)")
                }
                17 -> { // UDP
                    // CRITICAL FIX: Handle DNS (port 53)
                    if (destPort == 53) {
                        android.util.Log.i("CB_VPN", "🔍 DNS Request detected")
                        // Simple DNS forward to Google
                        forwardDnsQuery(packet, srcPort)
                    } else {
                        android.util.Log.d("CB_VPN", "📨 UDP to $destIp:$destPort")
                        // Untuk UDP lain (QUIC), skip dulu - focus pada TCP untuk order flow
                    }
                }
                else -> {
                    android.util.Log.d("CB_VPN", "📦 Other protocol: $protocol")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ Packet error: ${e.message}")
        }
    }
    
    private fun forwardDnsQuery(dnsPacket: ByteArray, srcPort: Int) {
        workerPool.execute {
            try {
                val googleDns = InetAddress.getByName("8.8.8.8")
                val socket = java.net.DatagramSocket()
                socket.soTimeout = 3000
                
                // Send to Google DNS
                val packet = java.net.DatagramPacket(dnsPacket, dnsPacket.size, googleDns, 53)
                socket.send(packet)
                
                // Receive response
                val buffer = ByteArray(512)
                val response = java.net.DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                
                // Forward response back to app
                forwardDnsResponse(response.data.take(response.length).toByteArray(), srcPort)
                
                socket.close()
                android.util.Log.i("CB_VPN", "✅ DNS forwarded successfully")
                
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "❌ DNS forward failed: ${e.message}")
            }
        }
    }
    
    private fun forwardDnsResponse(dnsResponse: ByteArray, srcPort: Int) {
        // Simple response forwarding - minimal implementation
        try {
            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            // Create simple UDP response packet
            // Note: This is minimal - proper implementation would parse/build full UDP packet
            outStream.write(dnsResponse)
            android.util.Log.d("CB_VPN", "📤 DNS response forwarded")
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ DNS response failed: ${e.message}")
        }
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, 
                               destPort: Int, payload: ByteArray): ByteArray {
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        
        // Simple IP header
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 0xFF.toByte()
        packet[9] = 0x06
        
        // Source IP
        val src = srcIp.split(".")
        packet[12] = src[0].toUByte().toByte()
        packet[13] = src[1].toUByte().toByte()
        packet[14] = src[2].toUByte().toByte()
        packet[15] = src[3].toUByte().toByte()
        
        // Dest IP
        val dest = destIp.split(".")
        packet[16] = dest[0].toUByte().toByte()
        packet[17] = dest[1].toUByte().toByte()
        packet[18] = dest[2].toUByte().toByte()
        packet[19] = dest[3].toUByte().toByte()
        
        // Ports
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        
        // TCP header
        packet[32] = 0x50
        packet[33] = if (payload.isEmpty()) 0x02 else 0x18.toByte()
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        
        // Payload
        System.arraycopy(payload, 0, packet, 40, payload.size)
        
        return packet
    }

    override fun onDestroy() {
        android.util.Log.d("CB_VPN", "🛑 SERVICE DESTROYING")
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
