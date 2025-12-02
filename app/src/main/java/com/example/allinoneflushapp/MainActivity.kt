package com.example.allinoneflushapp

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var networkIndicator: ImageView
    private lateinit var btnDoAllJob: Button

    private val pandaPackage = "com.logistics.rider.foodpanda"
    private val dnsList = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "9.9.9.10")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGlobals.applicationContext = application
        setContentView(R.layout.activity_main)

        textViewIP = findViewById(R.id.textViewIP)
        textViewDNS = findViewById(R.id.textViewDNS)
        networkIndicator = findViewById(R.id.networkIndicator)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)

        updateIP()
        rotateDNS()
        startNetworkMonitor()

        btnDoAllJob.setOnClickListener {
            doAllJob()
        }
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ip = URL("https://api.ipify.org").readText().trim()
                runOnUiThread { textViewIP.text = "Public IP: $ip" }
            } catch (e: Exception) {
                runOnUiThread { textViewIP.text = "Public IP: Error" }
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
        AppMonitorVPNService.rotateDNS(listOf(selectedDNS))
    }

    private fun startNetworkMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val connected = AppMonitorVPNService.isPandaActive()
                runOnUiThread {
                    networkIndicator.setImageResource(
                        if (connected) R.drawable.green_circle else R.drawable.red_circle
                    )
                }
                delay(3000)
            }
        }
    }

    private fun doAllJob() {
        AccessibilityAutomationService.clearCacheForceStopApp(pandaPackage)
        AccessibilityAutomationService.toggleAirplaneMode()
        rotateDNS()
        updateIP()
    }
}
