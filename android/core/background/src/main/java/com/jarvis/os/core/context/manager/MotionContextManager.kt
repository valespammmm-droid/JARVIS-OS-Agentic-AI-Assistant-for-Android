package com.jarvis.os.core.context.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.jarvis.os.core.context.model.MotionSnapshot
import com.jarvis.os.core.context.model.MotionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MotionContextManager {
    val motionState: StateFlow<MotionSnapshot>
    fun startMonitoring()
    fun stopMonitoring()
}

/**
 * Motion Context Manager utilizing Google Play Services ActivityRecognitionClient
 * for low-power hardware-accelerated motion state detection.
 */
class MotionContextManagerImpl(
    private val context: Context
) : MotionContextManager {

    companion object {
        private const val TAG = "JarvisMotionMgr"
        private const val ACTION_ACTIVITY_TRANSITION = "com.jarvis.os.ACTION_ACTIVITY_TRANSITION"
        private const val DETECTION_INTERVAL_MS = 15_000L
    }

    private val activityRecognitionClient: ActivityRecognitionClient =
        ActivityRecognition.getClient(context)

    private val _motionState = MutableStateFlow(MotionSnapshot(MotionType.STILL, 100))
    override val motionState: StateFlow<MotionSnapshot> = _motionState.asStateFlow()

    private var isMonitoring = false

    private val transitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                for (event in result.transitionEvents) {
                    val motionType = mapDetectedActivityToMotion(event.activityType)
                    _motionState.value = MotionSnapshot(motionType, 90)
                    Log.i(TAG, "Motion transition detected: $motionType")
                }
            } else if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                val mostProbable = result.mostProbableActivity
                val motionType = mapDetectedActivityToMotion(mostProbable.type)
                _motionState.value = MotionSnapshot(motionType, mostProbable.confidence)
                Log.d(TAG, "Activity recognition result: $motionType (Confidence: ${mostProbable.confidence}%)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startMonitoring() {
        if (isMonitoring) return
        if (!hasMotionPermission()) {
            Log.w(TAG, "Activity Recognition permission missing.")
            return
        }

        isMonitoring = true
        Log.i(TAG, "Starting Motion Context monitoring...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    transitionReceiver,
                    IntentFilter(ACTION_ACTIVITY_TRANSITION),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(transitionReceiver, IntentFilter(ACTION_ACTIVITY_TRANSITION))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register transitionReceiver", e)
        }
    }

    override fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            context.unregisterReceiver(transitionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }
        isMonitoring = false
        Log.i(TAG, "Stopped Motion Context monitoring.")
    }

    private fun mapDetectedActivityToMotion(activityType: Int): MotionType {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> MotionType.DRIVING
            DetectedActivity.ON_BICYCLE -> MotionType.CYCLING
            DetectedActivity.RUNNING -> MotionType.RUNNING
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionType.WALKING
            DetectedActivity.STILL -> MotionType.STILL
            else -> MotionType.UNKNOWN
        }
    }

    private fun hasMotionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
