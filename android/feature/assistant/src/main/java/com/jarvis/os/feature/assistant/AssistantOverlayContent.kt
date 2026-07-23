package com.jarvis.os.feature.assistant

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.core.ui.theme.*

/**
 * State-of-the-Art Compact Consumer AI Assistant Overlay for JARVIS OS.
 * Features dynamic spring height morphing, organic neural AI orb visualizer,
 * edge-to-edge WindowInsets gesture support, and minimal Gemini/Siri-style floating bottom sheet.
 */
@Composable
fun AssistantOverlayContent(
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Sync voice transcript with input field when listening
    LaunchedEffect(uiState.voiceTranscript, uiState.isListening) {
        if (uiState.isListening) {
            textInput = uiState.voiceTranscript
        } else if (uiState.voiceTranscript.isNotEmpty() && textInput == uiState.voiceTranscript) {
            textInput = ""
        }
    }

    // Animated entrance state for bottom sheet
    var isSheetVisible by remember { mutableStateOf(true) }

    val handleDismiss = {
        isSheetVisible = false
        onDismiss()
    }

    // Quick Command Action Chips
    val quickActions = remember {
        listOf(
            "💬 Who are you?",
            "📱 Open Instagram",
            "📱 Open WhatsApp",
            "📸 Take a Photo",
            "⏰ Set Alarm for 6 AM",
            "🗺️ Navigate Home"
        )
    }

    // Compact floating assistant sheet container
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isSheetVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 200)
            ) + fadeOut(animationSpec = tween(durationMillis = 200))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioLowBouncy
                        )
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0F172A).copy(alpha = 0.94f),
                                Color(0xFF070A14).copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                NeonCyan.copy(alpha = 0.50f),
                                Color(0xFF38BDF8).copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 1. Minimal Drag Handle & Header ──
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = if (uiState.isConnected) Color(0xFF00E676) else Color(0xFFFF1744)
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "JARVIS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.95f),
                            letterSpacing = 1.5.sp
                        )
                    }

                    IconButton(
                        onClick = handleDismiss,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // ── 2. Dynamic Center Neural Orb Visualizer & Status ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    OrganicNeuralOrb(
                        isListening = uiState.isListening,
                        isTyping = uiState.isTyping,
                        size = 42
                    )
                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        val statusText = when {
                            uiState.isListening -> "Listening..."
                            uiState.isTyping -> "Thinking..."
                            else -> "Hello Boss Varsshan, what help do you want sir?"
                        }

                        Text(
                            text = statusText,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.92f),
                            fontWeight = FontWeight.Medium
                        )

                        if (uiState.voiceTranscript.isNotBlank() && uiState.isListening) {
                            Text(
                                text = "\"${uiState.voiceTranscript}\"",
                                fontSize = 12.sp,
                                color = NeonCyan,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }

                    if (uiState.isListening || uiState.isTyping) {
                        VoiceWaveformVisualizer(isActive = true)
                    }
                }

                // ── 3. Quick Command Action Chips (Only when messages empty) ──
                if (uiState.messages.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickActions.forEach { action ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(GlassDark)
                                    .border(0.8.dp, NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        val cleanCmd = action.substringAfter(" ").trim()
                                        viewModel.sendText(cleanCmd)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = action,
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // ── 4. Chat Messages List (Expanded when conversation exists) ──
                if (uiState.messages.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            ChatBubble(message)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ── 5. Glassmorphic Compact Input Bar ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PulsingMicButton(
                        isListening = uiState.isListening,
                        onClick = {
                            if (uiState.isListening) viewModel.stopVoiceInput()
                            else viewModel.startVoiceInput()
                        }
                    )

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        placeholder = {
                            Text("Ask JARVIS...", color = TextMuted, fontSize = 13.sp)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = GlassDark,
                            unfocusedContainerColor = GlassDark,
                            focusedBorderColor = NeonCyan.copy(alpha = 0.6f),
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(23.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendText(textInput.trim())
                                textInput = ""
                            }
                        })
                    )

                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendText(textInput.trim())
                                textInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(NeonCyan, ElectricViolet))
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Organic Rotating Neural AI Orb ──
@Composable
private fun OrganicNeuralOrb(
    isListening: Boolean,
    isTyping: Boolean,
    size: Int = 42
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.96f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseScale"
    )

    val color1 = if (isListening) NeonCyan else if (isTyping) Color(0xFFFFD600) else Color(0xFF38BDF8)
    val color2 = if (isListening) ElectricViolet else if (isTyping) Color(0xFFFF3D00) else Color(0xFF0284C7)

    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(if (isListening || isTyping) scaleAnim else 1.0f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            rotate(rotationAngle) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(color1, color2, color1)
                    )
                )
            }
        }
        Box(
            modifier = Modifier
                .size((size * 0.65).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.9f), color1)
                    )
                )
        )
    }
}

// ── Voice Waveform Visualizer ──
@Composable
private fun VoiceWaveformVisualizer(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val heightFraction by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(300 + i * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 70)
                ), label = "wave$i"
            )
            val h = if (isActive) (14 * heightFraction).dp else 4.dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) NeonCyan else TextMuted.copy(alpha = 0.3f))
            )
        }
    }
}

// ── Chat Bubble ──
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val shape = if (isUser) {
            RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
        } else {
            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .then(
                    if (isUser) Modifier.background(
                        Brush.horizontalGradient(listOf(ElectricViolet, NeonCyan.copy(alpha = 0.85f)))
                    ) else Modifier
                        .background(Color(0xFF161B26))
                        .border(0.8.dp, GlassBorder, shape)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.text,
                color = if (isUser) Color.White else TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// ── Pulsing Mic Button ──
@Composable
private fun PulsingMicButton(isListening: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "pulse"
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .scale(if (isListening) pulseScale else 1f)
            .clip(CircleShape)
            .background(
                if (isListening) Brush.radialGradient(listOf(NeonCyan, Color(0xFF0284C7)))
                else Brush.linearGradient(listOf(GlassDark, GlassDark))
            )
            .border(
                if (isListening) 1.5.dp else 1.dp,
                if (isListening) NeonCyan else GlassBorder,
                CircleShape
            )
    ) {
        Icon(
            if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (isListening) "Stop listening" else "Start listening",
            tint = if (isListening) Color.White else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
