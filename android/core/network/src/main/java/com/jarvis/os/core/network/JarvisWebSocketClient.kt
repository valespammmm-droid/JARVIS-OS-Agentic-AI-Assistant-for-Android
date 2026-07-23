package com.jarvis.os.core.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ktor WebSocket client for JARVIS OS backend communication.
 * Robust, single-connection event client handling structured streaming and heartbeat.
 */
class JarvisWebSocketClient(
    private val context: CoroutineScope,
    private val serverUrl: String = NetworkConfig.getWebSocketUrl()
) {
    
    companion object {
        private const val TAG = "JarvisWebSocketClient"
        private const val HEARTBEAT_INTERVAL_MS = NetworkConfig.HEARTBEAT_INTERVAL_MS
        private const val MAX_RECONNECT_ATTEMPTS = NetworkConfig.MAX_RECONNECT_ATTEMPTS
    }
    
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
        install(DefaultRequest) {
            headers.append("Origin", "jarvis-os")
        }
    }
    
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val incomingMessagesFlow = MutableSharedFlow<WsMessage>(replay = 0, extraBufferCapacity = 64)
    private val outgoingMessageChannel = Channel<String>(Channel.UNLIMITED)
    private val reconnectAttempts = AtomicInteger(0)
    private var heartbeatJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null
    private var isConnecting = false
    private var isWebSocketActive = false
    private var isExplicitlyDisconnected = false
    
    fun incomingMessages(): Flow<WsMessage> = incomingMessagesFlow.asSharedFlow()
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED && isWebSocketActive
    fun isConnecting(): Boolean = isConnecting
    
    suspend fun connect() {
        if (isConnecting || isWebSocketActive) return
        
        isConnecting = true
        isExplicitlyDisconnected = false
        reconnectAttempts.set(0)
        
        context.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                Log.i(TAG, "Connecting to WebSocket at $serverUrl...")
                
                client.webSocket(serverUrl) {
                    activeSession = this
                    isWebSocketActive = true
                    isConnecting = false
                    reconnectAttempts.set(0)
                    _connectionState.value = ConnectionState.CONNECTED
                    Log.i(TAG, "WebSocket connected successfully.")
                    
                    startHeartbeat()
                    
                    val sendJob = launch {
                        for (message in outgoingMessageChannel) {
                            try {
                                send(Frame.Text(message))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending frame: ${e.message}")
                            }
                        }
                    }
                    
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleIncomingMessage(text)
                            }
                        }
                    } finally {
                        sendJob.cancel()
                        heartbeatJob?.cancel()
                        heartbeatJob = null
                        activeSession = null
                    }
                }
                
                isWebSocketActive = false
                _connectionState.value = ConnectionState.DISCONNECTED
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                isWebSocketActive = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            
            isConnecting = false
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = context.launch {
            while (isActive && isWebSocketActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }

    suspend fun disconnect() {
        isExplicitlyDisconnected = true
        heartbeatJob?.cancel()
        activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "explicit_disconnect"))
        activeSession = null
        isWebSocketActive = false
        isConnecting = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    suspend fun sendText(text: String, contextStateJson: JSONObject? = null) {
        if (!isWebSocketActive) return
        try {
            val payload = JSONObject().apply { put("text", text) }
            val envelope = JSONObject().apply {
                put("type", "message")
                put("payload", payload)
                if (contextStateJson != null) put("context_state", contextStateJson)
            }
            outgoingMessageChannel.trySend(envelope.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }

    suspend fun sendHeartbeat() {
        if (!isWebSocketActive) return
        try {
            val envelope = JSONObject().apply {
                put("type", "ping")
                put("payload", JSONObject())
            }
            outgoingMessageChannel.trySend(envelope.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat", e)
        }
    }

    suspend fun sendToolResult(
        requestId: String,
        success: Boolean,
        action: String,
        message: String,
        executionTimeMs: Long = 0
    ) {
        if (!isWebSocketActive) return
        try {
            val payload = JSONObject().apply {
                put("request_id", requestId)
                put("success", success)
                put("action", action)
                put("message", message)
                put("execution_time_ms", executionTimeMs)
            }
            val envelope = JSONObject().apply {
                put("type", "tool_result")
                put("payload", payload)
            }
            outgoingMessageChannel.trySend(envelope.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tool result", e)
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "").lowercase()
            val payload = json.optJSONObject("payload")
            
            val message: WsMessage? = when (type) {
                "connected" -> WsMessage.Connected(payload?.optString("text", "Connected") ?: "Connected")
                "stream_start" -> WsMessage.StreamStart(payload?.optString("text", "") ?: "")
                "stream_chunk", "token", "response_chunk" -> {
                    WsMessage.StreamChunk(
                        text = payload?.optString("text", "") ?: "",
                        done = payload?.optBoolean("done", false) ?: false
                    )
                }
                "stream_end", "completed" -> WsMessage.StreamEnd(payload?.optString("text", "") ?: "", done = true)
                "plan" -> {
                    val goal = payload?.optString("goal", "") ?: ""
                    val stepsArr = payload?.optJSONArray("steps")
                    WsMessage.Plan(goal = goal, stepsCount = stepsArr?.length() ?: 0)
                }
                "tool_request" -> {
                    WsMessage.ToolRequest(
                        requestId = payload?.optString("request_id", "") ?: "",
                        action = payload?.optString("action", "") ?: "",
                        packageName = payload?.optString("package_name", "") ?: "",
                        appName = payload?.optString("app_name", "") ?: "",
                        target = payload?.optString("target", "") ?: ""
                    )
                }
                "tool_call", "intent_detected" -> {
                    val intentObj = payload?.optJSONObject("intent") ?: payload
                    WsMessage.ToolCall(
                        action = intentObj?.optString("action", "") ?: "",
                        packageName = intentObj?.optString("package_name", "") ?: "",
                        appName = intentObj?.optString("app_name", "") ?: "",
                        target = intentObj?.optString("target", "") ?: ""
                    )
                }
                "task_start", "task_progress", "task_complete", "task_finish" -> {
                    WsMessage.TaskProgress(
                        taskName = payload?.optString("task_name", "") ?: type,
                        status = payload?.optString("status", "") ?: type,
                        detail = payload?.optString("detail", "") ?: "",
                        agent = payload?.optString("agent", "") ?: "",
                        tool = payload?.optString("tool", "") ?: ""
                    )
                }
                "pong", "heartbeat" -> WsMessage.Heartbeat
                "error" -> WsMessage.Error(payload?.optString("message", "Error") ?: "Error")
                else -> {
                    Log.d(TAG, "Ignored event type: $type")
                    null
                }
            }
            
            if (message != null) {
                incomingMessagesFlow.tryEmit(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming WS JSON: $text", e)
        }
    }

    fun close() {
        context.launch { disconnect() }
    }
}
