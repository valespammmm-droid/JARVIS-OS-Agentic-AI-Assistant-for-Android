package com.jarvis.os.core.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneStateMachineTest {

    @Before
    fun setUp() {
        // Reset state machine before each test to guarantee clean slate
        runBlocking { MicrophoneStateMachine.forceReset("Test setup") }
    }

    // ------------------------------------------------------------------
    //  Initial State Tests
    // ------------------------------------------------------------------

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
    }

    @Test
    fun testInitialOwnerIsNone() {
        assertEquals(MicrophoneStateMachine.Owner.NONE, MicrophoneStateMachine.currentOwner())
    }

    // ------------------------------------------------------------------
    //  Wake Word Engine Transitions
    // ------------------------------------------------------------------

    @Test
    fun testWakeWordEngineAcquireFromIdle() {
        runBlocking {
            val acquired = MicrophoneStateMachine.wakeWordEngineAcquire()
            assertTrue(acquired)
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_LISTENING, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testWakeWordEngineAcquireFromIdleReturnsTrueWhenAlreadyAcquired() {
        runBlocking {
            val first = MicrophoneStateMachine.wakeWordEngineAcquire()
            assertTrue(first)

            val second = MicrophoneStateMachine.wakeWordEngineAcquire()
            assertTrue(second)
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_LISTENING, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testWakeWordEngineAcquireFromSpeechRecognizerFails() {
        runBlocking {
            // Start with speech recognizer and start it
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()
            assertEquals(MicrophoneStateMachine.Owner.SPEECH_RECOGNIZER, MicrophoneStateMachine.currentOwner())

            // Try to acquire for wake word - should fail
            val acquired = MicrophoneStateMachine.wakeWordEngineAcquire()
            assertFalse(acquired)
            assertEquals(MicrophoneStateMachine.Owner.SPEECH_RECOGNIZER, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testWakeWordDetectedTransition() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_LISTENING, MicrophoneStateMachine.currentState())

            val result = MicrophoneStateMachine.wakeWordDetected()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_DETECTED, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordEngineStopTransition() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()

            val result = MicrophoneStateMachine.wakeWordEngineStop()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.STOP_WAKEWORD_ENGINE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordEngineReleaseTransition() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()
            MicrophoneStateMachine.wakeWordEngineStop()

            val result = MicrophoneStateMachine.wakeWordEngineRelease()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.RELEASE_MICROPHONE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordEngineRestartTransition() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()
            MicrophoneStateMachine.wakeWordEngineStop()
            MicrophoneStateMachine.wakeWordEngineRelease()

            val result = MicrophoneStateMachine.wakeWordEngineRestart()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.RESTART_WAKEWORD, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordEngineWaitingTransition() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()
            MicrophoneStateMachine.wakeWordEngineStop()
            MicrophoneStateMachine.wakeWordEngineRelease()
            MicrophoneStateMachine.wakeWordEngineRestart()

            val result = MicrophoneStateMachine.wakeWordEngineWaiting()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.WAITING, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordEngineWaitingToListeningLoop() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()
            MicrophoneStateMachine.wakeWordEngineStop()
            MicrophoneStateMachine.wakeWordEngineRelease()
            MicrophoneStateMachine.wakeWordEngineRestart()
            MicrophoneStateMachine.wakeWordEngineWaiting()

            val result = MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.WAKEWORD_LISTENING)
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_LISTENING, MicrophoneStateMachine.currentState())
        }
    }

    // ------------------------------------------------------------------
    //  Speech Recognizer Transitions
    // ------------------------------------------------------------------

    @Test
    fun testSpeechRecognizerAcquireFromIdle() {
        runBlocking {
            val acquired = MicrophoneStateMachine.speechRecognizerAcquire()
            assertTrue(acquired)
            assertEquals(MicrophoneStateMachine.State.OPEN_OVERLAY, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.NONE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testSpeechRecognizerAcquireFromIdleReturnsTrueWhenAlreadyAcquired() {
        runBlocking {
            val first = MicrophoneStateMachine.speechRecognizerAcquire()
            assertTrue(first)

            val second = MicrophoneStateMachine.speechRecognizerAcquire()
            assertTrue(second)
            assertEquals(MicrophoneStateMachine.State.OPEN_OVERLAY, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.NONE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testSpeechRecognizerAcquireFromWakeWordFails() {
        runBlocking {
            // Start with wake word engine
            MicrophoneStateMachine.wakeWordEngineAcquire()
            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())

            // Try to acquire for speech recognizer - should fail
            val acquired = MicrophoneStateMachine.speechRecognizerAcquire()
            assertFalse(acquired)
            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testSpeechRecognizerStartTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            assertEquals(MicrophoneStateMachine.State.OPEN_OVERLAY, MicrophoneStateMachine.currentState())

            val result = MicrophoneStateMachine.speechRecognizerStart()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.START_SPEECH_RECOGNIZER, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testSpeechRecognizerVoiceInputTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()

            val result = MicrophoneStateMachine.speechRecognizerVoiceInput()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.VOICE_INPUT, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testSpeechRecognizerProcessingTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()
            MicrophoneStateMachine.speechRecognizerVoiceInput()

            val result = MicrophoneStateMachine.speechRecognizerProcessing()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.PROCESSING, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testSpeechRecognizerResponseTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()
            MicrophoneStateMachine.speechRecognizerVoiceInput()
            MicrophoneStateMachine.speechRecognizerProcessing()

            val result = MicrophoneStateMachine.speechRecognizerResponse()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.AI_RESPONSE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testSpeechRecognizerCloseTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()
            MicrophoneStateMachine.speechRecognizerVoiceInput()
            MicrophoneStateMachine.speechRecognizerProcessing()
            MicrophoneStateMachine.speechRecognizerResponse()

            val result = MicrophoneStateMachine.speechRecognizerClose()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.CLOSE_OVERLAY, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testSpeechRecognizerReleaseTransition() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()
            MicrophoneStateMachine.speechRecognizerVoiceInput()
            MicrophoneStateMachine.speechRecognizerProcessing()
            MicrophoneStateMachine.speechRecognizerResponse()
            MicrophoneStateMachine.speechRecognizerClose()

            val result = MicrophoneStateMachine.speechRecognizerRelease()
            assertTrue(result)
            assertEquals(MicrophoneStateMachine.State.RELEASE_SPEECH_RECOGNIZER, MicrophoneStateMachine.currentState())
        }
    }

    // ------------------------------------------------------------------
    //  Illegal Transition Tests
    // ------------------------------------------------------------------

    @Test
    fun testIdleToVoiceInputIsIllegal() {
        runBlocking {
            val result = MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.VOICE_INPUT)
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.NONE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testIdleToProcessingIsIllegal() {
        runBlocking {
            val result = MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.PROCESSING)
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testIdleToResponseIsIllegal() {
        runBlocking {
            val result = MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.AI_RESPONSE)
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testWakeWordToSpeechRecognizerIsIllegal() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()

            val result = MicrophoneStateMachine.speechRecognizerAcquire()
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_DETECTED, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testSpeechRecognizerToWakeWordIsIllegal() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.speechRecognizerStart()

            val result = MicrophoneStateMachine.wakeWordEngineAcquire()
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.START_SPEECH_RECOGNIZER, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.SPEECH_RECOGNIZER, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testIllegalTransitionDoesNotThrow() {
        runBlocking {
            // Should never throw, only return false
            try {
                MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.VOICE_INPUT)
                MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.PROCESSING)
                MicrophoneStateMachine.transitionTo(MicrophoneStateMachine.State.AI_RESPONSE)
            } catch (e: Exception) {
                fail("Illegal transition should not throw: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    //  awaitState Tests
    // ------------------------------------------------------------------

    @Test
    fun testAwaitStateImmediatelySucceeds() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            val result = MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_LISTENING)
            assertTrue(result)
        }
    }

    @Test
    fun testAwaitStateTimeout() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            // Start a transition that will take time
            async {
                delay(100)
                MicrophoneStateMachine.wakeWordDetected()
            }
            val result = MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_DETECTED, timeoutMs = 50L)
            assertFalse(result)
        }
    }

    @Test
    fun testAwaitStateTimeoutCancelsOnForceReset() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            val deferred = async {
                MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_DETECTED, timeoutMs = 10000L)
            }
            delay(50)
            MicrophoneStateMachine.forceReset("Test timeout cancellation")
            val result = deferred.await()
            assertFalse(result)
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testAwaitStateFullCycle() {
        runBlocking {
            // Wake word engine cycle
            assertTrue(MicrophoneStateMachine.wakeWordEngineAcquire())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_LISTENING))

            assertTrue(MicrophoneStateMachine.wakeWordDetected())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_DETECTED))

            assertTrue(MicrophoneStateMachine.wakeWordEngineStop())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.STOP_WAKEWORD_ENGINE))

            assertTrue(MicrophoneStateMachine.wakeWordEngineRelease())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.RELEASE_MICROPHONE))

            // Speech recognizer cycle
            assertTrue(MicrophoneStateMachine.speechRecognizerAcquire())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.OPEN_OVERLAY))

            assertTrue(MicrophoneStateMachine.speechRecognizerStart())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.START_SPEECH_RECOGNIZER))

            assertTrue(MicrophoneStateMachine.speechRecognizerVoiceInput())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.VOICE_INPUT))

            assertTrue(MicrophoneStateMachine.speechRecognizerProcessing())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.PROCESSING))

            assertTrue(MicrophoneStateMachine.speechRecognizerResponse())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.AI_RESPONSE))

            assertTrue(MicrophoneStateMachine.speechRecognizerClose())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.CLOSE_OVERLAY))

            assertTrue(MicrophoneStateMachine.speechRecognizerRelease())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.RELEASE_SPEECH_RECOGNIZER))

            // Back to wake word
            assertTrue(MicrophoneStateMachine.wakeWordEngineRestart())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.RESTART_WAKEWORD))

            assertTrue(MicrophoneStateMachine.wakeWordEngineWaiting())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAITING))

            assertTrue(MicrophoneStateMachine.wakeWordEngineWaiting())
            assertTrue(MicrophoneStateMachine.awaitState(MicrophoneStateMachine.State.WAKEWORD_LISTENING))
        }
    }

    // ------------------------------------------------------------------
    //  Force Reset Tests
    // ------------------------------------------------------------------

    @Test
    fun testForceResetReturnsToIdle() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()
            MicrophoneStateMachine.wakeWordDetected()
            assertEquals(MicrophoneStateMachine.State.WAKEWORD_DETECTED, MicrophoneStateMachine.currentState())

            MicrophoneStateMachine.forceReset("Test force reset")
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
            assertEquals(MicrophoneStateMachine.Owner.NONE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testForceResetWithReason() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()
            MicrophoneStateMachine.forceReset("Service crash")
            assertEquals(MicrophoneStateMachine.State.IDLE, MicrophoneStateMachine.currentState())
        }
    }

    // ------------------------------------------------------------------
    //  Concurrent Transition Tests
    // ------------------------------------------------------------------

    @Test
    fun testConcurrentWakeWordAcquiresAreSerialized() {
        runBlocking {
            MicrophoneStateMachine.wakeWordEngineAcquire()

            val deferred1 = async {
                MicrophoneStateMachine.wakeWordEngineAcquire()
            }
            val deferred2 = async {
                MicrophoneStateMachine.wakeWordEngineAcquire()
            }

            // Both should succeed because they are idempotent
            assertTrue(deferred1.await())
            assertTrue(deferred2.await())

            assertEquals(MicrophoneStateMachine.State.WAKEWORD_LISTENING, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testConcurrentSpeechRecognizerAcquiresAreSerialized() {
        runBlocking {
            MicrophoneStateMachine.speechRecognizerAcquire()

            val deferred1 = async {
                MicrophoneStateMachine.speechRecognizerAcquire()
            }
            val deferred2 = async {
                MicrophoneStateMachine.speechRecognizerAcquire()
            }

            // Both should succeed because they are idempotent
            assertTrue(deferred1.await())
            assertTrue(deferred2.await())

            assertEquals(MicrophoneStateMachine.State.OPEN_OVERLAY, MicrophoneStateMachine.currentState())
        }
    }

    @Test
    fun testConcurrentWakeWordAndSpeechRecognizerAcquiresAreSerialized() {
        runBlocking {
            // Start with wake word
            MicrophoneStateMachine.wakeWordEngineAcquire()

            val deferred1 = async {
                MicrophoneStateMachine.speechRecognizerAcquire()
            }
            val deferred2 = async {
                MicrophoneStateMachine.wakeWordEngineAcquire()
            }

            // Speech recognizer should fail (wake word owns mic)
            assertFalse(deferred1.await())

            // Wake word acquire should succeed because it already owns the mic (idempotent)
            assertTrue(deferred2.await())

            assertEquals(MicrophoneStateMachine.Owner.WAKE_WORD_ENGINE, MicrophoneStateMachine.currentOwner())
        }
    }

    @Test
    fun testConcurrentStateTransitionsAreSerialized() {
        runBlocking {
            // Start with wake word
            MicrophoneStateMachine.wakeWordEngineAcquire()

            // Launch concurrent transitions
            val deferred1 = async {
                MicrophoneStateMachine.wakeWordDetected()
            }
            val deferred2 = async {
                MicrophoneStateMachine.speechRecognizerAcquire()
            }
            val deferred3 = async {
                MicrophoneStateMachine.wakeWordEngineStop()
            }

            // Wake word detected should succeed
            assertTrue(deferred1.await())

            // Speech recognizer acquire should fail (wake word still owns mic)
            assertFalse(deferred2.await())

            // Wake word stop should succeed
            assertTrue(deferred3.await())

            assertEquals(MicrophoneStateMachine.State.STOP_WAKEWORD_ENGINE, MicrophoneStateMachine.currentState())
        }
    }
}
