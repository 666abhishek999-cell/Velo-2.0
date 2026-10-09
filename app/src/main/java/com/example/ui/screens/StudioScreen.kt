package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.encoder.EncoderDetector
import com.example.model.CaptureMode
import com.example.model.LiveStatistics
import com.example.model.StreamConfig
import com.example.model.VideoResolution
import com.example.ui.MainTab
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMatrix
import com.example.ui.theme.NeonCrimson

@Composable
fun StudioScreen(
    config: StreamConfig,
    stats: LiveStatistics,
    supportEval: EncoderDetector.SupportEvaluation,
    onStartCapture: (CaptureMode) -> Unit,
    onStopCapture: () -> Unit,
    onNavigateTab: (MainTab) -> Unit,
    onUpdateConfig: (StreamConfig) -> Unit
) {
    val scrollState = rememberScrollState()
    val isRunning = stats.isStreaming || stats.isRecording

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Studio Status Hero Banner ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("studio_status_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isRunning) NeonCrimson else CardBorder
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) NeonCrimson else EmeraldMatrix)
                                .then(if (isRunning) Modifier.alpha(pulseAlpha) else Modifier)
                        )
                        Text(
                            text = when {
                                stats.isStreaming && stats.isRecording -> "STREAM + RECORD ACTIVE"
                                stats.isStreaming -> "LIVE STREAMING"
                                stats.isRecording -> "LOCAL RECORDING"
                                else -> "READY TO BROADCAST"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) NeonCrimson else EmeraldMatrix,
                            letterSpacing = 1.sp
                        )
                    }

                    if (isRunning) {
                        val m = stats.durationSeconds / 60
                        val s = stats.durationSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", m, s),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = config.aspectRatioMode.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isRunning) {
                    // Live Telemetry Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryItem(
                            label = "FPS",
                            value = "${stats.currentFps.toInt()} / ${stats.targetFps}",
                            color = ElectricCyan
                        )
                        val bitrateLabel = when {
                            stats.isStreaming && stats.isRecording -> "STREAM/REC"
                            stats.isStreaming -> "UPLOAD"
                            stats.isRecording -> "REC BITRATE"
                            else -> "BITRATE"
                        }
                        val bitrateVal = if (stats.isRecording && !stats.isStreaming) {
                            if (stats.recordingBitrateKbps > 0) stats.recordingBitrateKbps
                            else if (stats.activeBitrateKbps > 0) stats.activeBitrateKbps
                            else stats.targetBitrateKbps.toLong()
                        } else {
                            if (stats.activeBitrateKbps > 0) stats.activeBitrateKbps
                            else if (stats.recordingBitrateKbps > 0) stats.recordingBitrateKbps
                            else stats.targetBitrateKbps.toLong()
                        }
                        TelemetryItem(
                            label = bitrateLabel,
                            value = "${String.format("%.1f", bitrateVal / 1000f)} Mbps",
                            color = ElectricCyan
                        )
                        TelemetryItem(
                            label = "DROPPED",
                            value = "${stats.droppedFrames}",
                            color = if (stats.droppedFrames > 0) NeonCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TelemetryItem(
                            label = "STATUS",
                            value = if (stats.isRecording && !stats.isStreaming) "Recording" else stats.networkStatus,
                            color = EmeraldMatrix
                        )
                    }
                } else {
                    // Encoder support banner
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when (supportEval.level) {
                                EncoderDetector.SupportLevel.SUPPORTED -> Icons.Default.CheckCircle
                                EncoderDetector.SupportLevel.LIMITED -> Icons.Default.Warning
                                EncoderDetector.SupportLevel.UNSUPPORTED -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when (supportEval.level) {
                                EncoderDetector.SupportLevel.SUPPORTED -> EmeraldMatrix
                                EncoderDetector.SupportLevel.LIMITED -> Color(0xFFFFB703)
                                EncoderDetector.SupportLevel.UNSUPPORTED -> Color(0xFFFF495C)
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "${supportEval.label} • ${supportEval.details}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- 2. Primary Control Action Buttons ---
        if (!isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onStartCapture(CaptureMode.STREAM_ONLY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("start_streaming_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF00363D)
                    )
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Start Streaming", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onStartCapture(CaptureMode.RECORD_ONLY) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("start_recording_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NeonCrimson
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCrimson)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Record Only", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onStartCapture(CaptureMode.STREAM_AND_RECORD) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("stream_and_record_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkSurfaceVariant,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp), tint = ElectricCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Stream + Record", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Button(
                onClick = onStopCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("stop_capture_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCrimson,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Stop Broadcasting", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // --- 3. Quick Config Overview Tiles ---
        Text(
            text = "Active Configuration",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConfigTile(
                modifier = Modifier.weight(1f),
                title = "Resolution",
                value = config.resolution.label.split(" ").first(),
                subtitle = config.aspectRatioMode.label.split(" ").first(),
                onClick = { onNavigateTab(MainTab.SETTINGS) }
            )
            ConfigTile(
                modifier = Modifier.weight(1f),
                title = "FPS",
                value = "${config.fps} FPS",
                subtitle = supportEval.label,
                onClick = { onNavigateTab(MainTab.SETTINGS) }
            )
            ConfigTile(
                modifier = Modifier.weight(1f),
                title = "Bitrate",
                value = "${config.bitrateKbps / 1000}M",
                subtitle = config.bitrateMode.name,
                onClick = { onNavigateTab(MainTab.SETTINGS) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConfigTile(
                modifier = Modifier.weight(1f),
                title = "Audio",
                value = config.audioSource.label,
                subtitle = "${config.audioBitrateKbps} kbps",
                onClick = { onNavigateTab(MainTab.SETTINGS) }
            )
            ConfigTile(
                modifier = Modifier.weight(1f),
                title = "Encoder",
                value = if (config.encoderChoice == "AUTO") "HW AVC" else config.encoderChoice.take(10),
                subtitle = "MediaCodec",
                onClick = { onNavigateTab(MainTab.DIAGNOSTICS) }
            )
        }

        // --- 4. Gaming Presets ---
        Text(
            text = "Gaming Profiles",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PresetChip(
                title = "YouTube 1080p60",
                subtitle = "8 Mbps CBR",
                modifier = Modifier.weight(1f),
                onClick = {
                    onUpdateConfig(
                        config.copy(
                            resolution = VideoResolution.RES_1080P,
                            fps = 60,
                            bitrateKbps = 8000
                        )
                    )
                }
            )
            PresetChip(
                title = "Twitch 720p60",
                subtitle = "4.5 Mbps CBR",
                modifier = Modifier.weight(1f),
                onClick = {
                    onUpdateConfig(
                        config.copy(
                            resolution = VideoResolution.RES_720P,
                            fps = 60,
                            bitrateKbps = 4500
                        )
                    )
                }
            )
            PresetChip(
                title = "Low-End Safe",
                subtitle = "720p30 • 3M",
                modifier = Modifier.weight(1f),
                onClick = {
                    onUpdateConfig(
                        config.copy(
                            resolution = VideoResolution.RES_720P,
                            fps = 30,
                            bitrateKbps = 3000,
                            lowEndDeviceMode = true
                        )
                    )
                }
            )
        }

        // --- 5. Low-End Device Mode Quick Switch ---
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
                    Text(
                        text = "Low-End Phone Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Reduces CPU wakeups, optimizes buffer queues, and minimizes memory footprint for smooth gaming.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = config.lowEndDeviceMode,
                    onCheckedChange = { checked ->
                        onUpdateConfig(config.copy(lowEndDeviceMode = checked))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ElectricCyan,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("low_end_mode_switch")
                )
            }
        }
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun ConfigTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .testTag("tile_${title.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
