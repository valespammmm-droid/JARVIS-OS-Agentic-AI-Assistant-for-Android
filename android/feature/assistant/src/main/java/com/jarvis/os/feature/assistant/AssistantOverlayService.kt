package com.jarvis.os.feature.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.jarvis.os.core.ui.theme.JarvisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob

/**
 * Foreground service that manages the JARVIS assistant overlay UI.
 * Pure UI service that adds/removes floating bottom sheet overlay on WindowManager.
 */
class AssistantOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlay"

        const val ACTION_SHOW_ASSISTANT = "com.jarvis.os.action.SHOW_ASSISTANT"
        const val ACTION_HIDE_ASSISTANT = "com.jarvis.os.action.HIDE_ASSISTANT"
        const val ACTION_TOGGLE = "com.jarvis.os.action.TOGGLE_OVERLAY"
        const val ACTION_WAKE_WORD_DETECTED = "com.jarvis.os.action.WAKE_WORD_DETECTED"

        private const val NOTIFICATION_CHANNEL_ID = "assistant_overlay_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "JARVIS Assistant"
        private const val NOTIFICATION_ID = 1002

        private const val FOREGROUND_SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        @Volatile
        private var instance: AssistantOverlayService? = null

        @Volatile
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var wakeWordReceiver: WakeWordBroadcastReceiver? = null
    private var viewModel: AssistantViewModel? = null
    private var serviceScope: CoroutineScope? = null

    private val lifecycleOwner = OverlayLifecycleOwner()

    @Volatile
    private var isOverlayShowing = false

    private val showHideMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OVERLAY_SERVICE_CREATED")

        instance = this
        isRunning = true

        startForegroundServiceWithNotification()

        lifecycleOwner.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        viewModel = AssistantViewModel(applicationContext)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        registerWakeWordReceiver()
        Log.i(TAG, "Service fully initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_SHOW_ASSISTANT -> {
                Log.i(TAG, "SHOW_ASSISTANT action received")
                handleShowAssistant()
            }
            ACTION_HIDE_ASSISTANT -> {
                Log.i(TAG, "HIDE_ASSISTANT action received")
                handleHideAssistant()
            }
            ACTION_TOGGLE -> {
                Log.i(TAG, "TOGGLE_OVERLAY action received")
                toggleOverlay()
            }
            else -> {
                handleShowAssistant()
            }
        }

        return START_STICKY
    }

    private fun handleShowAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "OVERLAY_PERMISSION_MISSING - cannot show overlay")
            return
        }
        serviceScope?.launch {
            showOverlay()
            viewModel?.onWakeWordDetected()
        }
    }

    private fun handleHideAssistant() {
        serviceScope?.launch {
            Log.i(TAG, "event=\"Overlay Close Requested\"")
            hideOverlay()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AssistantOverlayService = this@AssistantOverlayService
    }

    override fun onDestroy() {
        Log.i(TAG, "OVERLAY_SERVICE_DESTROYED")

        if (isOverlayShowing && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                Log.i(TAG, "OVERLAY_DISMISSED in onDestroy")
            } catch (e: Exception) {
                Log.w(TAG, "REMOVE_VIEW_FAILED in onDestroy", e)
            }
            overlayView = null
            isOverlayShowing = false
        }

        serviceScope?.cancel()

        try {
            wakeWordReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }

        lifecycleOwner.onDestroy()
        instance = null
        isRunning = false

        super.onDestroy()
    }

    private suspend fun showOverlay() = showHideMutex.withLock {
        // If already attached to WindowManager, bring view to front & make visible
        if (isOverlayShowing && overlayView != null) {
            Log.i(TAG, "OVERLAY_ALREADY_ATTACHED_REORDERING_TO_FRONT")
            try {
                overlayView?.visibility = View.VISIBLE
                val params = overlayView?.layoutParams as? WindowManager.LayoutParams
                if (params != null && windowManager != null) {
                    windowManager?.updateViewLayout(overlayView, params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UPDATE_VIEW_LAYOUT_FAILED", e)
            }
            return@withLock
        }

        // Check overlay permission
        if (!hasOverlayPermission()) {
            Log.e(TAG, "OVERLAY_PERMISSION_MISSING")
            return@withLock
        }

        // Remove any stale view if detached
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Stale view removal failed", e)
            }
            overlayView = null
        }

        if (windowManager == null) {
            Log.w(TAG, "Cannot show overlay: windowManager is null")
            return@withLock
        }

        Log.i(TAG, "SHOWING_FRESH_OVERLAY")

        var retryCount = 0
        val maxRetries = 2
        var lastException: Exception? = null

        while (retryCount <= maxRetries) {
            try {
                val cv = ComposeView(this)

                cv.setViewTreeLifecycleOwner(lifecycleOwner)
                cv.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                cv.setViewTreeViewModelStoreOwner(lifecycleOwner)

                cv.setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )

                val vm = viewModel ?: AssistantViewModel(applicationContext)
                cv.setContent {
                    JarvisTheme {
                        AssistantOverlayContent(
                            viewModel = vm,
                            onDismiss = {
                                serviceScope?.launch { hideOverlay() }
                            }
                        )
                    }
                }

                val params = WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val field = WindowManager.LayoutParams::class.java.getField("layoutInDisplayCutoutMode")
                            field.set(this, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set layoutInDisplayCutoutMode: ${e.message}")
                        }
                    }
                }

                windowManager?.addView(cv, params)
                overlayView = cv
                isOverlayShowing = true

                Log.i(TAG, "OVERLAY_DISPLAYED_SUCCESSFULLY")
                return@withLock

            } catch (e: Exception) {
                lastException = e
                retryCount++
                if (retryCount <= maxRetries) {
                    Log.w(TAG, "ADD_VIEW_FAILED exception=$e, retrying ($retryCount/$maxRetries)")
                    delay(100)
                }
            }
        }

        Log.e(TAG, "OVERLAY_SHOW_FAILED: exception=$lastException")
    }

    private suspend fun hideOverlay() = showHideMutex.withLock {
        if (!isOverlayShowing || overlayView == null) {
            Log.w(TAG, "OVERLAY_DISMISSED_SKIPPED_NOT_SHOWING")
            return@withLock
        }

        Log.i(TAG, "HIDING_OVERLAY")

        try {
            windowManager?.removeView(overlayView)
            Log.i(TAG, "OVERLAY_DISMISSED")
        } catch (e: Exception) {
            Log.e(TAG, "REMOVE_VIEW_FAILED exception=$e")
        }

        overlayView = null
        isOverlayShowing = false

        viewModel?.dismissOverlayAndRelease()
    }

    private fun toggleOverlay() {
        serviceScope?.launch {
            if (isOverlayShowing) {
                hideOverlay()
            } else {
                showOverlay()
            }
        }
    }

    private fun registerWakeWordReceiver() {
        wakeWordReceiver = WakeWordBroadcastReceiver()
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_ASSISTANT)
            addAction(ACTION_HIDE_ASSISTANT)
            addAction(ACTION_WAKE_WORD_DETECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeWordReceiver, filter, null, null, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(wakeWordReceiver, filter)
        }
        Log.d(TAG, "Wake word receiver registered")
    }

    private inner class WakeWordBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return

            when (intent.action) {
                ACTION_SHOW_ASSISTANT, ACTION_WAKE_WORD_DETECTED -> {
                    handleShowAssistant()
                }
                ACTION_HIDE_ASSISTANT -> {
                    serviceScope?.launch { hideOverlay() }
                }
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()

        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("JARVIS OS Active")
            .setContentText("Listening for wake word 'Jarvis'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    FOREGROUND_SERVICE_TYPE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for JARVIS Assistant"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
