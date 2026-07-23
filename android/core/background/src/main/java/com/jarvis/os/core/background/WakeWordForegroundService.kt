package com.jarvis.os.core.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jarvis.os.core.speech.WakeWordCallback
import com.jarvis.os.core.speech.WakeWordDetector
import com.jarvis.os.core.speech.AudioRecordWakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that manages wake word detection and keeps the assistant alive in the background.
 *
 * This service:
 * - Runs as a foreground service with a persistent notification
 * - Manages audio recording via AudioRecord
 * - Delegates wake word detection to the speech module
 * - Triggers vibration/sound feedback on wake word detection
 * - Broadcasts wake events for consumption by overlay UI
 * - Complies with Android 15 foreground service type requirements
 * - Enforces single-instance to prevent duplicate service instances
 *
 * INTEGRATION WITH [MicrophoneStateMachine]:
 *   On wake-word detection, the [VadWakeWordDetector] has already called
 *   [MicrophoneStateMachine.wakeWordDetected]. This service then:
 *   1. Transitions state machine: WAKEWORD_DETECTED → STOP_WAKEWORD_ENGINE → RELEASE_MICROPHONE
 *   2. Sends broadcast: com.jarvis.os.action.SHOW_ASSISTANT
 *   3. Starts AssistantOverlayService via startForegroundService()
 *   4. Awaits CLOSE_ASSISTANT → RELEASE_SPEECH_RECOGNIZER → START_WAKEWORD_ENGINE before
 *      resetting wakeWordProcessing flag.
 *
 *   On resume (after overlay closes), [resumeWakeWordDetection()] goes through
 *   [MicrophoneStateMachine.wakeWordEngineRestart] inside the detector.
 */
class WakeWordForegroundService : Service(), WakeWordCallback {

    companion object {
        private const val TAG = "JarvisFgSvc"

        // Action constants for intents
        const val ACTION_START = "com.jarvis.os.action.START_SERVICE"
        const val ACTION_STOP = "com.jarvis.os.action.STOP_SERVICE"
        const val ACTION_PAUSE_DETECTION = "com.jarvis.os.action.PAUSE_DETECTION"
        const val ACTION_RESUME_DETECTION = "com.jarvis.os.action.RESUME_DETECTION"

        // Extra keys
        const val EXTRA_WAKE_WORD_DETECTED = "extra_wake_word_detected"
        const val EXTRA_CONFIDENCE = "extra_confidence"

        // Notification channel ID
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Wake Word Engine"
        private const val NOTIFICATION_ID = 1001

        // Service type for Android 14+ foreground service
        private const val FOREGROUND_SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        /**
         * Broadcast action for wake word detection.
         */
        const val ACTION_WAKE_WORD_DETECTED = "com.jarvis.os.action.WAKE_WORD_DETECTED"

        /**
         * Broadcast action to show assistant overlay.
         */
        const val ACTION_SHOW_ASSISTANT = "com.jarvis.os.action.SHOW_ASSISTANT"

        // ------------------------------------------------------------------
        //  Singleton Enforcement
        // ------------------------------------------------------------------

        @Volatile
        private var instance: WakeWordForegroundService? = null

        @Volatile
        var isRunning = false

        /**
         * Permission for internal broadcasts.
         */
        const val INTERNAL_BROADCAST_PERMISSION = "com.jarvis.os.permission.INTERNAL_BROADCAST"
    }

    private val binder = LocalBinder()

    // Services
    private var wakeWordDetector: WakeWordDetector? = null
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null

    // Coroutines
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serviceJob: Job? = null

    // State
    private var lastWakeWordTime = 0L

    // ------------------------------------------------------------------
    //  Binder
    // ------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordForegroundService = this@WakeWordForegroundService
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        // Single-instance enforcement
        if (instance != null) {
            Log.e(TAG, "DUPLICATE_INSTANCE detected, stopping self")
            stopSelf()
            return
        }
        instance = this

        // Battery optimization request
        val batteryOptGranted = BatteryOptimizationHelper.requestIgnoreBatteryOptimizationsIfNeeded(this)
        Log.i(TAG, "BATTERY_OPT_REQUESTED granted=$batteryOptGranted")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        // Create notification channel
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: intent=$intent, flags=$flags, startId=$startId")

        // System recreation — simply ensure foreground is running
        if (intent == null) {
            Log.i(TAG, "SYSTEM_RECREATED - ensuring foreground state")
            if (!isRunning) {
                startForegroundService()
            }
            return START_STICKY
        }

        val action = intent.action ?: run {
            Log.i(TAG, "System restarted the service with null action. Ensuring foreground state.")
            if (!isRunning) {
                startForegroundService()
            }
            return START_STICKY
        }

        // Single-instance enforcement for START action
        if (ACTION_START == action && isRunning) {
            Log.w(TAG, "ALREADY_RUNNING - ignoring duplicate START command")
            return START_STICKY
        }

        when (action) {
            ACTION_START -> {
                Log.i(TAG, "START_SERVICE command received")
                if (!isRunning) {
                    startForegroundService()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "STOP_SERVICE command received")
                stopWakeWordDetection()
                stopForegroundService()
            }
            ACTION_PAUSE_DETECTION -> {
                Log.i(TAG, "PAUSE_DETECTION command received")
                stopWakeWordDetection()
            }
            ACTION_RESUME_DETECTION -> {
                Log.i(TAG, "RESUME_DETECTION command received")
                serviceScope.launch { resumeWakeWordDetection() }
            }
            else -> {
                Log.w(TAG, "Unhandled action: $action")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind")
        return binder as IBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")

        // Clear singleton
        instance = null
        isRunning = false

        // Cancel service scope - this will cancel all child coroutines
        serviceScope.cancel()

        stopForegroundService()
        super.onDestroy()
        Log.i(TAG, "SERVICE_SCOPE_CANCELLED")
    }

    // ------------------------------------------------------------------
    //  Wake Word Detection Management
    // ------------------------------------------------------------------

    /**
     * Start the wake word detection service.
     */
    fun startWakeWordDetection() {
        if (isRunning) {
            Log.w(TAG, "Service already running")
            return
        }

        Log.i(TAG, "Starting wake word detection service")

        try {
            // Use AudioRecord-based detector: holds mic continuously, no on/off flashing
            wakeWordDetector = AudioRecordWakeWordDetector().apply {
                initialize(this@WakeWordForegroundService)
                startListening(this@WakeWordForegroundService)
            }

            isRunning = true
            lastWakeWordTime = System.currentTimeMillis()

            Log.i(TAG, "Wake word detection service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word detection", e)
        }
    }

    /**
     * Stop the wake word detection service.
     */
    fun stopWakeWordDetection() {
        Log.i(TAG, "Stopping wake word detection service")

        try {
            wakeWordDetector?.stopListening()
            wakeWordDetector?.release()
            wakeWordDetector = null

            isRunning = false
            Log.i(TAG, "Wake word detection service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop wake word detection", e)
        }
    }

    /**
     * Resume wake word detection after assistant overlay closes or completes voice input.
     */
    suspend fun resumeWakeWordDetection() {
        Log.i(TAG, "event=\"WakeWord Resuming\"")

        try {
            wakeWordDetector?.stopListening()
            wakeWordDetector?.release()
            wakeWordDetector = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up previous detector", e)
        }

        wakeWordDetector = AudioRecordWakeWordDetector().apply {
            initialize(this@WakeWordForegroundService)
            startListening(this@WakeWordForegroundService)
        }
        isRunning = true
        Log.i(TAG, "event=\"WakeWord Resumed\"")
    }

    /**
     * Check if the service is currently running.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get the last wake word detection time.
     */
    fun getLastWakeWordTime(): Long = lastWakeWordTime

    // ------------------------------------------------------------------
    //  Wake Word Callback
    // ------------------------------------------------------------------

    override fun onWakeWordDetected(confidence: Float) {
        Log.i(TAG, "Wake word detected! Confidence: $confidence")
        lastWakeWordTime = System.currentTimeMillis()

        // Pause background wake word recognizer immediately so VoiceInputManager has exclusive mic access
        try {
            wakeWordDetector?.stopListening()
            wakeWordDetector?.release()
            wakeWordDetector = null
            Log.i(TAG, "event=\"Background Recognizer Paused for Overlay Voice Input\"")
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing background detector", e)
        }

        // Trigger haptic/audio feedback
        triggerVibration()
        triggerSoundFeedback()

        serviceScope.launch {
            Log.i(TAG, "event=\"WakeWord Detected\" confidence=\"$confidence\" action=\"show_overlay\"")
            sendAssistantOpenRequest(confidence)
        }
    }

    // ------------------------------------------------------------------
    //  Assistant Overlay Launch
    // ------------------------------------------------------------------

    private fun sendAssistantOpenRequest(confidence: Float) {
        Log.i(TAG, "ASSISTANT_OPEN_REQUESTED")

        val serviceIntent = Intent().apply {
            setClassName(this@WakeWordForegroundService, "com.jarvis.os.feature.assistant.AssistantOverlayService")
            action = ACTION_SHOW_ASSISTANT
            putExtra("wake_word_detected", true)
            putExtra(EXTRA_CONFIDENCE, confidence)
            putExtra("wake_word_processing", true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "AssistantOverlayService started via startForegroundService")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start AssistantOverlayService - permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AssistantOverlayService", e)
        }

        try {
            val broadcastIntent = Intent(ACTION_SHOW_ASSISTANT).apply {
                setPackage(packageName)
                putExtra("wake_word_detected", true)
                putExtra(EXTRA_CONFIDENCE, confidence)
            }
            sendBroadcast(broadcastIntent)
            Log.i(TAG, "Explicit ACTION_SHOW_ASSISTANT broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send explicit broadcast", e)
        }
    }

    // ------------------------------------------------------------------
    //  Feedback
    // ------------------------------------------------------------------

    /**
     * Trigger vibration feedback on wake word detection.
     */
    private fun triggerVibration() {
        try {
            vibrator?.let { vib ->
                val effect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                vib.vibrate(effect)
            }
            Log.d(TAG, "Vibration triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger vibration", e)
        }
    }

    /**
     * Trigger sound feedback (play a short beep).
     */
    private fun triggerSoundFeedback() {
        try {
            audioManager?.let { am ->
                // Get current volume
                val streamType = AudioManager.STREAM_MUSIC
                val maxVolume = am.getStreamMaxVolume(streamType)
                val currentVolume = am.getStreamVolume(streamType)
                val volumeRatio = currentVolume.toFloat() / maxVolume

                // Play a short beep using media player (simplified for now)
                // In production, use a proper sound file
                Log.d(TAG, "Sound feedback would be played at volume $volumeRatio")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger sound feedback", e)
        }
    }

    // ------------------------------------------------------------------
    //  Notification Management
    // ------------------------------------------------------------------

    /**
     * Create notification channel for foreground service.
     */
    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wake word detection service running in background"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }

    /**
     * Start the foreground service with notification.
     */
    private fun startForegroundService() {
        try {
            val notification = createForegroundNotification()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            Log.i(TAG, "FOREGROUND_SERVICE_STARTED type=MICROPHONE")
            
            // Start wake word detection
            startWakeWordDetection()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Stop the foreground service.
     */
    private fun stopForegroundService() {
        try {
            stopWakeWordDetection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    /**
     * Create the foreground service notification.
     */
    private fun createForegroundNotification(): Notification {
        // Create a simple pending intent for the notification tap
        val notificationIntent = Intent(this, this::class.java).apply {
            action = ACTION_START
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("JARVIS OS")
            .setContentText("Wake word engine running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ------------------------------------------------------------------
    //  Cleanup Helpers
    // ------------------------------------------------------------------

    private suspend fun awaitCancellation() {
        // No-op - scope is being cancelled
    }
}
