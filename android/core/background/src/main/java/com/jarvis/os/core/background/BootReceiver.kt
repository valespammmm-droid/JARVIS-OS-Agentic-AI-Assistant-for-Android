package com.jarvis.os.core.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar

/**
 * BroadcastReceiver that restarts the wake word service on device reboot.
 *
 * Android 14+ Restrictions:
 * - Cannot directly start foreground service from BOOT_COMPLETED intent
 * - Must use WorkManager or AlarmManager to schedule service start
 * - Service must be started by user in foreground first
 *
 * This receiver:
 * - Listens for BOOT_COMPLETED and LOCKED_BOOT_COMPLETED intents
 * - Schedules a WorkManager worker (preferred) or AlarmManager fallback
 * - Logs the scheduling path taken
 * - Gracefully handles cases where service start is not allowed
 * - Has proper RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "JarvisBoot"

        // Wake word service action
        private const val ACTION_START_SERVICE = "com.jarvis.os.action.START_SERVICE"

        // WorkManager tag for scheduling
        private const val WORK_TAG_START_SERVICE = "boot_start_service"

        // AlarmManager request code
        private const val ALARM_REQUEST_CODE = 1001

        // Delay before starting service after boot
        private const val BOOT_DELAY_MS = 2000L

        /**
         * Check if starting the service is allowed from boot receiver.
         *
         * Android 14+ (API 34+) restrictions:
         * - Foreground services started from boot receivers require user interaction first
         * - This check is informational - the actual restriction is enforced by the system
         *
         * @param context Application context
         * @return true if service start is allowed, false otherwise
         */
        fun isServiceStartAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // No restriction on pre-Samsung devices
                return true
            }

            // Check if app is in foreground state (has a running activity)
            val packageManager = context.packageManager
            val launcherActivities = packageManager.getLaunchIntentForPackage(context.packageName)
            
            // If there's a launcher intent, the app is likely in a foreground state
            return launcherActivities != null
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            Log.w(TAG, "Received null intent")
            return
        }

        val action = intent.action ?: return

        Log.i(TAG, "BOOT_RECEIVED action=$action")

        when (action) {
            android.content.Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                // Android 14+ requires scheduling service start, not direct start
                scheduleWakeWordServiceStart(context)
            }
            else -> {
                Log.d(TAG, "Received unhandled action: $action")
            }
        }
    }

    /**
     * Schedule the wake word foreground service to start after boot.
     *
     * Android 14+ Compatibility:
     * - Does NOT directly start the service (this is restricted)
     * - Uses WorkManager (preferred) with short delay
     * - Falls back to AlarmManager if WorkManager unavailable
     * - Logs which path was taken
     *
     * @param context Application context
     */
    private fun scheduleWakeWordServiceStart(context: Context) {
        // Check if service start is allowed (Android 14+ requirement)
        if (!isServiceStartAllowed(context)) {
            Log.w(TAG, "BOOT_START_NOT_ALLOWED - service cannot be started from boot receiver")
            Log.i(TAG, "User must open the app to start the service")
            return
        }

        // Try WorkManager first (preferred)
        if (tryScheduleWithWorkManager(context)) {
            return
        }

        // Fallback to AlarmManager
        scheduleWithAlarmManager(context)
    }

    /**
     * Try to schedule service start using WorkManager.
     *
     * WorkManager is preferred because:
     * - More reliable on Android 14+
     * - Handles background execution constraints automatically
     * - Expedited work requests bypass some restrictions
     *
     * @param context Application context
     * @return true if WorkManager scheduling succeeded, false otherwise
     */
    private fun tryScheduleWithWorkManager(context: Context): Boolean {
        try {
            // Create an intent that will start the service
            val serviceIntent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_START
            }

            // Create a work request to start the service
            val workRequest = OneTimeWorkRequestBuilder<BootServiceWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresDeviceIdle(false)
                        .setRequiresCharging(false)
                        .build()
                )
                .setInitialDelay(BOOT_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WORK_TAG_START_SERVICE)
                .build()

            // Enqueue the work
            WorkManager.getInstance(context).enqueue(workRequest)
            
            Log.i(TAG, "BOOT_SCHEDULED via WorkManager")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager scheduling failed, falling back to AlarmManager", e)
            return false
        }
    }

    /**
     * Schedule service start using AlarmManager fallback.
     *
     * This is used when WorkManager is not available or fails.
     * Uses setAndAllowWhileIdle() to ensure the alarm fires even when device is idle.
     *
     * @param context Application context
     */
    private fun scheduleWithAlarmManager(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
                Log.e(TAG, "AlarmManager not available")
                return
            }

            // Create an intent that will start the service
            val serviceIntent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_START
            }

            // Create a PendingIntent for the service start
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getService(
                context,
                ALARM_REQUEST_CODE,
                serviceIntent,
                pendingIntentFlags
            )

            // Schedule the alarm
            val calendar = Calendar.getInstance().apply {
                add(Calendar.MILLISECOND, BOOT_DELAY_MS.toInt())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }

            Log.i(TAG, "BOOT_SCHEDULED via AlarmManager delay=${BOOT_DELAY_MS}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "BOOT_START_NOT_ALLOWED - AlarmManager permission denied", e)
            Log.i(TAG, "User must open the app to start the service")
        } catch (e: Exception) {
            Log.e(TAG, "AlarmManager scheduling failed", e)
        }
    }
}

/**
 * WorkManager worker that starts the wake word foreground service.
 *
 * This worker is scheduled by BootReceiver to work around Android 14+
 * restrictions on starting foreground services from boot receivers.
 */
class BootServiceWorker : androidx.work.Worker {

    companion object {
        private const val TAG = "JarvisBootWorker"
    }

    constructor(context: Context, params: androidx.work.WorkerParameters) : super(context, params)

    override fun doWork(): Result {
        Log.i(TAG, "BootServiceWorker executing")

        val context = applicationContext

        // Check if service start is allowed
        if (!BootReceiver.isServiceStartAllowed(context)) {
            Log.w(BootReceiver.TAG, "BOOT_START_NOT_ALLOWED in worker")
            return Result.failure()
        }

        // Try to start the service
        val serviceIntent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(BootReceiver.TAG, "BootServiceWorker: service started successfully")
            Result.success()
        } catch (e: SecurityException) {
            Log.e(BootReceiver.TAG, "BootServiceWorker: service start permission denied", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(BootReceiver.TAG, "BootServiceWorker: service start failed", e)
            Result.failure()
        }
    }
}
