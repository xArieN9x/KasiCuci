package com.example.allinoneflushapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.URL
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var networkIndicator: ImageView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnAccessibilityOn: Button
    private lateinit var btnForceCloseAll: Button
    private val pandaPackage = "com.logistics.rider.foodpanda"
    private val dnsList = listOf("1.1.1.1", "156.154.70.1", "8.8.8.8", "76.76.2.0")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            actuallyStartVpnService()
        } else {
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingWidget()
        } else {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGlobals.applicationContext = application
        setContentView(R.layout.activity_main)
        textViewIP = findViewById(R.id.textViewIP)
        textViewDNS = findViewById(R.id.textViewDNS)
        networkIndicator = findViewById(R.id.networkIndicator)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnAccessibilityOn = findViewById(R.id.btnAccessibilityOn)
        btnForceCloseAll = findViewById(R.id.btnForceCloseAll)
        updateIP()
        rotateDNS()
        startNetworkMonitor()
        btnDoAllJob.setOnClickListener { doAllJobSequence() }
        btnAccessibilityOn.setOnClickListener { openAccessibilitySettings() }
        btnForceCloseAll.setOnClickListener { forceCloseAllAndExit() }
    }

    override fun onResume() {
        super.onResume()
        if (isPandaRunning() && !FloatingWidgetService.isRunning()) {
            checkAndStartFloatingWidget()
        }
    }

    private fun actuallyStartVpnService() {
        stopService(Intent(this, AppMonitorVPNService::class.java))
        startService(Intent(this, AppMonitorVPNService::class.java))
        AppMonitorVPNService.rotateDNS(dnsList)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            actuallyStartVpnService()
        }
    }

    private fun checkAndStartFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                startFloatingWidget()
            }
        } else {
            startFloatingWidget()
        }
    }

    private fun startFloatingWidget() {
        if (!FloatingWidgetService.isRunning()) {
            val intent = Intent(this, FloatingWidgetService::class.java)
            startService(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 1000)
        }
    }

    private fun isPandaRunning(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningApps = activityManager.runningAppProcesses ?: return false
        return runningApps.any { it.processName == pandaPackage }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Toast.makeText(this, "Enable 'CB Accessibility Engine'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceCloseAllAndExit() {
        Toast.makeText(this, "Closing all...", Toast.LENGTH_SHORT).show()
        if (FloatingWidgetService.isRunning()) {
            stopService(Intent(this, FloatingWidgetService::class.java))
        }
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.killBackgroundProcesses(pandaPackage)
        Handler(Looper.getMainLooper()).postDelayed({
            finishAffinity()
            exitProcess(0)
        }, 1500)
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            var ip: String? = null
            try {
                val url = URL("https://1.1.1.1/cdn-cgi/trace")
                val text = url.readText().trim()
                // Format: ip=123.123.123.123
                val ipLine = text.lines().find { it.startsWith("ip=") }
                ip = ipLine?.substringAfter("=")?.trim()
            } catch (e1: Exception) {
                // Fallback to ipify jika 1.1.1.1 gagal
                try {
                    ip = URL("https://api.ipify.org").readText().trim()
                } catch (e2: Exception) {
                    ip = null
                }
            }
            withContext(Dispatchers.Main) {
                textViewIP.text = if (ip.isNullOrEmpty()) "Public IP: —" else "Public IP: $ip"
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
        AppMonitorVPNService.rotateDNS(dnsList = listOf(selectedDNS))
    }

    private fun startNetworkMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val connected = AppMonitorVPNService.isPandaActive()
                withContext(Dispatchers.Main) {
                    networkIndicator.setImageResource(
                        if (connected) R.drawable.green_circle else R.drawable.red_circle
                    )
                }
                delay(1500)
            }
        }
    }

    private fun doAllJobSequence() {
        AccessibilityAutomationService.requestClearAndForceStop(pandaPackage)
        CoroutineScope(Dispatchers.Main).launch {
            delay(7000)
            AccessibilityAutomationService.requestToggleAirplane()
            // ✅ Tunda lebih lama untuk network stabil
            delay(9000) // dari 5500 → 9000
            bringAppToForeground()
            delay(1000)
    
            // ✅ FETCH IP DULU (dengan logik asal, tiada 'break')
            updateIP()
    
            delay(1500)
            Toast.makeText(this@MainActivity, "Setting up VPN tunnel...", Toast.LENGTH_SHORT).show()
            requestVpnPermission()
            delay(2100)
    
            rotateDNS()
            launchPandaApp()
            delay(1500)
            checkAndStartFloatingWidget()
        }
    }

    private fun bringAppToForeground() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    private fun launchPandaApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pandaPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Panda launched!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch Panda", Toast.LENGTH_SHORT).show()
        }
    }
}
