package com.example.allinoneflushapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.*
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.net.URL
import java.util.concurrent.*
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var lastPacketTime = 0L
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastPacketTime > 3000) pandaActive = false
            return pandaActive
        }

        fun rotateDNS(dnsList: List<String>) {
            instance?.let {
                dnsIndex = (dnsIndex + 1) % dnsList.size
                it.establishVPN(dnsList[dnsIndex])
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    
    // GPS Optimization Components
    private var locationManager: LocationManager? = null
    private var gpsWakeLock: PowerManager.WakeLock? = null
    private var isGpsOptimized = false
    private var lastGpsFixTime = 0L
    
    // VPN Components
    private val connectionPool = ConnectionPool()
    private val priorityManager = TrafficPriorityManager()
    private val tcpConnections = ConcurrentHashMap<Int, Socket>()
    
    private val workerPool = Executors.newCachedThreadPool()
    private val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        
        // Start VPN
        establishVPN("8.8.8.8")
        
        // Start GPS Optimization jika VPN berjaya
        if (vpnInterface != null) {
            startGpsOptimization()
        }
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "Panda Monitor Pro", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN Tunnel + GPS Optimization"
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(status: String) {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚀 Panda Monitor Pro")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        startForeground(NOTIF_ID, notification)
    }

    fun establishVPN(dns: String) {
        try {
            forwardingActive = false
            connectionPool.closeAll()
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
        } catch (_: Exception) {}

        val builder = Builder()
        builder.setSession("PandaMonitorPro")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }

        if (vpnInterface != null) {
            forwardingActive = true
            updateNotification("VPN Active | GPS: Optimizing...")
            
            // Start VPN components
            startPacketForwarding()
            startPacketProcessor()
            
            scheduledPool.scheduleAtFixedRate({
                connectionPool.cleanupIdleConnections()
            }, 10, 10, TimeUnit.SECONDS)
            
        } else {
            updateNotification("VPN Failed - Restarting...")
            stopGpsOptimization()
        }
    }

    // ==================== GPS OPTIMIZATION ====================
    private fun startGpsOptimization() {
        if (isGpsOptimized) return
        
        workerPool.execute {
            try {
                // 1. Sync NTP Time (Accuracy penting untuk GPS)
                syncNtpTime()
                
                // 2. Acquire Wake Lock (Elak CPU sleep)
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                gpsWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PandaMonitorPro:GPSLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(60*60*1000L) // 1 hour
                }
                
                // 3. Get LocationManager
                locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                
                // 4. Setup GPS Criteria (High Accuracy)
                val criteria = Criteria().apply {
                    accuracy = Criteria.ACCURACY_FINE
                    powerRequirement = Criteria.POWER_HIGH
                    isAltitudeRequired = true
                    isSpeedRequired = true
                    isBearingRequired = true
                    horizontalAccuracy = Criteria.ACCURACY_HIGH
                    verticalAccuracy = Criteria.ACCURACY_HIGH
                }
                
                // 5. Request High-Frequency GPS Updates
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, // GPS sahaja, bukan network
                    1000, // 1 second interval (cepat!)
                    0f,   // No minimum distance
                    locationListener
                )
                
                // 6. Force immediate GPS fix attempt
                val lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                lastLocation?.let {
                    lastGpsFixTime = System.currentTimeMillis()
                    onGpsLocationImproved(it)
                }
                
                isGpsOptimized = true
                updateNotification("VPN Active | GPS: Locked 🔒")
                
                // 7. Periodic GPS health check
                scheduledPool.scheduleAtFixedRate({
                    checkGpsHealth()
                }, 30, 30, TimeUnit.SECONDS)
                
            } catch (e: SecurityException) {
                updateNotification("VPN Active | GPS: Permission needed")
            } catch (e: Exception) {
                updateNotification("VPN Active | GPS: Limited")
            }
        }
    }
    
    private fun syncNtpTime() {
        workerPool.execute {
            try {
                val url = URL("https://time.google.com")
                val conn = url.openConnection() as HttpsURLConnection
                conn.connect()
                val serverTime = conn.date
                if (serverTime > 0) {
                    val timeDiff = abs(serverTime - System.currentTimeMillis())
                    if (timeDiff > 1000) { // Jika lebih 1 second difference
                        SystemClock.setCurrentTimeMillis(serverTime)
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Fallback ke NTP pool
                try {
                    val url2 = URL("https://pool.ntp.org")
                    val conn2 = url2.openConnection()
                    conn2.connect()
                    conn2.getInputStream().close()
                } catch (_: Exception) {}
            }
        }
    }
    
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGpsFixTime = System.currentTimeMillis()
            onGpsLocationImproved(location)
        }
        
        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                updateNotification("VPN Active | GPS: Provider Enabled")
            } else {
                android.util.Log.d("GPS_DEBUG", "Non-GPS provider enabled: $provider")
            }
        }
        
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                updateNotification("VPN Active | GPS: Provider Disabled ⚠️")
            } else {
                android.util.Log.d("GPS_DEBUG", "Non-GPS provider disabled: $provider")
            }
        }
    }
    
    private fun onGpsLocationImproved(location: Location) {
        // Boleh log atau process improved location data di sini
        // Contoh: Calculate accuracy, speed, etc.
        val accuracy = location.accuracy
        val speed = location.speed * 3.6 // Convert ke km/h
        
        // Jika accuracy baik (<10 meter), kita ada solid lock
        if (accuracy > 0 && accuracy < 10.0) {
            // GPS lock yang solid
        }
    }
    
    private fun checkGpsHealth() {
        val now = System.currentTimeMillis()
        val timeSinceLastFix = now - lastGpsFixTime
        
        when {
            timeSinceLastFix > 30000 -> { // 30 seconds tanpa fix
                updateNotification("VPN Active | GPS: Seeking signal...")
                // Trigger re-acquisition
                locationManager?.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    { location -> 
                        lastGpsFixTime = System.currentTimeMillis()
                        onGpsLocationImproved(location)
                    },
                    null
                )
            }
            timeSinceLastFix > 10000 -> { // 10-30 seconds
                updateNotification("VPN Active | GPS: Fair signal")
            }
            else -> {
                updateNotification("VPN Active | GPS: Strong lock 🔒")
            }
        }
    }
    
    private fun stopGpsOptimization() {
        try {
            locationManager?.removeUpdates(locationListener)
            gpsWakeLock?.release()
            gpsWakeLock = null
            isGpsOptimized = false
        } catch (_: Exception) {}
    }
    
    // ==================== VPN PACKET HANDLING ====================
    private fun startPacketForwarding() {
        workerPool.execute {
            val buffer = ByteArray(2048)
            while (forwardingActive && vpnInterface != null) {
                try {
                    val len = FileInputStream(vpnInterface!!.fileDescriptor).read(buffer)
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
        }
    }
    
    private fun startPacketProcessor() {
        workerPool.execute {
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
        val destKey = "${task.destIp}:${task.destPort}"
        
        var socket = connectionPool.getSocket(task.destIp, task.destPort)
        
        if (socket == null || socket.isClosed || !socket.isConnected) {
            socket = try {
                Socket(task.destIp, task.destPort).apply {
                    tcpNoDelay = true
                    soTimeout = 15000
                }
            } catch (e: Exception) {
                null
            }
        }
        
        if (socket != null) {
            // ✅ FIX: Guna blok biasa, bukan .run atau .let
            tcpConnections[task.srcPort] = socket
            
            try {
                socket.getOutputStream().write(task.packet)
                socket.getOutputStream().flush()
                
                // Handler akan jalan setiap kali ada packet baru
                startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
                
            } catch (e: Exception) {
                socket.close()
                tcpConnections.remove(task.srcPort)
            }
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
                    
                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, 
                                              buffer.copyOfRange(0, n))
                    outStream.write(reply)
                    outStream.flush()
                }
            } catch (e: Exception) {
                // Connection closed
            } finally {
                if (socket.isConnected && !socket.isClosed) {
                    connectionPool.returnSocket(destIp, destPort, socket)
                } else {
                    socket.close()
                }
                tcpConnections.remove(srcPort)
            }
        }
    }
    
    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 20) return
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 6) return

            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                        "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or 
                         (packet[ipHeaderLen + 1].toInt() and 0xFF)
            val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or 
                          (packet[ipHeaderLen + 3].toInt() and 0xFF)
            val payload = packet.copyOfRange(ipHeaderLen + 20, packet.size)

            priorityManager.addPacket(payload, destIp, destPort, srcPort)
            
        } catch (_: Exception) {}
    }
    
    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, 
                               destPort: Int, payload: ByteArray): ByteArray {
        // Existing implementation (same as before)
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
        return packet
    }

    override fun onDestroy() {
        forwardingActive = false
        isGpsOptimized = false
        
        // Cleanup VPN
        connectionPool.closeAll()
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        
        // Cleanup GPS
        stopGpsOptimization()
        
        // Shutdown executors
        scheduledPool.shutdownNow()
        workerPool.shutdownNow()
        
        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        
        // Reset state
        pandaActive = false
        lastPacketTime = 0L
        instance = null
        
        updateNotification("Service Stopped")
        super.onDestroy()
    }
}
