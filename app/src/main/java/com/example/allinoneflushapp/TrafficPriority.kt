package com.example.allinoneflushapp

import java.util.concurrent.PriorityBlockingQueue

data class PacketTask(
    val packet: ByteArray,
    val destIp: String,
    val destPort: Int,
    val srcPort: Int,
    val priority: Int, // 3=HIGH, 2=NORMAL, 1=LOW
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PacketTask> {
    override fun compareTo(other: PacketTask): Int {
        // Higher priority first, then older packets first
        return if (this.priority != other.priority) {
            other.priority - this.priority
        } else {
            (this.timestamp - other.timestamp).toInt()
        }
    }
}

class TrafficPriorityManager {
    private val packetQueue = PriorityBlockingQueue<PacketTask>()
    
    fun addPacket(packet: ByteArray, destIp: String, destPort: Int, srcPort: Int) {
        val priority = when {
            EndpointConfig.CRITICAL_ENDPOINTS.any { destIp.contains(it) } -> 3
            EndpointConfig.BACKGROUND_ENDPOINTS.any { destIp.contains(it) } -> 1
            else -> 2
        }
        
        packetQueue.put(PacketTask(packet, destIp, destPort, srcPort, priority))
    }
    
    fun takePacket(): PacketTask? {
        return try {
            packetQueue.take()
        } catch (e: InterruptedException) {
            null
        }
    }
    
    fun queueSize(): Int = packetQueue.size
}
