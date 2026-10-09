package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.encoder.EncoderDetector
import com.example.model.AspectRatioMode
import com.example.model.AudioInputDevice
import com.example.model.AudioSourceType
import com.example.model.BitrateModeType
import com.example.model.HardwareCapability
import com.example.model.ScreenOrientationMode
import com.example.model.StreamConfig
import com.example.model.StreamingProtocol
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import com.example.service.VeloStreamAccessibilityService
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMatrix
import com.example.ui.theme.NeonCrimson

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: StreamConfig,
    supportEval: EncoderDetector.SupportEvaluation,
    hardwareCaps: List<HardwareCapability>,
    isTestingConnection: Boolean,
    testResult: String?,
    onUpdateConfig: (StreamConfig) -> Unit,
    onTestConnection: () -> Unit,
    onClearTestResult: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var isKeyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // --- SECTION 1: STREAMING DESTINATION ---
        SectionHeader(icon = Icons.Default.Tv, title = "Broadcast Destination & Protocol")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Destination & Protocol Preset
                Text("Destination & Protocol Preset", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isYtHls = config.streamingProtocol == StreamingProtocol.YOUTUBE_HLS
                    val isYtRtmp = config.streamingProtocol == StreamingProtocol.RTMP && config.rtmpUrl.contains("youtube.com") && !config.rtmpUrl.startsWith("rtmps://")
                    val isYtRtmps = config.streamingProtocol == StreamingProtocol.RTMP && config.rtmpUrl.startsWith("rtmps://a.rtmps.youtube.com")
                    val isTwitch = config.streamingProtocol == StreamingProtocol.RTMP && config.rtmpUrl.contains("twitch.tv")

                    PresetButton(
                        label = "YouTube RTMP",
                        selected = isYtRtmp,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(
                            config.copy(
                                streamingProtocol = StreamingProtocol.RTMP,
                                rtmpUrl = "rtmp://a.rtmp.youtube.com/live2"
                            )
                        )
                    }
                    PresetButton(
                        label = "YouTube RTMPS",
                        selected = isYtRtmps,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(
                            config.copy(
                                streamingProtocol = StreamingProtocol.RTMP,
                                rtmpUrl = "rtmps://a.rtmps.youtube.com/live2"
                            )
                        )
                    }
                    PresetButton(
                        label = "YouTube HLS (HTTPS)",
                        selected = isYtHls,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        onUpdateConfig(
                            config.copy(
                                streamingProtocol = StreamingProtocol.YOUTUBE_HLS,
                                hlsStreamUrl = "https://a.upload.youtube.com/http_upload_hls?cid=",
                                videoCodec = VideoCodec.H265
                            )
                        )
                    }
                    PresetButton(
                        label = "Twitch",
                        selected = isTwitch,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(
                            config.copy(
                                streamingProtocol = StreamingProtocol.RTMP,
                                rtmpUrl = "rtmp://live.twitch.tv/app",
                                videoCodec = VideoCodec.H264
                            )
                        )
                    }
                }

                // Streaming Protocol selector
                Text("Streaming Ingestion Protocol", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetButton(
                        label = "RTMP / RTMPS",
                        selected = config.streamingProtocol == StreamingProtocol.RTMP,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(streamingProtocol = StreamingProtocol.RTMP))
                    }
                    PresetButton(
                        label = "YouTube HLS (HTTPS)",
                        selected = config.streamingProtocol == StreamingProtocol.YOUTUBE_HLS,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(streamingProtocol = StreamingProtocol.YOUTUBE_HLS))
                    }
                }

                if (config.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ElectricCyan.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "YouTube Live HLS active: Transmits MPEG-TS segments directly over HTTPS. Supports HEVC (H.265) & 4K streaming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }

                    OutlinedTextField(
                        value = config.hlsStreamUrl,
                        onValueChange = { onUpdateConfig(config.copy(hlsStreamUrl = it)) },
                        label = { Text("YouTube HLS Ingestion URL") },
                        placeholder = { Text("https://a.upload.youtube.com/http_upload_hls?cid=") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hls_url_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                } else {
                    OutlinedTextField(
                        value = config.rtmpUrl,
                        onValueChange = { onUpdateConfig(config.copy(rtmpUrl = it)) },
                        label = { Text("RTMP Server URL") },
                        placeholder = { Text("rtmp://a.rtmp.youtube.com/live2") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rtmp_url_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                }

                OutlinedTextField(
                    value = config.streamKey,
                    onValueChange = { onUpdateConfig(config.copy(streamKey = it)) },
                    label = { Text("Stream Key") },
                    placeholder = { Text("••••-••••-••••-••••") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_key_input"),
                    singleLine = true,
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CardBorder
                    )
                )

                // Test Connection Button & Result
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = !isTestingConnection && config.rtmpUrl.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("test_connection_button")
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Testing…")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Connection")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-Reconnect", style = MaterialTheme.typography.bodySmall, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = config.autoReconnect,
                            onCheckedChange = { onUpdateConfig(config.copy(autoReconnect = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                        )
                    }
                }

                if (testResult != null) {
                    val isSuccess = testResult.contains("successfully", ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (isSuccess) Color(0xFF0D3325) else Color(0xFF3B151E)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = testResult,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) EmeraldMatrix else Color(0xFFFF6B7A)
                        )
                    }
                }
            }
        }

        // --- SECTION 2: VIDEO QUALITY & ASPECT RATIO FIXER ---
        SectionHeader(icon = Icons.Default.AspectRatio, title = "Video Quality & Aspect Ratio")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Resolution Selector
                Text("Video Resolution", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoResolution.entries.forEach { res ->
                        FilterChip(
                            selected = config.resolution == res,
                            onClick = { onUpdateConfig(config.copy(resolution = res)) },
                            label = { Text(res.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan,
                                selectedLabelColor = Color(0xFF00363D)
                            )
                        )
                    }
                }

                // ASPECT RATIO FIXER (VERY IMPORTANT: tablets 3:2 / 4:3 / 7:5 -> 16:9 stretch option without black bars or crop)
                Text(
                    text = "Aspect Ratio Fixer (Tablet / Foldable / Ultrawide)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )

                AspectRatioMode.entries.forEach { mode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateConfig(config.copy(aspectRatioMode = mode)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (config.aspectRatioMode == mode) DarkSurfaceVariant else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (config.aspectRatioMode == mode) ElectricCyan else CardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (config.aspectRatioMode == mode) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = ElectricCyan)
                            }
                        }
                    }
                }

                // Orientation
                Text("Screen Orientation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScreenOrientationMode.entries.forEach { orient ->
                        PresetButton(
                            label = orient.label.split(" ").first(),
                            selected = config.orientationMode == orient,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateConfig(config.copy(orientationMode = orient))
                        }
                    }
                }

                // FPS Selection with Hardware Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Frame Rate (FPS)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = supportEval.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (supportEval.level) {
                            EncoderDetector.SupportLevel.SUPPORTED -> EmeraldMatrix
                            EncoderDetector.SupportLevel.LIMITED -> Color(0xFFFFB703)
                            EncoderDetector.SupportLevel.UNSUPPORTED -> Color(0xFFFF495C)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15, 24, 30, 45, 60).forEach { fps ->
                        PresetButton(
                            label = "$fps FPS",
                            selected = config.fps == fps,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateConfig(config.copy(fps = fps))
                        }
                    }
                }
            }
        }

        // --- SECTION 3: BITRATE CONTROLS & MODES ---
        SectionHeader(icon = Icons.Default.Speed, title = "Bitrate & Encoder Controls")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Target Bitrate", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = "${String.format("%.1f", config.bitrateKbps / 1000f)} Mbps (${config.bitrateKbps} kbps)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }

                // Bitrate Slider (1 Mbps to 50 Mbps)
                Slider(
                    value = config.bitrateKbps.toFloat(),
                    onValueChange = { onUpdateConfig(config.copy(bitrateKbps = it.toInt())) },
                    valueRange = 1000f..50000f,
                    steps = 48,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("bitrate_slider")
                )

                // Bitrate Presets (up to 50 Mbps)
                Text("Bitrate Presets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(2000, 4000, 6000, 8000, 10000, 15000, 20000, 30000, 50000).forEach { kbps ->
                        FilterChip(
                            selected = config.bitrateKbps == kbps,
                            onClick = { onUpdateConfig(config.copy(bitrateKbps = kbps)) },
                            label = { Text("${kbps / 1000}M") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan,
                                selectedLabelColor = Color(0xFF00363D)
                            )
                        )
                    }
                }

                // Bitrate Mode (CBR vs VBR)
                Text("Bitrate Rate Control Mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetButton(
                        label = "CBR (Constant Bitrate)",
                        selected = config.bitrateMode == BitrateModeType.CBR,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(bitrateMode = BitrateModeType.CBR))
                    }
                    PresetButton(
                        label = "VBR (Variable Bitrate)",
                        selected = config.bitrateMode == BitrateModeType.VBR,
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(bitrateMode = BitrateModeType.VBR))
                    }
                }

                // Keyframe Interval / GOP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Keyframe Interval (GOP)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3).forEach { gop ->
                            FilterChip(
                                selected = config.gopSeconds == gop,
                                onClick = { onUpdateConfig(config.copy(gopSeconds = gop)) },
                                label = { Text("${gop}s ${if (gop == 2) "(YT)" else ""}") }
                            )
                        }
                    }
                }

                // Video Codec Standard
                Text("Video Codec Standard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VideoCodec.entries.forEach { codec ->
                        PresetButton(
                            label = codec.label.split(" ").first(),
                            selected = config.videoCodec == codec,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateConfig(config.copy(videoCodec = codec))
                        }
                    }
                }

                // Active Selected Encoder Badge
                val displayEncoderName = if (config.encoderChoice == "AUTO") "Auto (Best HW Accelerated)" else config.encoderChoice
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131B24), RoundedCornerShape(8.dp))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Active Encoder Selection",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = displayEncoderName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = ElectricCyan
                        )
                    }
                }

                // Specific Encoder Text Input
                Text("Specific Encoder Name (Direct Selection)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = if (config.encoderChoice == "AUTO" || config.encoderChoice == "HARDWARE_AVC" || config.encoderChoice == "HARDWARE_HEVC") "" else config.encoderChoice,
                    onValueChange = { input ->
                        val trimmed = input.trim()
                        if (trimmed.isEmpty()) {
                            onUpdateConfig(config.copy(encoderChoice = "AUTO"))
                        } else {
                            val matchedMime = EncoderDetector.getMimeForEncoder(trimmed)
                            val matchedCodec = when {
                                matchedMime?.contains("hevc", ignoreCase = true) == true -> VideoCodec.H265
                                matchedMime?.contains("av01", ignoreCase = true) == true -> VideoCodec.AV1
                                else -> config.videoCodec
                            }
                            onUpdateConfig(config.copy(encoderChoice = trimmed, videoCodec = matchedCodec))
                        }
                    },
                    label = { Text("Encoder Name (e.g. OMX.qcom.video.encoder.avc)") },
                    placeholder = { Text("c2.qti.avc.encoder or OMX.qcom.video.encoder.avc") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("encoder_name_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CardBorder
                    )
                )

                // Quick Presets
                Text("Encoder Quick Presets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PresetButton(
                        label = "Auto (Best HW)",
                        selected = config.encoderChoice == "AUTO",
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(encoderChoice = "AUTO"))
                    }
                    PresetButton(
                        label = "Hardware AVC",
                        selected = config.encoderChoice == "HARDWARE_AVC",
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(encoderChoice = "HARDWARE_AVC", videoCodec = VideoCodec.H264))
                    }
                    PresetButton(
                        label = "Hardware HEVC",
                        selected = config.encoderChoice == "HARDWARE_HEVC",
                        modifier = Modifier.weight(1f)
                    ) {
                        onUpdateConfig(config.copy(encoderChoice = "HARDWARE_HEVC", videoCodec = VideoCodec.H265))
                    }
                }

                // Detected Device Encoders list (with fallback to local detection if empty)
                val availableEncoders = remember(hardwareCaps) {
                    if (hardwareCaps.isNotEmpty()) hardwareCaps else EncoderDetector.getHardwareCapabilities()
                }

                if (availableEncoders.isNotEmpty()) {
                    Text(
                        text = "Detected Encoders on Device (${availableEncoders.size} available — tap to use)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableEncoders.forEach { cap ->
                            val isCapSelected = config.encoderChoice.equals(cap.encoderName, ignoreCase = true)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val matchedCodec = when {
                                            cap.codecMime.contains("hevc", ignoreCase = true) -> VideoCodec.H265
                                            cap.codecMime.contains("av01", ignoreCase = true) -> VideoCodec.AV1
                                            else -> VideoCodec.H264
                                        }
                                        onUpdateConfig(
                                            config.copy(
                                                encoderChoice = cap.encoderName,
                                                videoCodec = matchedCodec
                                            )
                                        )
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCapSelected) DarkSurfaceVariant else Color(0xFF1E2630)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isCapSelected) ElectricCyan else CardBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = cap.encoderName,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            if (isCapSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${if (cap.isHardware) "Hardware Accelerated" else "Software Fallback"} • ${cap.codecMime.substringAfter("video/").uppercase()} • ${cap.maxResolution} @ ${cap.maxFps}fps",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (cap.isHardware) EmeraldMatrix else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isCapSelected) {
                                        Text(
                                            text = "ACTIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ElectricCyan
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION 4: AUDIO SETTINGS & BITRATE SLIDER ---
        SectionHeader(icon = Icons.Default.Audiotrack, title = "Audio Quality & Mixing")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Audio Source selector
                Text("Audio Source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AudioSourceType.entries.forEach { src ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateConfig(config.copy(audioSource = src)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (config.audioSource == src) DarkSurfaceVariant else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (config.audioSource == src) ElectricCyan else CardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(src.label, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(src.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (config.audioSource == src) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = ElectricCyan)
                            }
                        }
                    }
                }

                // Audio Quality Bitrate Slider / Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Audio Bitrate (AAC)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("${config.audioBitrateKbps} kbps", fontWeight = FontWeight.Bold, color = ElectricCyan)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(64, 96, 128, 160, 192).forEach { kbps ->
                        PresetButton(
                            label = "${kbps}k",
                            selected = config.audioBitrateKbps == kbps,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdateConfig(config.copy(audioBitrateKbps = kbps))
                        }
                    }
                }

                // Microphone Volume Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Microphone Gain", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Text("${(config.micVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = ElectricCyan)
                }
                Slider(
                    value = config.micVolume,
                    onValueChange = { onUpdateConfig(config.copy(micVolume = it)) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                )

                // Internal Audio Volume Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Internal Audio Gain", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Text("${(config.internalAudioVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = ElectricCyan)
                }
                Slider(
                    value = config.internalAudioVolume,
                    onValueChange = { onUpdateConfig(config.copy(internalAudioVolume = it)) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                )

                // --- Microphone Input Device (Routing) ---
                Spacer(Modifier.height(4.dp))
                Text("Microphone Input Device (Routing)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AudioInputDevice.entries.forEach { dev ->
                    val isDevSelected = config.audioInputDevice == dev
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateConfig(config.copy(audioInputDevice = dev)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDevSelected) DarkSurfaceVariant else Color.Transparent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDevSelected) ElectricCyan else CardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(dev.label, fontWeight = FontWeight.Bold, color = Color.White)
                                    if (dev.isMono) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF2A2312), RoundedCornerShape(4.dp))
                                                .border(1.dp, Color(0xFFE5A93C), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "MONO MIC",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFE5A93C),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                                Text(dev.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isDevSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = ElectricCyan)
                            }
                        }
                    }
                }

                // Explicit Mono Audio Notice requested by user
                if (config.audioInputDevice == AudioInputDevice.HEADPHONES || config.audioInputDevice == AudioInputDevice.BLUETOOTH) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1C14), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFD4A346).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFE5A93C), modifier = Modifier.size(18.dp))
                            Text(
                                text = "Notice: Wired headphones and Bluetooth microphones record in Mono on Android (hardware constraint). Mono mic is automatically balanced into Left & Right stereo channels in the stream.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE8D5B5)
                            )
                        }
                    }
                }

                // --- Game Mic Concurrency Bypass (Accessibility Privilege) ---
                Spacer(Modifier.height(4.dp))
                val isA11yActive = remember { VeloStreamAccessibilityService.isAccessibilityEnabled(context) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111922)),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isA11yActive) EmeraldMatrix.copy(alpha = 0.6f) else Color(0xFFD4A346).copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Game Mic Concurrency Bypass", fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Dual-app mic access for BGMI & game voice chats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isA11yActive) EmeraldMatrix.copy(alpha = 0.2f) else Color(0xFF422615),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isA11yActive) EmeraldMatrix else Color(0xFFE5A93C),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isA11yActive) "PRIVILEGE ACTIVE" else "DISABLED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = if (isA11yActive) EmeraldMatrix else Color(0xFFE5A93C)
                                )
                            }
                        }

                        Text(
                            text = "Android restricts 2 apps from using the microphone at once. If BGMI or another game enables in-game team chat, standard streaming apps get silenced. Enabling VeloStream accessibility privilege elevates the mic service so both the game and stream record simultaneously!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Voice Communication Bypass Strategy", style = MaterialTheme.typography.bodySmall, color = Color.White)
                            Switch(
                                checked = config.accessibilityMicBypass,
                                onCheckedChange = { onUpdateConfig(config.copy(accessibilityMicBypass = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                            )
                        }

                        Button(
                            onClick = {
                                context.startActivity(VeloStreamAccessibilityService.createSettingsIntent())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isA11yActive) Color(0xFF1B3B2B) else Color(0xFF2E2211),
                                contentColor = if (isA11yActive) EmeraldMatrix else Color(0xFFE5A93C)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isA11yActive) EmeraldMatrix.copy(alpha = 0.5f) else Color(0xFFE5A93C).copy(alpha = 0.6f)
                            )
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isA11yActive) "Accessibility Privilege Active (Tap to Open Settings)" else "Open Android Accessibility Settings to Enable",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // --- SECTION 5: GAMING OVERLAY HUD ---
        SectionHeader(icon = Icons.Default.Hardware, title = "In-Game Floating HUD")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Status Overlay", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Displays a compact draggable pill over games showing LIVE, FPS, Bitrate, and dropped frames without blocking touch controls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = config.showFloatingOverlay,
                    onCheckedChange = { onUpdateConfig(config.copy(showFloatingOverlay = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun PresetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = if (selected) ElectricCyan else DarkSurfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) ElectricCyan else CardBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color(0xFF00363D) else Color.White
        )
    }
}
