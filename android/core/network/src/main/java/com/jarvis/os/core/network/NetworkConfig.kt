package com.jarvis.os.core.network

/**
 * Configuration constants for the network module.
 */
object NetworkConfig {
    /**
     * Default WebSocket URL for the backend.
     * 10.0.2.2 is the emulator's host loopback address.
     */
    const val DEFAULT_WS_URL = "ws://10.0.2.2:8000/api/v1/ws"
    
    /**
     * Default WebSocket URL for a real device.
     * Replace with your actual backend IP address.
     */
    const val DEFAULT_WS_URL_REAL_DEVICE = "ws://192.168.1.11:8000/api/v1/ws"
    
    /**
     * Heartbeat interval in milliseconds.
     */
    const val HEARTBEAT_INTERVAL_MS = 30000L
    
    /**
     * Maximum number of reconnection attempts.
     */
    const val MAX_RECONNECT_ATTEMPTS = 5
    
    /**
     * Base URL for HTTP requests (if needed in the future).
     */
    const val BASE_URL = "http://192.168.1.11:8000"
    
    /**
     * Determine the best WebSocket URL to use.
     * Uses real-device URL unless running on an emulator.
     */
    fun getWebSocketUrl(): String {
        return if (android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("emulator") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("Android SDK built for x86")) {
            DEFAULT_WS_URL
        } else {
            DEFAULT_WS_URL_REAL_DEVICE
        }
    }
}
