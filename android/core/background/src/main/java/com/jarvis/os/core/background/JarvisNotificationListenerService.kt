package com.jarvis.os.core.background

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification Listener Service for JARVIS OS.
 * Reads incoming notifications, WhatsApp messages, emails, and alerts for voice readout and auto-reply.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotificationListener"
        var isRunning: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.i(TAG, "JarvisNotificationListenerService connected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val packageName = it.packageName
            val extras = it.notification.extras
            val title = extras.getString("android.title")
            val text = extras.getCharSequence("android.text")?.toString()
            Log.d(TAG, "Notification received from [$packageName]: $title - $text")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
        Log.i(TAG, "JarvisNotificationListenerService disconnected.")
    }
}
