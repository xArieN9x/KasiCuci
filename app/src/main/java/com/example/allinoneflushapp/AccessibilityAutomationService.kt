package com.example.allinoneflushapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())

        fun clearCacheForceStopApp(packageName: String) {
            val context = AppGlobals.applicationContext
            // Open App Info settings for target app
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            // Small delay to let Settings open
            handler.postDelayed({
                // Simulate Accessibility actions:
                // 1. Scroll to "Force Stop" button
                // 2. Click Force Stop
                // 3. Scroll to "Clear Cache" or "Storage" and tap
                // NOTE: exact node text may vary by ColorOS
            }, 1500)
        }

        @RequiresApi(Build.VERSION_CODES.M)
        fun toggleAirplaneMode() {
            val context = AppGlobals.applicationContext

            // Open Quick Settings Panel (Accessibility)
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            handler.postDelayed({
                // Use AccessibilityService to find Airplane Mode toggle
                // Tap ON → wait 8 sec → Tap OFF
                // Requires UiAutomator or AccessibilityNodeInfo traversal
            }, 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Helper function: traverse UI to find node by text and click
     */
    private fun clickNodeByText(root: AccessibilityNodeInfo?, text: String) {
        root ?: return
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            } else if (node.parent != null) {
                clickNodeByText(node.parent, text)
            }
        }
    }
}
