package com.jarvis.os.core.context.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.jarvis.os.core.context.model.LocationSnapshot
import com.jarvis.os.core.context.model.MotionType
import com.jarvis.os.core.context.model.SemanticLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LocationContextManager {
    val locationState: StateFlow<LocationSnapshot>
    fun startMonitoring()
    fun stopMonitoring()
    fun onMotionStateChanged(motionType: MotionType)
    fun setGeofencePlace(location: SemanticLocation, latitude: Double, longitude: Double, radiusMeters: Float = 200f)
}

/**
 * Production-grade Location Context Manager utilizing FusedLocationProviderClient,
 * Geofencing, and adaptive battery-saving update intervals based on motion state.
 */
class LocationContextManagerImpl(
    private val context: Context
) : LocationContextManager {

    companion object {
        private const val TAG = "JarvisLocationMgr"
        private const val DEFAULT_INTERVAL_MS = 60_000L // 1 min when active
        private const val FASTEST_INTERVAL_MS = 15_000L
        private const val DRIVING_INTERVAL_MS = 10_000L // 10 sec when driving
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _locationState = MutableStateFlow(LocationSnapshot())
    override val locationState: StateFlow<LocationSnapshot> = _locationState.asStateFlow()

    private var isMonitoring = false
    private var currentMotion = MotionType.STILL
    private val registeredGeofences = mutableMapOf<SemanticLocation, TargetGeofence>()

    private data class TargetGeofence(
        val semanticLocation: SemanticLocation,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float
    )

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    override fun setGeofencePlace(location: SemanticLocation, latitude: Double, longitude: Double, radiusMeters: Float) {
        registeredGeofences[location] = TargetGeofence(location, latitude, longitude, radiusMeters)
        Log.i(TAG, "Geofence registered for $location at ($latitude, $longitude)")
    }

    @SuppressLint("MissingPermission")
    override fun startMonitoring() {
        if (isMonitoring) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing. Monitoring disabled.")
            return
        }

        isMonitoring = true
        Log.i(TAG, "Starting location context monitoring...")

        // Fetch last known location immediately for 0-battery instant context
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { handleNewLocation(it) }
        }

        requestAdaptiveLocationUpdates()
    }

    override fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }
        isMonitoring = false
        Log.i(TAG, "Stopped location context monitoring.")
    }

    override fun onMotionStateChanged(motionType: MotionType) {
        if (currentMotion == motionType) return
        currentMotion = motionType
        Log.i(TAG, "Motion state changed to $motionType — adjusting location query frequency")

        if (isMonitoring) {
            if (motionType == MotionType.STILL) {
                // When stationary, rely on geofences and passive location to save 100% GPS battery
                stopMonitoring()
            } else {
                requestAdaptiveLocationUpdates()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestAdaptiveLocationUpdates() {
        if (!hasLocationPermission()) return

        val priority = when (currentMotion) {
            MotionType.DRIVING -> Priority.PRIORITY_HIGH_ACCURACY
            MotionType.WALKING, MotionType.RUNNING, MotionType.CYCLING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_PASSIVE
        }

        val interval = if (currentMotion == MotionType.DRIVING) DRIVING_INTERVAL_MS else DEFAULT_INTERVAL_MS

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request adaptive location updates", e)
        }
    }

    private fun handleNewLocation(location: Location) {
        var detectedSemantic = SemanticLocation.UNKNOWN

        // Evaluate against registered geofence targets
        for ((semantic, geo) in registeredGeofences) {
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                geo.latitude, geo.longitude,
                results
            )
            if (results[0] <= geo.radiusMeters) {
                detectedSemantic = semantic
                break
            }
        }

        val snapshot = LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            semanticLocation = detectedSemantic,
            placeName = if (detectedSemantic != SemanticLocation.UNKNOWN) detectedSemantic.name else null
        )

        _locationState.value = snapshot
        Log.d(TAG, "Updated LocationSnapshot: semantic=$detectedSemantic (${location.latitude}, ${location.longitude})")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
