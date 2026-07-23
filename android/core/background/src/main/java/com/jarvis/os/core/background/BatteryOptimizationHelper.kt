package com.jarvis.os.core.background

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Helper class for battery optimization management.
 *
 * This class provides utilities to:
 * - Check if battery optimizations are ignored
 * - Request battery optimization exemption (opens system dialog - no auto-grant)
 * - Detect Samsung "Auto-launch" / "Background restrictions" eligibility
 * - Provide Samsung-specific guidance constants
 *
 * Samsung-specific notes:
 * - Samsung's "Auto-launch" feature allows apps to start services in background
 * - Samsung's "Background restrictions" can block service starts
 * - Users must manually enable these settings for reliable wake word detection
 */
class BatteryOptimizationHelper {

    companion object {
        private const val TAG = "JarvisBattery"

        // Request code for battery optimization permission
        const val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001

        // Samsung package name
        private const val SAMSUNG_PACKAGE = "com.samsung.android.sm"

        // Samsung battery settings intents
        private const val SAMSUNG_SETTINGS_ACTION_AUTO_LAUNCH = "com.samsung.android.sm.battery.action.AUTO_LAUNCH"
        private const val SAMSUNG_SETTINGS_ACTION_BACKGROUND_RESTRICTIONS = "com.samsung.android.sm.battery.action.BG_RESTRICTIONS"

        // Samsung package name for background restrictions intent
        private const val SAMSUNG_PACKAGE_BG_RESTRICTIONS = "com.samsung.android.sm.battery"

        // ------------------------------------------------------------------
        //  Samsung-Specific Guidance Constants
        // ------------------------------------------------------------------

        /**
         * Samsung settings screen to enable auto-launch for the app.
         * Users must manually enable this for reliable wake word detection.
         */
        const val SAMSUNG_SETTINGS_AUTO_LAUNCH = "samsung_auto_launch"

        /**
         * Samsung settings screen to disable background restrictions.
         * Users must manually disable this for reliable wake word detection.
         */
        const val SAMSUNG_SETTINGS_BACKGROUND_RESTRICTIONS = "samsung_background_restrictions"

        // ------------------------------------------------------------------
        //  Public API
        // ------------------------------------------------------------------

        /**
         * Check if battery optimizations are currently ignored for this app.
         *
         * @param context Application context
         * @return true if battery optimizations are ignored, false otherwise
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                // On older versions, battery optimizations are not enforced
                Log.d(TAG, "Battery optimizations not enforced on Android < M")
                return true
            }

            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val result = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                Log.d(TAG, "isIgnoringBatteryOptimizations=$result for package=${context.packageName}")
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                return false
            }
        }

        /**
         * Request battery optimization exemption for the app.
         *
         * This method opens the battery optimization settings screen where the user
         * can manually grant the exemption. It does NOT auto-grant.
         *
         * @param context Application context
         * @return true if already ignoring battery optimizations, false otherwise
         */
        fun requestIgnoreBatteryOptimizationsIfNeeded(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.d(TAG, "Battery optimizations not enforced on Android < M")
                return true
            }

            val alreadyIgnoring = isIgnoringBatteryOptimizations(context)
            if (alreadyIgnoring) {
                Log.i(TAG, "BATTERY_OPT_REQUESTED granted=true (already ignoring)")
                return true
            }

            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                Log.i(TAG, "BATTERY_OPT_REQUESTED granted=false (opened system dialog)")
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start battery optimization request", e)
                return false
            }
        }

        /**
         * Check if the app is auto-launch eligible (Samsung-specific).
         *
         * Samsung's "Auto-launch" feature allows apps to start services in background.
         * This is a best-effort detection via ActivityManager.
         *
         * @param context Application context
         * @return true if app is auto-launch eligible, false otherwise
         */
        fun isAppAutoEligible(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // Background restrictions not enforced on Android < P
                Log.d(TAG, "Background restrictions not enforced on Android < P")
                return true
            }

            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val result = activityManager?.isBackgroundRestricted() ?: false
                Log.d(TAG, "isAppAutoEligible=$result (backgroundRestricted=$result)")
                return !result
            } catch (e: Exception) {
                Log.e(TAG, "Error checking auto-launch eligibility", e)
                return false
            }
        }

        /**
         * Open battery optimization settings screen.
         *
         * @param context Application context
         */
        fun openBatteryOptimizationSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Battery optimization settings opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization settings", e)
            }
        }

        /**
         * Open Samsung auto-launch settings for the app.
         *
         * @param context Application context
         */
        fun openSamsungAutoLaunchSettings(context: Context) {
            try {
                val intent = Intent(SAMSUNG_SETTINGS_ACTION_AUTO_LAUNCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("package_name", context.packageName)
                }
                context.startActivity(intent)
                Log.i(TAG, "Samsung auto-launch settings opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Samsung auto-launch settings", e)
            }
        }

        /**
         * Open Samsung background restrictions settings for the app.
         *
         * @param context Application context
         */
        fun openSamsungBackgroundRestrictionsSettings(context: Context) {
            try {
                val intent = Intent(SAMSUNG_SETTINGS_ACTION_BACKGROUND_RESTRICTIONS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("package_name", context.packageName)
                }
                context.startActivity(intent)
                Log.i(TAG, "Samsung background restrictions settings opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Samsung background restrictions settings", e)
            }
        }

        /**
         * Get battery optimization status message.
         *
         * @param context Application context
         * @return Human-readable status message
         */
        fun getBatteryOptimizationStatusMessage(context: Context): String {
            val isIgnoring = isIgnoringBatteryOptimizations(context)
            val autoEligible = isAppAutoEligible(context)
            return if (isIgnoring) {
                "Battery optimizations are ignored - wake word engine will run reliably"
            } else if (autoEligible) {
                "Battery optimizations are enabled - ensure Samsung auto-launch is enabled for reliable wake word detection"
            } else {
                "Battery optimizations are enabled - wake word engine may be throttled. Enable Samsung auto-launch and disable background restrictions."
            }
        }

        /**
         * Get Samsung-specific guidance for battery optimization.
         *
         * @param context Application context
         * @return Human-readable Samsung-specific guidance
         */
        fun getSamsungBatteryOptimizationGuidance(context: Context): String {
            val autoEligible = isAppAutoEligible(context)
            val settingsPath = if (autoEligible) {
                "Settings > Battery > More battery settings > Auto-launch applications > Your app"
            } else {
                "Settings > Battery > More battery settings > Background restrictions > Disable for your app"
            }
            return "For Samsung devices, enable:\n$settingsPath\n\nThis allows the wake word engine to run reliably in the background."
        }
    }
}
