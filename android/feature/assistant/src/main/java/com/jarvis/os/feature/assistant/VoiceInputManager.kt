package com.jarvis.os.feature.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.jarvis.os.core.speech.MicrophoneStateMachine


/**
 * Manager for voice input using Android SpeechRecognizer.
 *
 * Wraps the SpeechRecognizer API and provides callbacks for
 * collecting voice recognition results.
 *
 * IMPORTANT: SpeechRecognizer must be created and operated on the main thread.
 * All lifecycle calls are posted to the main looper to enforce this constraint.
 *
 * INTEGRATION WITH [MicrophoneStateMachine]:
 *   - [startListening] calls [MicrophoneStateMachine.speechRecognizerAcquire()] to
 *     claim exclusive mic ownership before creating a SpeechRecognizer.
 *   - [release] calls [beginAssistantRelease] / [completeAssistantRelease]
 *     to atomically hand the mic back, enabling the wake-word engine to
 *     reacquire without a race condition.
 */
class VoiceInputManager(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "JarvisVoiceInput"
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Coroutine scope for state machine operations
    private val vmScope = CoroutineScope(Dispatchers.Main + Job())
    
    // Mutex to guard SpeechRecognizer lifecycle
    private val recognizerMutex = Mutex()
    
    // Audio focus management
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    
    /**
     * Start listening for voice input.
     * Always destroys any existing recognizer first to avoid stale-session errors
     * (ERROR_CLIENT / ERROR_NO_MATCH).
     * All operations are posted to the main thread as required by SpeechRecognizer.
     */
    fun startListening() {
        val task = Runnable {
            try {
                // Destroy any stale recognizer from a previous session
                // This prevents ERROR_CLIENT from SpeechRecognizer when reusing
                if (speechRecognizer != null) {
                    Log.w(TAG, "Destroying stale recognizer before new session")
                    destroyRecognizerSync()
                }
                
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e(TAG, "SpeechRecognizer is not available on this device")
                    onError("Speech recognition not available on this device")
                    return@Runnable
                }

                // The state machine now handles reset from overlay-visible states
                // (PROCESSING, AI_RESPONSE, TEXT_TO_SPEECH) → OPEN_OVERLAY → START_SPEECH_RECOGNIZER
                val acquired = runBlocking {
                    MicrophoneStateMachine.speechRecognizerAcquire() &&
                        MicrophoneStateMachine.speechRecognizerStart()
                }
                if (!acquired) {
                    val owner = MicrophoneStateMachine.currentOwner()
                    val state = MicrophoneStateMachine.currentState()
                    Log.e(TAG, "event=\"Mic Acquire Denied\" component=\"SpeechRecognizer\" owner=\"$owner\" state=\"$state\"")
                    onError("Microphone busy — please try again")
                    return@Runnable
                }

                // Request audio focus
                if (!requestAudioFocus()) {
                    Log.e(TAG, "Audio focus request failed")
                    runBlocking { MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.OPEN_OVERLAY) }
                    onError("Audio focus not granted")
                    return@Runnable
                }
                
                // Create recognizer
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer
                
                // Set listener
                recognizer.setRecognitionListener(object : SpeechRecognizerRecognitionListener() {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "event=\"SpeechRecognizer Started\"")
                    }
                    
                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Beginning of speech")
                        runBlocking { MicrophoneStateMachine.speechRecognizerVoiceInput() }
                    }
                    
                    override fun onRmsChanged(rmsdB: Float) {
                        // RMS indicates audio level - can be used for visual feedback
                    }
                    
                    override fun onBufferReceived(buffer: ByteArray?) {
                        Log.d(TAG, "Buffer received: ${buffer?.size} bytes")
                    }
                    
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "End of speech")
                    }
                    
                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error (microphone busy or session reset)"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected — tap the mic to try again"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — tap the mic to try again"
                            else -> "Unknown error: $error"
                        }
                        Log.e(TAG, "Speech recognition error ($error): $message")
                        destroyRecognizerSync()
                        runBlocking {
                            MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.OPEN_OVERLAY)
                        }
                        
                        // For transient errors (no match / timeout), don't propagate as a
                        // hard error — just reset so the user can tap mic again.
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                Log.d(TAG, "Transient error, resetting for next attempt")
                                onError(message)
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                // Transient error - retry
                                Log.d(TAG, "Recognizer busy error - retrying")
                                onError(message)
                            }
                            else -> {
                                onError(message)
                            }
                        }
                    }
                    
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null && matches.isNotEmpty()) {
                            val text = matches[0]
                            Log.i(TAG, "event=\"SpeechRecognizer Stopped\" reason=\"final_result\"")
                            destroyRecognizerSync()
                            runBlocking {
                                MicrophoneStateMachine.speechRecognizerVoiceInput()
                                MicrophoneStateMachine.speechRecognizerProcessing()
                            }
                            Log.i(TAG, "event=\"Message Sent\" source=\"voice\" textLength=\"${text.length}\"")
                            onResult(text, true)
                        } else {
                            onError("No results returned")
                        }
                    }
                    
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null && matches.isNotEmpty()) {
                            val text = matches[0]
                            Log.d(TAG, "Partial result: $text")
                            runBlocking { MicrophoneStateMachine.speechRecognizerVoiceInput() }
                            onResult(text, false)
                        }
                    }
                    
                    override fun onEvent(eventType: Int, params: Bundle?) {
                        Log.d(TAG, "Event: $eventType")
                    }
                })
                
                val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                
                recognizer.startListening(recognitionIntent)
                Log.i(TAG, "event=\"SpeechRecognizer Started\"")
                
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Speech recognition not available", e)
                onError("Speech recognition not available on this device")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                onError("Failed to start listening: ${e.message}")
            }
        }
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }
    
    /**
     * Stop listening and release resources.
     */
    fun stopListening() {
        val task = Runnable {
            try {
                speechRecognizer?.stopListening()
                Log.i(TAG, "SpeechRecognizer stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping listener", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }
    
    /**
     * Request audio focus for speech recognition.
     */
    private fun requestAudioFocus(): Boolean {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.w(TAG, "AudioManager not available")
            return false
        }
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.w(TAG, "Audio focus lost")
                        releaseAudioFocus()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Audio focus lost transient")
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Audio focus lost transient can duck")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                        audioFocusGranted = true
                    }
                }
            }
            .build()
        
        val result = audioManager?.requestAudioFocus(audioFocusRequest!!) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "Audio focus requested: granted=$audioFocusGranted, result=$result")
        return audioFocusGranted
    }
    
    /**
     * Release audio focus.
     */
    private fun releaseAudioFocus() {
        try {
            audioFocusRequest?.let {
                audioManager?.abandonAudioFocusRequest(it)
            }
            audioFocusGranted = false
            Log.i(TAG, "Audio focus released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio focus", e)
        }
    }
    
    /**
     * Check if SpeechRecognizer is available.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
    
    /**
     * Release resources and release mic ownership to the state machine.
     *
     * Uses the proper state-machine API:
     * 1. Transition to CLOSE_ASSISTANT via speechRecognizerClose()
     * 2. Await CLOSE_ASSISTANT
     * 3. Transition to RELEASE_SPEECH_RECOGNIZER via speechRecognizerRelease()
     * 4. Await RELEASE_SPEECH_RECOGNIZER
     * 5. Destroy the SpeechRecognizer
     *
     * This ensures the wake-word engine receives a deterministic "mic free" signal.
     */
    suspend fun release() {
        recognizerMutex.withLock {
            try {
                // Step 1: Close the recognizer (transitions to CLOSE_ASSISTANT)
                val closeSuccess = MicrophoneStateMachine.speechRecognizerClose()
                if (!closeSuccess) {
                    Log.w(TAG, "speechRecognizerClose failed, force-resetting state machine")
                    MicrophoneStateMachine.forceReset("voice_input_release_close_failed")
                }
                
                // Step 2: Await CLOSE_ASSISTANT
                val awaitCloseSuccess = MicrophoneStateMachine.awaitState(
                    MicrophoneStateMachine.State.CLOSE_OVERLAY,
                    2000L
                )
                if (!awaitCloseSuccess) {
                    Log.w(TAG, "Timeout waiting for CLOSE_OVERLAY, force-resetting")
                    MicrophoneStateMachine.forceReset("voice_input_release_close_timeout")
                }
                
                // Step 3: Release the recognizer (transitions to RELEASE_SPEECH_RECOGNIZER)
                val releaseSuccess = MicrophoneStateMachine.speechRecognizerRelease()
                if (!releaseSuccess) {
                    Log.w(TAG, "speechRecognizerRelease failed, force-resetting state machine")
                    MicrophoneStateMachine.forceReset("voice_input_release_release_failed")
                }
                
                // State machine removed — wake word engine manages its own lifecycle.
                Log.i(TAG, "VoiceInputManager released — mic ownership returned to wake word engine")
            } finally {
                // Step 5: Destroy the SpeechRecognizer (always do this to clean up)
                destroyRecognizer()
            }
        }
    }
    
    /**
     * Internal helper — cancels, destroys, and nulls the SpeechRecognizer.
     * MUST be called on the main thread.
     */
    private fun destroyRecognizerSync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "destroyRecognizerSync called outside Main Thread!")
        }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            Log.i(TAG, "event=\"SpeechRecognizer Stopped\" destroyed=\"true\" sync=\"true\"")
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying SpeechRecognizer sync", e)
        } finally {
            speechRecognizer = null
            releaseAudioFocus()
        }
    }

    /**
     * Suspending wrapper that runs destroyRecognizerSync on the Main Thread and
     * suspends until execution completes.
     */
    private suspend fun destroyRecognizer() {
        val deferred = CompletableDeferred<Unit>()
        val task = Runnable {
            destroyRecognizerSync()
            deferred.complete(Unit)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }

        withTimeoutOrNull(1500L) { deferred.await() }
    }
    
    /**
     * Non-suspend wrapper for release() - launches on the provided coroutine scope.
     * Use this when calling from non-suspend contexts (e.g., Service.onDestroy).
     */
    fun releaseAsync(scope: CoroutineScope) {
        scope.launch {
            release()
        }
    }
}

/**
 * Abstract base class for SpeechRecognizer listener that provides
 * default implementations for all callback methods.
 */
abstract class SpeechRecognizerRecognitionListener : 
    android.speech.RecognitionListener {
    
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {}
    override fun onResults(results: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
