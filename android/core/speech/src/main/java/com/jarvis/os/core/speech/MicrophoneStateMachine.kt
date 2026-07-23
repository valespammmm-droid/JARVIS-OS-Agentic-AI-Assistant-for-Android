package com.jarvis.os.core.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single Source of Truth Finite State Machine for JARVIS OS Audio & Microphone Ownership.
 * Enforces single microphone ownership between WakeWord Engine and SpeechRecognizer.
 */
object MicrophoneStateMachine {
    private const val TAG = "MicStateMachine"

    enum class State {
        DISABLED,
        INITIALIZING,
        WAKELISTENING,
        WAKEDETECTED,
        OPENINGOVERLAY,
        OPEN_OVERLAY,
        LISTENING,
        THINKING,
        SPEAKING,
        CLOSING,
        CLOSE_OVERLAY
    }

    enum class Owner {
        NONE,
        WAKE_WORD_ENGINE,
        SPEECH_RECOGNIZER
    }

    private val _stateFlow = MutableStateFlow(State.DISABLED)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _ownerFlow = MutableStateFlow(Owner.NONE)
    val ownerFlow: StateFlow<Owner> = _ownerFlow.asStateFlow()

    private val mutex = Mutex()

    fun currentState(): State = _stateFlow.value
    fun currentOwner(): Owner = _ownerFlow.value

    suspend fun transitionTo(newState: State, newOwner: Owner = _ownerFlow.value): Boolean = mutex.withLock {
        val oldState = _stateFlow.value
        val oldOwner = _ownerFlow.value
        Log.i(TAG, "State Transition: [$oldState | $oldOwner] -> [$newState | $newOwner]")
        _stateFlow.value = newState
        _ownerFlow.value = newOwner
        return true
    }

    suspend fun wakeWordEngineAcquire(): Boolean = mutex.withLock {
        if (_ownerFlow.value == Owner.NONE || _ownerFlow.value == Owner.WAKE_WORD_ENGINE) {
            _stateFlow.value = State.WAKELISTENING
            _ownerFlow.value = Owner.WAKE_WORD_ENGINE
            Log.i(TAG, "WakeWord Engine acquired microphone")
            return true
        }
        return false
    }

    suspend fun speechRecognizerAcquire(): Boolean = mutex.withLock {
        _stateFlow.value = State.LISTENING
        _ownerFlow.value = Owner.SPEECH_RECOGNIZER
        Log.i(TAG, "SpeechRecognizer acquired microphone")
        return true
    }

    suspend fun speechRecognizerStart(): Boolean = mutex.withLock {
        if (_ownerFlow.value == Owner.SPEECH_RECOGNIZER) {
            _stateFlow.value = State.LISTENING
            return true
        }
        return false
    }

    suspend fun speechRecognizerVoiceInput(): Boolean = mutex.withLock {
        if (_ownerFlow.value == Owner.SPEECH_RECOGNIZER) {
            _stateFlow.value = State.LISTENING
            return true
        }
        return false
    }

    suspend fun speechRecognizerProcessing(): Boolean = mutex.withLock {
        _stateFlow.value = State.THINKING
        return true
    }

    suspend fun speechRecognizerClose(): Boolean = mutex.withLock {
        _stateFlow.value = State.CLOSE_OVERLAY
        return true
    }

    suspend fun speechRecognizerRelease(): Boolean = mutex.withLock {
        _stateFlow.value = State.WAKELISTENING
        _ownerFlow.value = Owner.WAKE_WORD_ENGINE
        Log.i(TAG, "Microphone returned to WakeWord Engine")
        return true
    }

    suspend fun releaseToSpeechRecognizer(): Boolean = speechRecognizerAcquire()
    suspend fun releaseToWakeWordEngine(): Boolean = speechRecognizerRelease()

    suspend fun awaitState(targetState: State, timeoutMs: Long = 2000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            _stateFlow.first { it == targetState }
            true
        } ?: false
    }

    suspend fun forceReset(reason: String) = mutex.withLock {
        Log.w(TAG, "forceReset called. Reason: $reason")
        _stateFlow.value = State.WAKELISTENING
        _ownerFlow.value = Owner.WAKE_WORD_ENGINE
    }
}