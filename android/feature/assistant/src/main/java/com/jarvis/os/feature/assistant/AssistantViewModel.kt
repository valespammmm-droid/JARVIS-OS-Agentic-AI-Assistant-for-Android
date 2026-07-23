package com.jarvis.os.feature.assistant

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import com.jarvis.os.core.network.JarvisWebSocketClient
import com.jarvis.os.core.network.WsMessage
import com.jarvis.os.core.speech.MicrophoneStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Role of a message in the conversation.
 */
enum class MessageRole {
    USER,
    ASSISTANT
}

/**
 * A single message in the conversation.
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val isStreaming: Boolean = false
)

/**
 * UI state for the assistant overlay.
 */
data class AssistantUiState(
    val isOverlayVisible: Boolean = false,
    val isListening: Boolean = false,
    val isConnected: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val currentStreamingText: String = "",
    val isTyping: Boolean = false,
    val voiceTranscript: String = ""
)

/**
 * ViewModel for the assistant overlay.
 *
 * Manages:
 * - Conversation state (messages)
 * - WebSocket connection and streaming
 * - Voice input state
 */
class AssistantViewModel(private val context: Context) : ViewModel() {
    
    companion object {
        private const val TAG = "JarvisVM"
    }
    
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    
    private val mutex = Mutex()
    private var webSocketClient: JarvisWebSocketClient? = null
    private var currentStreamingJob: Job? = null
    private var webSocketCollectionJob: Job? = null
    private var connectionStateJob: Job? = null
    private var responseTimeoutJob: Job? = null
    
    // Voice Input & TTS engines
    private val textToSpeech = JarvisTextToSpeech(context)
    
    private val voiceInputManager = VoiceInputManager(
        context = context,
        onResult = { text, isFinal ->
            viewModelScope.launch {
                mutex.withLock {
                    if (isFinal) {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            voiceTranscript = ""
                        )
                        isVoiceInputActive.set(false)
                        // Auto-send voice input to backend
                        sendText(text)
                        resumeWakeWordService()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            voiceTranscript = text
                        )
                    }
                }
            }
        },
        onError = { error ->
            viewModelScope.launch {
                mutex.withLock {
                    _uiState.value = _uiState.value.copy(
                        isListening = false
                    )
                    isVoiceInputActive.set(false)
                }
                Log.e(TAG, "Voice input error: $error")
                resumeWakeWordService()
            }
        }
    )
    
    // Command Executor for local Android Intent actions
    private val commandExecutor = JarvisCommandExecutor(context)
    
    // Guard against duplicate voice input starts
    private val isVoiceInputActive = AtomicBoolean(false)
    
    /**
     * Wake word detected — show the overlay chat UI and start voice recognition for user query.
     */
    fun onWakeWordDetected() {
        viewModelScope.launch {
            mutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isOverlayVisible = true
                )
            }
            Log.i(TAG, "event=\"WakeWord Detected\" overlay=\"showing\" voiceInput=\"auto-starting\"")
            startVoiceInput()
        }
    }
    
    /**
     * Send text message to the backend or handle local Intent action.
     */
    fun sendText(text: String) {
        if (text.isBlank()) return
        
        Log.i(TAG, "sendText: $text")
        
        // Stop any current text synthesis before user speaks/types again
        textToSpeech.stop()
        
        viewModelScope.launch {
            // Check if command is an immediate local device action
            val localResult = commandExecutor.tryExecuteLocalCommand(text)

            mutex.withLock {
                val newMessages = _uiState.value.messages + ChatMessage(
                    role = MessageRole.USER,
                    text = text,
                    isStreaming = false
                )

                if (localResult != null) {
                    val finalMessages = newMessages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = localResult,
                        isStreaming = false
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = finalMessages,
                        isTyping = false
                    )
                    textToSpeech.speak(localResult)
                    Log.i(TAG, "event=\"Local Command Executed\" action=\"$localResult\"")
                } else {
                    _uiState.value = _uiState.value.copy(
                        messages = newMessages,
                        currentStreamingText = "",
                        isTyping = true
                    )
                }
            }

            if (localResult != null) return@launch

            startResponseTimeout()
            
            // Ensure WebSocket is connected
            if (!ensureWebSocketConnected()) {
                Log.e(TAG, "WS_NOT_CONNECTED - cannot send text")
                mutex.withLock {
                    val currentState = _uiState.value
                    val errorMessages = currentState.messages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = "Connection failed. Please check your network and try again.",
                        isStreaming = false
                    )
                    _uiState.value = currentState.copy(
                        messages = errorMessages,
                        isTyping = false
                    )
                    Log.i(TAG, "event=\"Compose State Updated\" isTyping=\"false\" reason=\"connection_failed\"")
                }
                return@launch
            }
            
            // Send message to backend agent
            try {
                webSocketClient?.sendText(text)
                Log.i(TAG, "event=\"Message Sent\" source=\"text\" textLength=\"${text.length}\"")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send text via WebSocket", e)
                mutex.withLock {
                    _uiState.value = _uiState.value.copy(isTyping = false)
                    Log.i(TAG, "event=\"Compose State Updated\" isTyping=\"false\" reason=\"send_failed\"")
                }
            }
        }
    }
    
    /**
     * Start voice input.
     */
    fun startVoiceInput() {
        // Stop speech feedback when user starts talking
        textToSpeech.stop()
        
        viewModelScope.launch {
            mutex.withLock {
                // Guard against duplicate starts
                if (isVoiceInputActive.get()) {
                    Log.w(TAG, "Voice input already active, skipping")
                    return@launch
                }
                isVoiceInputActive.set(true)
                
                _uiState.value = _uiState.value.copy(
                    isListening = true,
                    voiceTranscript = ""
                )
            }
            
            pauseWakeWordService()
            kotlinx.coroutines.delay(100)

            try {
                voiceInputManager.startListening()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                mutex.withLock {
                    _uiState.value = _uiState.value.copy(
                        isListening = false
                    )
                    isVoiceInputActive.set(false)
                }
                resumeWakeWordService()
            }
        }
    }
    
    /**
     * Stop voice input.
     */
    fun stopVoiceInput() {
        viewModelScope.launch {
            mutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isListening = false
                )
                isVoiceInputActive.set(false)
            }
            voiceInputManager.stopListening()
            resumeWakeWordService()
        }
    }

    private fun pauseWakeWordService() {
        try {
            val intent = android.content.Intent().apply {
                setClassName(context, "com.jarvis.os.core.background.WakeWordForegroundService")
                action = "com.jarvis.os.action.PAUSE_DETECTION"
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send PAUSE_DETECTION", e)
        }
    }

    private fun resumeWakeWordService() {
        try {
            val intent = android.content.Intent().apply {
                setClassName(context, "com.jarvis.os.core.background.WakeWordForegroundService")
                action = "com.jarvis.os.action.RESUME_DETECTION"
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send RESUME_DETECTION", e)
        }
    }
    
    /**
     * Dismiss the overlay.
     */
    fun dismissOverlay() {
        viewModelScope.launch {
            dismissOverlayAndRelease()
        }
    }

    suspend fun dismissOverlayAndRelease() {
        mutex.withLock {
            _uiState.value = _uiState.value.copy(
                isOverlayVisible = false,
                isListening = false
            )
            Log.i(TAG, "event=\"Overlay Dismissed\"")
        }

        try {
            textToSpeech.stop()
        } catch (e: Exception) {
            Log.e(TAG, "TTS_STOP_FAILED: ${e.message}")
        }

        // Do NOT release VoiceInputManager — wake word engine keeps running independently
        isVoiceInputActive.set(false)
    }
    
    /**
     * Connect to WebSocket server.
     * Uses connectionMutex to prevent duplicate client creation from concurrent callers.
     */
    private val connectionMutex = Mutex()
    
    private suspend fun connectWebSocket() {
        connectionMutex.withLock {
            // Double-check inside lock: another caller may have connected while we waited
            if (webSocketClient?.isConnected() == true) {
                Log.d(TAG, "WebSocket already connected")
                return
            }
            
            // Tear down any previous orphaned client cleanly
            val existing = webSocketClient
            if (existing != null) {
                if (existing.isConnected() || existing.isConnecting()) {
                    waitForConnection(existing)
                    return
                }
                existing.close()
            }
            
            val client = JarvisWebSocketClient(viewModelScope)
            webSocketClient = client
            
            // Subscribe to connection state changes
            connectionStateJob?.cancel()
            connectionStateJob = viewModelScope.launch {
                client.connectionState.collect { state ->
                    val isConnected = state == JarvisWebSocketClient.ConnectionState.CONNECTED
                        // Update isConnected state without injecting error chat bubbles
                        _uiState.value = _uiState.value.copy(
                            isConnected = isConnected
                        )
                    Log.i(TAG, "event=\"Compose State Updated\" isConnected=\"$isConnected\" wsState=\"$state\"")
                }
            }
            
            webSocketCollectionJob = viewModelScope.launch {
                client.incomingMessages().collect { message ->
                    handleIncomingMessage(message)
                }
            }
            
            viewModelScope.launch {
                client.connect()
            }
            
            // Wait up to 5 seconds for connection to be active
            var attempts = 0
            while (!client.isConnected() && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            if (client.isConnected()) {
                Log.i(TAG, "event=\"WebSocket Connected\" attempts=\"$attempts\"")
            } else {
                Log.w(TAG, "WebSocket connection timed out after 5 seconds")
            }
        }
    }
    
    /**
     * Ensure WebSocket is connected before sending text.
     * Attempts reconnect with bounded retries.
     */
    private suspend fun ensureWebSocketConnected(): Boolean {
        connectWebSocket()
        val client = webSocketClient ?: return false
        waitForConnection(client)
        return client.isConnected()
    }

    private suspend fun waitForConnection(client: JarvisWebSocketClient, timeoutMs: Long = 10000L): Boolean {
        var elapsedMs = 0L
        while (!client.isConnected() && client.isConnecting() && elapsedMs < timeoutMs) {
            kotlinx.coroutines.delay(100)
            elapsedMs += 100
        }
        return client.isConnected()
    }

    private fun startResponseTimeout(timeoutMs: Long = 15000L) {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            mutex.withLock {
                if (_uiState.value.isTyping) {
                    val messages = _uiState.value.messages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = "Request timed out. Please try again.",
                        isStreaming = false
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = messages,
                        currentStreamingText = "",
                        isTyping = false
                    )
                    Log.w(TAG, "event=\"Compose State Updated\" isTyping=\"false\" reason=\"response_timeout_15s\"")
                }
            }
        }
    }
    
    /**
     * Disconnect from WebSocket server.
     */
    private fun disconnectWebSocket() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        webSocketCollectionJob?.cancel()
        webSocketCollectionJob = null
        connectionStateJob?.cancel()
        connectionStateJob = null
        
        viewModelScope.launch {
            webSocketClient?.disconnect()
            webSocketClient = null
            mutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isTyping = false
                )
                Log.i(TAG, "event=\"Compose State Updated\" isConnected=\"false\" isTyping=\"false\"")
            }
        }
    }
    
    /**
     * Handle an incoming WebSocket message.
     */
    private fun handleIncomingMessage(message: WsMessage) {
        when (message) {
            is WsMessage.Connected -> {
                Log.i(TAG, "Backend handshake connected: ${message.text}")
            }

            is WsMessage.StreamStart -> {
                viewModelScope.launch {
                    mutex.withLock {
                        _uiState.value = _uiState.value.copy(
                            currentStreamingText = "",
                            isTyping = true
                        )
                    }
                }
            }

            is WsMessage.StreamChunk -> {
                viewModelScope.launch {
                    mutex.withLock {
                        val currentState = _uiState.value
                        val updatedStreamingText = currentState.currentStreamingText + message.text
                        val updatedMessages = currentState.messages.toMutableList()

                        if (updatedMessages.isNotEmpty() &&
                            updatedMessages.last().role == MessageRole.ASSISTANT &&
                            updatedMessages.last().isStreaming) {
                            updatedMessages[updatedMessages.size - 1] = updatedMessages.last().copy(
                                text = updatedStreamingText
                            )
                        } else {
                            updatedMessages.add(ChatMessage(
                                role = MessageRole.ASSISTANT,
                                text = updatedStreamingText,
                                isStreaming = true
                            ))
                        }

                        _uiState.value = currentState.copy(
                            currentStreamingText = updatedStreamingText,
                            messages = updatedMessages
                        )
                    }
                }

                if (message.done) {
                    finalizeStreamingMessage()
                }
            }

            is WsMessage.StreamEnd -> {
                finalizeStreamingMessage()
            }

            is WsMessage.ToolCall -> {
                Log.i(TAG, "ToolCall from server: action=${message.action}, pkg=${message.packageName}")
                viewModelScope.launch {
                    val localResult = JarvisCommandExecutor.executeCommand(
                        context = context,
                        action = message.action,
                        packageName = message.packageName,
                        appName = message.appName,
                        target = message.target
                    )
                    if (localResult != null) {
                        mutex.withLock {
                            val currentState = _uiState.value
                            val updatedMessages = currentState.messages + ChatMessage(
                                role = MessageRole.ASSISTANT,
                                text = localResult,
                                isStreaming = false
                            )
                            _uiState.value = currentState.copy(messages = updatedMessages)
                        }
                        textToSpeech.speak(localResult)
                    }
                }
            }

            is WsMessage.Plan -> {
                Log.i(TAG, "Task graph plan received: goal=${message.goal}, stepsCount=${message.stepsCount}")
            }

            is WsMessage.ToolRequest -> {
                Log.i(TAG, "ToolRequest received: req_id=${message.requestId}, action=${message.action}")
                viewModelScope.launch {
                    val executor = JarvisCommandExecutor(context)
                    val result = executor.executeToolAction(
                        action = message.action,
                        packageName = message.packageName,
                        appName = message.appName,
                        target = message.target
                    )
                    
                    // Report structured result back to backend
                    webSocketClient?.sendToolResult(
                        requestId = message.requestId,
                        success = result.success,
                        action = result.action,
                        message = result.message,
                        executionTimeMs = result.executionTimeMs
                    )

                    mutex.withLock {
                        val currentState = _uiState.value
                        val updatedMessages = currentState.messages + ChatMessage(
                            role = MessageRole.ASSISTANT,
                            text = result.message,
                            isStreaming = false
                        )
                        _uiState.value = currentState.copy(messages = updatedMessages)
                    }
                    textToSpeech.speak(result.message)
                }
            }

            is WsMessage.TaskProgress -> {
                Log.d(TAG, "TaskProgress: ${message.taskName} - ${message.status} (${message.detail}) agent=${message.agent} tool=${message.tool}")
            }
            
            is WsMessage.Heartbeat -> {
                Log.d(TAG, "Heartbeat received")
            }
            
            is WsMessage.Error -> {
                Log.e(TAG, "Error from server: ${message.message}")
                viewModelScope.launch {
                    mutex.withLock {
                        responseTimeoutJob?.cancel()
                        responseTimeoutJob = null
                        val currentState = _uiState.value
                        
                        // Add error message
                        val updatedMessages = currentState.messages + ChatMessage(
                            role = MessageRole.ASSISTANT,
                            text = "Error: ${message.message}",
                            isStreaming = false
                        )
                        
                        _uiState.value = currentState.copy(
                            currentStreamingText = "",
                            messages = updatedMessages,
                            isTyping = false
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Finalize a streaming message.
     */
    private fun finalizeStreamingMessage() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        
        viewModelScope.launch {
            var finalText = ""
            mutex.withLock {
                val currentState = _uiState.value
                
                // Find the streaming message text
                val streamingMessage = currentState.messages.find { it.role == MessageRole.ASSISTANT && it.isStreaming }
                if (streamingMessage != null) {
                    finalText = streamingMessage.text
                }
                
                // Update the last message to mark as not streaming
                val updatedMessages = currentState.messages.map { msg ->
                    if (msg.role == MessageRole.ASSISTANT && msg.isStreaming) {
                        msg.copy(isStreaming = false)
                    } else {
                        msg
                    }
                }
                
                _uiState.value = currentState.copy(
                    currentStreamingText = "",
                    messages = updatedMessages,
                    isTyping = false
                )
                Log.i(TAG, "event=\"AI Response Received\" type=\"stream_complete\" textLength=\"${finalText.length}\"")
                Log.i(TAG, "event=\"Compose State Updated\" isTyping=\"false\"")
            }
            if (finalText.isNotEmpty()) {
                // Play TTS feedback for streaming response when it is done
                textToSpeech.speak(finalText)
            }
        }
    }
    
    /**
     * Check if the overlay is visible.
     */
    fun isOverlayVisible(): Boolean = _uiState.value.isOverlayVisible
    
    /**
     * Check if currently listening.
     */
    fun isListening(): Boolean = _uiState.value.isListening
    
    /**
     * Check if connected to WebSocket.
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() == true
    
    /**
     * Get current messages.
     */
    fun getMessages(): List<ChatMessage> = _uiState.value.messages
    
    /**
     * Get current streaming text.
     */
    fun getCurrentStreamingText(): String = _uiState.value.currentStreamingText
    
    /**
     * Check if currently typing (showing typing indicator).
     */
    fun isTyping(): Boolean = _uiState.value.isTyping
    
    /**
     * Clean up and release voice/speech engines.
     * Called when overlay is dismissed.
     */
    fun cleanup() {
        Log.i(TAG, "VM_CLEANUP_COMPLETE")
        
        // Stop voice input
        stopVoiceInput()
        
        // Stop TTS
        try {
            textToSpeech.stop()
            Log.i(TAG, "TTS stopped during cleanup")
        } catch (e: Exception) {
            Log.e(TAG, "TTS_STOP_FAILED during cleanup: ${e.message}")
        }
        
        disconnectWebSocket()
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
    
    /**
     * Handle state machine errors by forcing reset.
     */
    private fun handleStateError(error: String) {
        Log.e(TAG, "State machine error: $error")
        viewModelScope.launch {
            mutex.withLock {
                _uiState.value = _uiState.value.copy(
                    isListening = false
                )
            }
        }
        isVoiceInputActive.set(false)
        // Force reset is handled by the caller (VoiceInputManager)
    }
}
