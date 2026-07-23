package com.jarvis.os.feature.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.jarvis.os.core.background.JarvisAccessibilityService

/**
 * Structured tool execution result returned by Android device controller.
 */
data class ToolExecutionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val executionTimeMs: Long = 0,
    val data: Map<String, Any> = emptyMap()
)

/**
 * Executes local Android device actions, intent launches, app control, and system settings
 * for JARVIS agent commands.
 */
class JarvisCommandExecutor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisCmdExec"

        fun executeCommand(
            context: Context,
            action: String,
            packageName: String = "",
            appName: String = "",
            target: String = ""
        ): String? {
            val executor = JarvisCommandExecutor(context)
            val result = executor.executeToolAction(action, packageName, appName, target)
            return result.message
        }

        fun parseAlarmTime(text: String): Pair<Int, Int> {
            val lower = text.lowercase()
            
            val timeMatch = Regex("""(\d{1,2}):(\d{2})""").find(lower)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val isPm = lower.contains("p.m") || lower.contains("pm")
                val isAm = lower.contains("a.m") || lower.contains("am")
                
                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0
                
                return Pair(hour, minute)
            }

            val hourOnlyMatch = Regex("""(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)""").find(lower)
            if (hourOnlyMatch != null) {
                var hour = hourOnlyMatch.groupValues[1].toIntOrNull() ?: 7
                val period = hourOnlyMatch.groupValues[2]
                val isPm = period.startsWith("p")
                val isAm = period.startsWith("a")

                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0

                return Pair(hour, 0)
            }

            return Pair(7, 0)
        }

        fun formatTimeDisplay(hour: Int, minute: Int): String {
            val period = if (hour >= 12) "PM" else "AM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val displayMin = String.format("%02d", minute)
            return "$displayHour:$displayMin $period"
        }
    }

    fun executeToolAction(
        action: String,
        packageName: String = "",
        appName: String = "",
        target: String = ""
    ): ToolExecutionResult {
        val startTime = System.currentTimeMillis()
        return when (action) {
            "LAUNCH_APP" -> {
                val msg = launchAppOrWeb(packageName, "https://google.com", "Opening $appName...")
                ToolExecutionResult(
                    success = !msg.startsWith("Unable"),
                    action = action,
                    message = msg,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    data = mapOf("packageName" to packageName, "appName" to appName)
                )
            }
            "CALL_PHONE" -> {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}"))
                val msg = launchIntent(intent, "Calling $target...")
                ToolExecutionResult(
                    success = !msg.startsWith("Permission") && !msg.startsWith("Action unavailable"),
                    action = action,
                    message = msg,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    data = mapOf("target" to target)
                )
            }
            "SEND_SMS" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${Uri.encode(target)}")).apply {
                    putExtra("sms_body", appName)
                }
                val msg = launchIntent(intent, "Opening SMS to $target...")
                ToolExecutionResult(
                    success = !msg.startsWith("Permission"),
                    action = action,
                    message = msg,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    data = mapOf("target" to target)
                )
            }
            "SET_ALARM" -> {
                val (hour, minute) = parseAlarmTime(target)
                val formattedTime = formatTimeDisplay(hour, minute)
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                val msg = launchIntent(intent, "Setting alarm for $formattedTime...")
                ToolExecutionResult(
                    success = !msg.startsWith("Permission"),
                    action = action,
                    message = msg,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    data = mapOf("hour" to hour, "minute" to minute, "formattedTime" to formattedTime)
                )
            }
            "LAUNCH_MAPS" -> {
                val msg = openWebUrl("https://www.google.com/maps", "Opening Maps...")
                ToolExecutionResult(
                    success = true,
                    action = action,
                    message = msg,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
            "OPEN_BLUETOOTH_SETTINGS" -> {
                val msg = launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings...")
                ToolExecutionResult(success = true, action = action, message = msg, executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "OPEN_WIFI_SETTINGS" -> {
                val msg = launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings...")
                ToolExecutionResult(success = true, action = action, message = msg, executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "CLICK_TEXT" -> {
                val success = JarvisAccessibilityService.clickText(target)
                ToolExecutionResult(success = success, action = action, message = if (success) "Clicked text '$target'" else "Could not find text '$target'", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "TYPE_TEXT" -> {
                val success = JarvisAccessibilityService.typeText(target, appName)
                ToolExecutionResult(success = success, action = action, message = if (success) "Typed '$appName' into '$target'" else "Field '$target' not found", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "SCROLL" -> {
                val success = JarvisAccessibilityService.scroll(target)
                ToolExecutionResult(success = success, action = action, message = "Scrolled $target", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "WAIT_PACKAGE" -> {
                val success = JarvisAccessibilityService.waitForPackage(packageName)
                ToolExecutionResult(success = success, action = action, message = if (success) "Verified package $packageName" else "Package $packageName timeout", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "WAIT_TEXT" -> {
                val success = JarvisAccessibilityService.waitForText(target)
                ToolExecutionResult(success = success, action = action, message = if (success) "Verified text '$target'" else "Text '$target' timeout", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "PRESS_BACK" -> {
                val success = JarvisAccessibilityService.pressBack()
                ToolExecutionResult(success = success, action = action, message = "Pressed Back", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "PRESS_HOME" -> {
                val success = JarvisAccessibilityService.pressHome()
                ToolExecutionResult(success = success, action = action, message = "Pressed Home", executionTimeMs = System.currentTimeMillis() - startTime)
            }
            "CAPTURE_TREE" -> {
                val treeJson = JarvisAccessibilityService.captureAccessibilityTree()
                ToolExecutionResult(success = true, action = action, message = "Captured UI tree", executionTimeMs = System.currentTimeMillis() - startTime, data = mapOf("tree" to treeJson))
            }
            else -> {
                val msg = tryExecuteLocalCommand(action) ?: "Tool $action executed"
                ToolExecutionResult(success = true, action = action, message = msg, executionTimeMs = System.currentTimeMillis() - startTime)
            }
        }
    }

    fun handleSetAlarm(text: String): String {
        val (hour, minute) = parseAlarmTime(text)
        val formattedTime = formatTimeDisplay(hour, minute)
        
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launchIntent(intent, "Setting alarm for $formattedTime...")
    }

    fun tryExecuteLocalCommand(text: String): String? {
        val lower = text.lowercase().trim()

        return when {
            lower.contains("open instagram") || lower.contains("launch instagram") -> {
                launchAppOrWeb("com.instagram.android", "https://instagram.com", "Opening Instagram...")
            }
            lower.contains("open whatsapp") || lower.contains("launch whatsapp") -> {
                launchAppOrWeb("com.whatsapp", "https://web.whatsapp.com", "Opening WhatsApp...")
            }
            lower.contains("open youtube") || lower.contains("launch youtube") -> {
                launchAppOrWeb("com.google.android.youtube", "https://youtube.com", "Opening YouTube...")
            }
            lower.contains("open camera") || lower.contains("launch camera") || lower.contains("take photo") -> {
                launchIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opening Camera...")
            }
            lower.contains("open settings") -> {
                launchIntent(Intent(Settings.ACTION_SETTINGS), "Opening System Settings...")
            }
            lower.contains("go to home screen") || lower.contains("go home") -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                launchIntent(intent, "Navigating to Home Screen...")
            }
            lower.startsWith("call ") -> {
                val target = lower.removePrefix("call ").trim()
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${Uri.encode(target)}")
                }
                launchIntent(intent, "Initiating call to $target...")
            }
            lower.contains("send whatsapp message") -> {
                launchAppOrWeb("com.whatsapp", "https://web.whatsapp.com", "Opening WhatsApp composer...")
            }
            lower.contains("send sms") || lower.contains("send an sms") -> {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:")
                }
                launchIntent(intent, "Opening SMS app...")
            }
            lower.startsWith("search google for ") -> {
                val query = text.substringAfter("search google for ", "").trim()
                openWebUrl("https://www.google.com/search?q=${Uri.encode(query)}", "Searching Google for '$query'...")
            }
            lower.startsWith("search youtube for ") -> {
                val query = text.substringAfter("search youtube for ", "").trim()
                openWebUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}", "Searching YouTube for '$query'...")
            }
            lower.contains("open chatgpt") -> {
                openWebUrl("https://chat.openai.com", "Opening ChatGPT...")
            }
            lower.contains("increase volume") || lower.contains("volume up") -> {
                adjustVolume(AudioManager.ADJUST_RAISE, "Increasing media volume")
            }
            lower.contains("reduce volume") || lower.contains("volume down") || lower.contains("mute my phone") -> {
                adjustVolume(AudioManager.ADJUST_LOWER, "Reducing media volume")
            }
            lower.contains("bluetooth") -> {
                launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings...")
            }
            lower.contains("wi-fi") || lower.contains("wifi") -> {
                launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings...")
            }
            lower.contains("set an alarm") || lower.contains("set alarm") -> {
                handleSetAlarm(text)
            }
            lower.startsWith("navigate to ") || lower.contains("find nearby") || lower.contains("locate the nearest") -> {
                val destination = text.replace("navigate to", "").replace("find nearby", "").replace("locate the nearest", "").trim()
                openWebUrl("https://www.google.com/maps/search/${Uri.encode(destination)}", "Opening Maps navigation for '$destination'...")
            }
            else -> null
        }
    }

    private fun launchAppOrWeb(packageName: String, webFallback: String, message: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                message
            } else {
                openWebUrl(webFallback, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package $packageName", e)
            openWebUrl(webFallback, message)
        }
    }

    private fun openWebUrl(url: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL $url", e)
            "Unable to open link: ${e.localizedMessage}"
        }
    }

    private fun launchIntent(intent: Intent, message: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            message
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for intent $intent", e)
            "Permission Required: Please grant Alarm & System permissions to JARVIS OS in Settings."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch intent $intent", e)
            "Action unavailable on this device: ${e.localizedMessage}"
        }
    }

    private fun adjustVolume(direction: Int, message: String): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
            "Volume adjustment unavailable"
        }
    }
}
