package com.jarvis.os.core.context.model

/**
 * Physical motion state detected by Activity Recognition API.
 */
enum class MotionType {
    STILL,
    WALKING,
    RUNNING,
    DRIVING,
    CYCLING,
    UNKNOWN
}

/**
 * High-level semantic location category.
 */
enum class SemanticLocation {
    HOME,
    COLLEGE,
    OFFICE,
    GYM,
    HOSPITAL,
    RESTAURANT,
    TRAVEL,
    UNKNOWN
}

/**
 * Network connectivity type.
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    OFFLINE
}

/**
 * System ringer volume profile.
 */
enum class VolumeProfile {
    NORMAL,
    SILENT,
    VIBRATE
}

/**
 * Geographical location snapshot.
 */
data class LocationSnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val semanticLocation: SemanticLocation = SemanticLocation.UNKNOWN,
    val placeName: String? = null
)

/**
 * Motion state snapshot with confidence score (0 - 100%).
 */
data class MotionSnapshot(
    val type: MotionType = MotionType.UNKNOWN,
    val confidence: Int = 0
)

/**
 * Device power snapshot.
 */
data class BatterySnapshot(
    val levelPercentage: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false
)

/**
 * Audio peripheral connection snapshot.
 */
data class AudioSnapshot(
    val isHeadphonesConnected: Boolean = false,
    val isCarBluetoothConnected: Boolean = false,
    val connectedDeviceNames: List<String> = emptyList()
)

/**
 * Unified Immutable Context State object consumed by JARVIS AI and Rule Engine.
 */
data class ContextState(
    val location: LocationSnapshot = LocationSnapshot(),
    val motion: MotionSnapshot = MotionSnapshot(),
    val battery: BatterySnapshot = BatterySnapshot(),
    val audio: AudioSnapshot = AudioSnapshot(),
    val network: NetworkType = NetworkType.OFFLINE,
    val volumeProfile: VolumeProfile = VolumeProfile.NORMAL,
    val foregroundAppPackage: String? = null,
    val currentCalendarEventTitle: String? = null,
    val isScreenOn: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
