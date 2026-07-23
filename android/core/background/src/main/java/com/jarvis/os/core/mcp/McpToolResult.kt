package com.jarvis.os.core.mcp

import org.json.JSONObject

/**
 * Standardized response contract returned by every Android MCP Tool execution.
 */
data class McpToolResult(
    val requestId: String,
    val tool: String,
    val success: Boolean,
    val message: String,
    val data: Map<String, Any> = emptyMap(),
    val retryable: Boolean = false,
    val executionTimeMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("request_id", requestId)
        json.put("tool", tool)
        json.put("success", success)
        json.put("message", message)
        json.put("retryable", retryable)
        json.put("execution_time_ms", executionTimeMs)

        val dataJson = JSONObject()
        for ((key, value) in data) {
            dataJson.put(key, value)
        }
        json.put("data", dataJson)
        return json
    }
}
