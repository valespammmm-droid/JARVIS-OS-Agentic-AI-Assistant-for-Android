package com.jarvis.os.core.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Production Wake-Word Detector — Single-Instance Zero-Flicker Architecture.
 * Keeps a single long-lived SpeechRecognizer instance alive inside the Foreground Service.
 * Eliminates mic ON/OFF flickering and repeated object allocation loops.
 */
class AudioRecordWakeWordDetector : WakeWordDetector {

    companion object {
        private const val TAG = "JarvisAudioRec"
        private const val RESTART_DELAY_MS = 200L
    }

    private var appContext: Context? = null
    private var wakeWordCallback: WakeWordCallback? = null
    @Volatile private var isInitialized = false
    @Volatile private var isCurrentlyListening = false
    @Volatile private var isPaused = false
    @Volatile private var isActive = false
    @Volatile private var currentConfidence = 0.95f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val wakeWords = listOf(
        "jarvis", "hey jarvis", "jarvis jarvis",
        "jarves", "hey jarves",
        "javis", "hey javis",
        "jarvies", "hey jarvies",
        "garvis", "hey garvis",
        "jervis", "hey jervis",
        "jar vis", "hey jar vis",
        "charvis", "hey charvis",
        "jarvas", "hey jarvas"
    )

    override fun initialize(context: Context): Boolean {
        if (isInitialized) return true
        appContext = context.applicationContext

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing")
            return false
        }

        if (!SpeechRecognizer.isRecognitionAvailable(appContext!!)) {
            Log.e(TAG, "SpeechRecognizer not available on device")
            return false
        }

        isInitialized = true
        Log.i(TAG, "WakeWord Detector Initialized ONCE successfully.")
        return true
    }

    override fun startListening(callback: WakeWordCallback) {
        if (!isInitialized) return
        if (isCurrentlyListening && !isPaused) return

        wakeWordCallback = callback
        isCurrentlyListening = true
        isPaused = false
        isActive = true

        Log.i(TAG, "Starting continuous wake word recognizer")
        mainHandler.post { initAndStartRecognizer() }
    }

    override fun isListening(): Boolean = isCurrentlyListening && !isPaused

    override fun getCurrentConfidence(): Float = currentConfidence

    fun pauseListening() {
        Log.i(TAG, "Pausing wake word detector for SpeechRecognizer mic handoff")
        isPaused = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recognizer on pause: ${e.message}")
            }
        }
    }

    fun resumeListening() {
        if (!isInitialized || !isActive) return
        Log.i(TAG, "Resuming wake word detector after conversation end")
        isPaused = false
        isCurrentlyListening = true
        mainHandler.post { initAndStartRecognizer() }
    }

    override fun stopListening() {
        Log.i(TAG, "stopListening called")
        isActive = false
        isCurrentlyListening = false
        isPaused = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { destroyRecognizer() }
    }

    override fun release() {
        Log.i(TAG, "release called")
        isActive = false
        isCurrentlyListening = false
        isPaused = false
        isInitialized = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { destroyRecognizer() }
    }

    private fun initAndStartRecognizer() {
        if (!isActive || isPaused) return

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(createListener())
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext?.packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error in initAndStartRecognizer: ${e.message}")
            scheduleRestart(1000L)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
        }
        speechRecognizer = null
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!isActive || isPaused) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isActive && !isPaused) {
                initAndStartRecognizer()
            }
        }, delayMs)
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!isActive || isPaused) return
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onResults(results: Bundle?) {
            processMatches(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processMatches(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processMatches(matches: List<String>?) {
        if (matches.isNullOrEmpty() || !isActive || isPaused) return

        for (hypothesis in matches) {
            val text = hypothesis.lowercase().trim()
            if (wakeWords.any { text.contains(it) }) {
                Log.i(TAG, "🔥 WAKE WORD DETECTED: '$text'")
                currentConfidence = 0.95f
                wakeWordCallback?.onWakeWordDetected(currentConfidence)
                break
            }
        }
    }
}
