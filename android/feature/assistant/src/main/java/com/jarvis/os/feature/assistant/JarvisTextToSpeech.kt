package com.jarvis.os.feature.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.os.core.speech.GlobalAssistantStateMachine
import com.jarvis.os.core.speech.GlobalAssistantStateMachine.AssistantState
import java.util.Locale

/**
 * TextToSpeech manager for JARVIS OS.
 * Binds speech synthesis playback directly to GlobalAssistantStateMachine.
 */
class JarvisTextToSpeech(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JarvisTTS"
        private const val UTTERANCE_ID = "com.jarvis.os.UTTERANCE_JARVIS"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        try {
            Log.d(TAG, "Initializing Speech Synthesis...")
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language US is not supported or missing data")
            } else {
                isInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "TTS Utterance started: $utteranceId")
                        GlobalAssistantStateMachine.transitionToSync(AssistantState.SPEAKING)
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "TTS Utterance completed: $utteranceId")
                        GlobalAssistantStateMachine.transitionToSync(AssistantState.CLOSING)
                        GlobalAssistantStateMachine.transitionToSync(AssistantState.WAKELISTENING)
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS Utterance error: $utteranceId")
                        GlobalAssistantStateMachine.transitionToSync(AssistantState.WAKELISTENING)
                    }
                })
                Log.i(TAG, "Speech Synthesis initialized successfully")
            }
        } else {
            Log.e(TAG, "Speech Synthesis initialization failed with status: $status")
        }
    }

    /**
     * Synthesize and play the given text.
     */
    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized. Speak request ignored.")
            return
        }

        val cleanText = sanitizeMarkdown(text)
        if (cleanText.isBlank()) return

        Log.d(TAG, "Speaking: $cleanText")
        try {
            GlobalAssistantStateMachine.transitionToSync(AssistantState.SPEAKING)
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio feedback", e)
        }
    }

    /**
     * Stop any current speech synthesis.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Release Speech Synthesizer resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down Speech Synthesis")
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS shutdown", e)
        } finally {
            tts = null
            isInitialized = false
        }
    }

    /**
     * Simple sanitization of markdown syntax for natural speech.
     */
    private fun sanitizeMarkdown(text: String): String {
        return text
            .replace(Regex("\\*\\*"), "")
            .replace(Regex("\\*"), "")
            .replace(Regex("`"), "")
            .replace(Regex("\\[USER_REPLY\\]|\\[/USER_REPLY\\]|\\[DEVELOPER_TRACE\\]|\\[/DEVELOPER_TRACE\\]"), "")
            .trim()
    }
}
