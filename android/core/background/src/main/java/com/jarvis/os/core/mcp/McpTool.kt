package com.jarvis.os.core.mcp

import android.content.Context

/**
 * Interface contract for all Android MCP (Model Context Protocol) Tools.
 */
interface McpTool {
    val name: String
    val description: String

    suspend fun execute(
        context: Context,
        requestId: String,
        arguments: Map<String, Any>
    ): McpToolResult
}
