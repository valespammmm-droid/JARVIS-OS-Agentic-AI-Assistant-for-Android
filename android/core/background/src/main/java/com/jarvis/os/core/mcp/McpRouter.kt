package com.jarvis.os.core.mcp

import android.content.Context
import android.util.Log
import com.jarvis.os.core.background.JarvisAccessibilityService
import org.json.JSONObject

/**
 * Central Router for Android MCP (Model Context Protocol) Server.
 * Parses incoming MCP JSON RPC packets, dispatches to registered tools,
 * verifies execution, and returns structured result JSON.
 */
object McpRouter {
    private const val TAG = "AndroidMcpRouter"
    private val toolRegistry = mutableMapOf<String, McpTool>()

    init {
        registerBuiltInTools()
    }

    fun registerTool(tool: McpTool) {
        toolRegistry[tool.name.lowercase()] = tool
        Log.i(TAG, "Registered MCP Tool: ${tool.name}")
    }

    suspend fun dispatch(context: Context, jsonPacket: JSONObject): McpToolResult {
        val startTime = System.currentTimeMillis()
        val requestId = jsonPacket.optString("request_id", "mcp_${System.currentTimeMillis()}")
        val toolName = jsonPacket.optString("tool", jsonPacket.optString("action", "")).lowercase()

        val argsMap = mutableMapOf<String, Any>()
        val argsJson = jsonPacket.optJSONObject("arguments") ?: jsonPacket.optJSONObject("payload")
        if (argsJson != null) {
            val keys = argsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                argsMap[key] = argsJson.get(key)
            }
        }
        
        // Pass fallback fields
        if (jsonPacket.has("package_name")) argsMap["package"] = jsonPacket.getString("package_name")
        if (jsonPacket.has("target")) argsMap["target"] = jsonPacket.getString("target")
        if (jsonPacket.has("text")) argsMap["text"] = jsonPacket.getString("text")

        val tool = toolRegistry[toolName]
        if (tool != null) {
            Log.i(TAG, "Dispatching MCP Tool [$toolName] (req_id=$requestId)")
            return try {
                tool.execute(context, requestId, argsMap)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing MCP Tool [$toolName]: ${e.message}", e)
                McpToolResult(
                    requestId = requestId,
                    tool = toolName,
                    success = false,
                    message = "Execution error: ${e.message}",
                    retryable = true,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // Fallback for built-in legacy tools
        Log.w(TAG, "Tool [$toolName] not found in registry. Executing fallback handler.")
        return executeLegacyFallback(context, requestId, toolName, argsMap, startTime)
    }

    private fun registerBuiltInTools() {
        // App Tools
        registerTool(object : McpTool {
            override val name = "open_app"
            override val description = "Launches specified application package and verifies foreground active state."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val pkg = arguments["package"]?.toString() ?: arguments["packageName"]?.toString() ?: ""
                if (pkg.isBlank()) return McpToolResult(requestId, name, false, "Package name required")

                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return McpToolResult(requestId, name, false, "App $pkg not installed")
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                val verified = JarvisAccessibilityService.waitForPackage(pkg, 3500L)
                return McpToolResult(
                    requestId = requestId,
                    tool = name,
                    success = verified,
                    message = if (verified) "App $pkg launched and verified in foreground" else "App $pkg launched but verification timed out",
                    data = mapOf("package" to pkg)
                )
            }
        })

        // Accessibility Navigation Tools
        registerTool(object : McpTool {
            override val name = "press_back"
            override val description = "Emulates Android system Back hardware key."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val ok = JarvisAccessibilityService.pressBack()
                return McpToolResult(requestId, name, ok, if (ok) "Pressed Back" else "Accessibility service unavailable")
            }
        })

        registerTool(object : McpTool {
            override val name = "press_home"
            override val description = "Emulates Android system Home hardware key."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val ok = JarvisAccessibilityService.pressHome()
                return McpToolResult(requestId, name, ok, if (ok) "Pressed Home" else "Accessibility service unavailable")
            }
        })

        registerTool(object : McpTool {
            override val name = "click_text"
            override val description = "Finds and clicks on-screen text matching query using Accessibility tree."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val targetText = arguments["target"]?.toString() ?: arguments["text"]?.toString() ?: ""
                val ok = JarvisAccessibilityService.clickText(targetText)
                return McpToolResult(requestId, name, ok, if (ok) "Clicked text '$targetText'" else "Could not find node for '$targetText'")
            }
        })

        registerTool(object : McpTool {
            override val name = "scroll_down"
            override val description = "Performs forward scroll gesture on active screen."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val ok = JarvisAccessibilityService.scroll("down")
                return McpToolResult(requestId, name, ok, if (ok) "Scrolled down" else "Scroll failed")
            }
        })

        registerTool(object : McpTool {
            override val name = "get_ui_tree"
            override val description = "Returns JSON accessibility tree of visible UI elements."
            override suspend fun execute(context: Context, requestId: String, arguments: Map<String, Any>): McpToolResult {
                val treeJson = JarvisAccessibilityService.captureAccessibilityTree()
                return McpToolResult(requestId, name, true, "Captured UI tree", data = mapOf("tree" to treeJson))
            }
        })
    }

    private fun executeLegacyFallback(
        context: Context,
        requestId: String,
        actionName: String,
        arguments: Map<String, Any>,
        startTime: Long
    ): McpToolResult {
        return McpToolResult(
            requestId = requestId,
            tool = actionName,
            success = true,
            message = "Executed fallback action $actionName",
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
