package com.jarvis.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.os.core.background.BatteryOptimizationHelper
import com.jarvis.os.core.background.WakeWordForegroundService
import com.jarvis.os.core.ui.theme.*
import com.jarvis.os.feature.assistant.AssistantOverlayService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
            startWakeWordService()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                if (!AssistantOverlayService.isRunning) {
                    toggleAssistantOverlayService()
                }
            }
        }
        
        // After runtime permissions, guide user to special permissions if needed
        checkAndRequestSpecialPermissionsSequentially()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Auto-start services if core mic permission is ready
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWakeWordService()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                if (!AssistantOverlayService.isRunning) {
                    toggleAssistantOverlayService()
                }
            }
        } else {
            requestAllPermissions()
        }

        setContent {
            JarvisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBlack
                ) {
                    DashboardScreen(
                        context = this,
                        onGrantAllPermissions = { requestAllPermissions() },
                        onOverlayRequest = { requestOverlayPermission() },
                        onBatteryExemptionRequest = { requestBatteryOptimizationExemption() },
                        onAccessibilityRequest = { requestAccessibilityPermission() },
                        onNotificationListenerRequest = { requestNotificationListenerPermission() },
                        onWriteSettingsRequest = { requestWriteSettingsPermission() },
                        onStartService = { startWakeWordService() },
                        onStopService = { stopWakeWordService() },
                        onOverlayServiceToggle = { toggleAssistantOverlayService() }
                    )
                }
            }
        }
    }

    /**
     * Master function to request ALL runtime and special system permissions.
     */
    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkAndRequestSpecialPermissionsSequentially() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            requestBatteryOptimizationExemption()
            return
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizationsIfNeeded(this)
        }
    }

    private fun requestAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable JARVIS in Installed Accessibility Services", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open Accessibility Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationListenerPermission() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable Notification Access for JARVIS OS", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open Notification Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
                Toast.makeText(this, "Enable Write System Settings for JARVIS OS", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open System Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopWakeWordService() {
        val intent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_STOP
        }
        stopService(intent)
        Toast.makeText(this, "Wake Word Service Stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleAssistantOverlayService() {
        val intent = Intent(this, AssistantOverlayService::class.java)
        if (AssistantOverlayService.isRunning) {
            stopService(intent)
            Toast.makeText(this, "Assistant Overlay Stopped", Toast.LENGTH_SHORT).show()
        } else {
            startService(intent)
            Toast.makeText(this, "Assistant Overlay Started", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun DashboardScreen(
    context: Context,
    onGrantAllPermissions: () -> Unit,
    onOverlayRequest: () -> Unit,
    onBatteryExemptionRequest: () -> Unit,
    onAccessibilityRequest: () -> Unit,
    onNotificationListenerRequest: () -> Unit,
    onWriteSettingsRequest: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOverlayServiceToggle: () -> Unit
) {
    var hasMicPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasPhonePermission by remember { mutableStateOf(false) }
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isBatteryExempt by remember { mutableStateOf(false) }

    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isNotificationListenerEnabled by remember { mutableStateOf(false) }
    var canWriteSettings by remember { mutableStateOf(false) }

    // Periodically update permission statuses when resumed
    LaunchedEffect(Unit) {
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        hasPhonePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        isAccessibilityEnabled = com.jarvis.os.core.background.JarvisAccessibilityService.isRunning
        isNotificationListenerEnabled = com.jarvis.os.core.background.JarvisNotificationListenerService.isRunning
        canWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepSpace, DarkPurple, ObsidianBlack)
                )
            )
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Title Header
        Text(
            text = "JARVIS OS",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            textAlign = TextAlign.Center,
            letterSpacing = 2.sp
        )

        Text(
            text = "AGENTIC AI OPERATING SYSTEM",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            letterSpacing = 3.sp
        )

        // 🚀 MASTER HERO BUTTON: GRANT ALL PERMISSIONS
        Button(
            onClick = onGrantAllPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(listOf(NeonCyan, ElectricViolet))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚡ GRANT ALL PERMISSIONS",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }

        // Glassmorphic status card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GlassDark)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "System & Agent Permissions",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                HorizontalDivider(color = GlassBorder)

                PermissionStatusRow(
                    name = "🎤 Microphone",
                    desc = "Voice recognition & 'Jarvis' wake-word",
                    granted = hasMicPermission,
                    onRequest = onGrantAllPermissions
                )

                PermissionStatusRow(
                    name = "🖼️ Display Over Apps",
                    desc = "Google Assistant-style overlay window",
                    granted = hasOverlayPermission,
                    onRequest = onOverlayRequest
                )

                PermissionStatusRow(
                    name = "📞 Phone & Contacts",
                    desc = "Direct voice calling & contact lookup",
                    granted = hasPhonePermission,
                    onRequest = onGrantAllPermissions
                )

                PermissionStatusRow(
                    name = "💬 SMS Messaging",
                    desc = "Voice text messaging & auto reply",
                    granted = hasSmsPermission,
                    onRequest = onGrantAllPermissions
                )

                PermissionStatusRow(
                    name = "📸 Camera Access",
                    desc = "Hands-free selfies & photo capture",
                    granted = hasCameraPermission,
                    onRequest = onGrantAllPermissions
                )

                PermissionStatusRow(
                    name = "⚡ Battery Exemption",
                    desc = "Prevents Android from killing 24/7 service",
                    granted = isBatteryExempt,
                    onRequest = onBatteryExemptionRequest
                )

                PermissionStatusRow(
                    name = "🤖 Accessibility Service",
                    desc = "Agentic UI automation & screen control",
                    granted = isAccessibilityEnabled,
                    onRequest = onAccessibilityRequest
                )

                PermissionStatusRow(
                    name = "🔔 Notification Access",
                    desc = "Read notifications & WhatsApp messages",
                    granted = isNotificationListenerEnabled,
                    onRequest = onNotificationListenerRequest
                )

                PermissionStatusRow(
                    name = "⚙️ System Write Settings",
                    desc = "Adjust brightness & sound volume levels",
                    granted = canWriteSettings,
                    onRequest = onWriteSettingsRequest
                )
            }
        }

        // Foreground Service Control Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GlassDark)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Engine Core State",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                val isServiceRunning = remember { mutableStateOf(WakeWordForegroundService.isRunning) }

                Button(
                    onClick = {
                        if (isServiceRunning.value) {
                            onStopService()
                            isServiceRunning.value = false
                        } else {
                            onStartService()
                            isServiceRunning.value = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isServiceRunning.value) "Stop Wake Word Engine" else "Start Wake Word Engine",
                        color = Color.White
                    )
                }
            }
        }

        // Assistant Overlay Service Control Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GlassDark)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Assistant Overlay Window",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                val isOverlayServiceRunning = remember { mutableStateOf(AssistantOverlayService.isRunning) }

                Button(
                    onClick = { onOverlayServiceToggle() },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isOverlayServiceRunning.value) "Stop Assistant Overlay" else "Start Assistant Overlay",
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun PermissionStatusRow(
    name: String,
    desc: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(text = desc, fontSize = 10.sp, color = TextMuted)
        }

        if (granted) {
            Text(
                text = "ACTIVE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E676),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = GlassWhite),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(text = "GRANT", fontSize = 10.sp, color = TextPrimary)
            }
        }
    }
}
