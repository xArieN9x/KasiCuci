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
        private const val AIRPLANE_DELAY = 4000L
        private val storageKeys = arrayOf("Storage usage", "Storage usage ", "Storage", "Storage & cache", "App storage")
        private val forceStopKeys = arrayOf("Force stop", "Force stop ", "Force Stop", "Paksa berhenti", "Paksa Hentikan")
        private val confirmOkKeys = arrayOf("OK", "Yes", "Confirm", "Ya", "Force stop ", "Force stop")
        private val clearCacheKeys = arrayOf("Clear cache", "Clear cache ", "Clear Cache", "Kosongkan cache")

        fun requestClearAndForceStop(packageName: String) {
            val ctx = AppGlobals.applicationContext
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(intent)

            handler.postDelayed({
                val svc = AppGlobals.accessibilityService ?: return@postDelayed
                // Click Force Stop in app info
                val clicked = svc.findAndClick(*forceStopKeys)
                if (clicked) {
                    // Confirm dialog - choose Force stop on dialog
                    handler.postDelayed({ svc.findAndClick(*confirmOkKeys) }, 700)
                }
                // After force stop, go to Storage usage then Clear cache
                handler.postDelayed({
                    svc.findAndClick(*storageKeys)
                    handler.postDelayed({ svc.findAndClick(*clearCacheKeys) }, 900)
                    handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 1500)
                }, 1600)
            }, 1200)
        }

        fun requestToggleAirplane() {
            val svc = AppGlobals.accessibilityService ?: return
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                // Try a few localized labels
                val clicked = svc.findAndClick("Airplane mode", "Airplane mode ", "Airplane", "Mod Pesawat", "Mod Penerbangan", "Aeroplane mode")
                if (!clicked) {
                    // fallback: try icon desc scanning
                    svc.findAndClick("Airplane")
                }
                // wait ON
                SystemClock.sleep(AIRPLANE_DELAY)
                // toggle OFF
                svc.findAndClick("Airplane mode", "Airplane mode ", "Airplane", "Mod Pesawat", "Mod Penerbangan")
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
				handler.postDelayed({
		            AppGlobals.accessibilityService?.launchPandaApp()
		        }, 1000)
				
            }, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGlobals.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // Robust search + click using text, content description, viewId
    fun findAndClick(vararg keys: Array<String>): Boolean {
        // not used; kept for compatibility
        return false
    }

    // ===========================================
    // EXTRA: Auto Launch Panda App Helper
    // ===========================================
    fun launchPandaApp() {
        try {
            val pkg = "com.logistics.rider.foodpanda"
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (_: Exception) {}
    }

    fun findAndClick(vararg keys: String, maxRetries: Int = 6, delayMs: Long = 700L): Boolean {
        repeat(maxRetries) {
            val root = rootInActiveWindow
            if (root != null) {
                for (k in keys) {
                    // exact text matches
                    val nodes = root.findAccessibilityNodeInfosByText(k)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            if (n.isClickable) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                            var p = n.parent
                            while (p != null) {
                                if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                                p = p.parent
                            }
                        }
                    }
                    // content-desc scan
                    val desc = findNodeByDescription(root, k)
                    if (desc != null) { desc.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                    // viewId fallback
                    val idNode = findNodeByViewId(root, k)
                    if (idNode != null) {
                        if (idNode.isClickable) { idNode.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                        var p = idNode.parent
                        while (p != null) {
                            if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                            p = p.parent
                        }
                    }
                }
                // try scroll to reveal hidden buttons
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(delayMs)
        }
        return false
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val cd = n.contentDescription
                if (cd != null && cd.toString().contains(text, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, idPart: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val vid = n.viewIdResourceName
                if (vid != null && vid.contains(idPart, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }
}
