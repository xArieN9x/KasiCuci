package com.example.allinoneflushapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private const val airplaneDelay: Long = 8000

        fun clearCacheForceStopApp(packageName: String) {
            val context = AppGlobals.applicationContext
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            handler.postDelayed({
                val service = AppGlobals.accessibilityService ?: return@postDelayed
                service.clickNodeByText("Force Stop")
                handler.postDelayed({
                    service.clickNodeByText("OK") // Confirm
                    service.clickNodeByText("Clear Cache")
                }, 1000)
            }, 2000)
        }

        @RequiresApi(23)
        fun toggleAirplaneMode() {
            val service = AppGlobals.accessibilityService ?: return
            service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                service.clickNodeByText("Airplane mode")
                SystemClock.sleep(airplaneDelay)
                service.clickNodeByText("Airplane mode")
                service.performGlobalAction(GLOBAL_ACTION_HOME)
            }, 1500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGlobals.accessibilityService = this
    }

    fun clickNodeByText(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (nodes.isNotEmpty()) {
            nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}
