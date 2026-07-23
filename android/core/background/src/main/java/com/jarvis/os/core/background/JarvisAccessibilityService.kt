package com.jarvis.os.core.background

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility Service for JARVIS OS.
 * Enables real-time screen understanding, UI element clicking, text entry, scrolling,
 * package condition-waits, and autonomous Android action verification.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun getCurrentPackage(): String {
            val service = instance ?: return ""
            return service.rootInActiveWindow?.packageName?.toString() ?: ""
        }

        fun captureAccessibilityTree(): String {
            val service = instance ?: return "[]"
            val root = service.rootInActiveWindow ?: return "[]"
            val nodesArray = JSONArray()
            service.traverseNode(root, nodesArray)
            return nodesArray.toString()
        }

        fun clickText(targetText: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(targetText)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (service.performClickOnNode(node)) {
                        Log.i(TAG, "Successfully clicked node with text '$targetText'")
                        return true
                    }
                }
            }
            Log.w(TAG, "Could not find clickable node for text '$targetText'")
            return false
        }

        fun typeText(targetFieldText: String, textToEnter: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(targetFieldText)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToEnter)
                    }
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                        Log.i(TAG, "Successfully entered text into field '$targetFieldText'")
                        return true
                    }
                }
            }
            return false
        }

        fun scroll(direction: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val action = if (direction.lowercase() == "up" || direction.lowercase() == "backward") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            return service.findAndPerformScroll(root, action)
        }

        fun pressBack(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun pressHome(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun waitForPackage(targetPackage: String, timeoutMs: Long = 4000L): Boolean {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (getCurrentPackage().contains(targetPackage, ignoreCase = true)) {
                    Log.i(TAG, "Verified package '$targetPackage' in foreground after ${System.currentTimeMillis() - startTime}ms")
                    return true
                }
                try {
                    Thread.sleep(150)
                } catch (e: Exception) {
                    break
                }
            }
            Log.w(TAG, "Timeout waiting for package '$targetPackage'")
            return false
        }

        fun waitForText(targetText: String, timeoutMs: Long = 4000L): Boolean {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val tree = captureAccessibilityTree()
                if (tree.contains(targetText, ignoreCase = true)) {
                    Log.i(TAG, "Verified text '$targetText' visible on screen")
                    return true
                }
                try {
                    Thread.sleep(150)
                } catch (e: Exception) {
                    break
                }
            }
            Log.w(TAG, "Timeout waiting for text '$targetText'")
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active observation of window changes
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "JarvisAccessibilityService unbound.")
        return super.onUnbind(intent)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, jsonArray: JSONArray) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank()) {
            val item = JSONObject().apply {
                if (text.isNotBlank()) put("text", text)
                if (desc.isNotBlank()) put("desc", desc)
                if (id.isNotBlank()) put("id", id)
                put("clickable", node.isClickable)
            }
            jsonArray.put(item)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, jsonArray)
        }
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findAndPerformScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction(action, null))) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndPerformScroll(child, action)) return true
        }
        return false
    }
}
