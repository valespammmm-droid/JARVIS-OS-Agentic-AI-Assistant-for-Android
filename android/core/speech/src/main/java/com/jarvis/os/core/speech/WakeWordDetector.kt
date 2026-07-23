package com.jarvis.os.core.speech

import android.content.Context

/**
 * Contract for wake-word detectors.
 * Implementations must hold the microphone for the minimum time required
 * and provide lifecycle methods that exactly mirror AudioRecord/SpeechRecognizer
 * ownership transitions.
 */
interface WakeWordDetector {
    fun initialize(context: Context): Boolean
    fun startListening(callback: WakeWordCallback)
    fun stopListening()
    fun release()
    fun isListening(): Boolean
    fun getCurrentConfidence(): Float
}
