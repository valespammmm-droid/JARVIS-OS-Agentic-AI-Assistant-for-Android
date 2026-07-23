package com.jarvis.os.core.speech

/**
 * Callback interface fired when the wake word is detected.
 */
fun interface WakeWordCallback {
    fun onWakeWordDetected(confidence: Float)
}
