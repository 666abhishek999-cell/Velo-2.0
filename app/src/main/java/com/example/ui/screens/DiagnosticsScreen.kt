package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HardwareCapability
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMatrix
import com.example.ui.theme.NeonCrimson

@Composable
fun DiagnosticsScreen(
    caps: List<HardwareCapability>,
    currentEncoder: String = "AUTO",
    onSelectEncoder: (String, String) -> Unit = { _, _ -> },
    onApplyRecommended: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Device Hardware Profile ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_profile_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = ElectricCyan)
                        Text(
                            text = "DEVICE HARDWARE PROFILE",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProfileParam("Model", "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}")
                        ProfileParam("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProfileParam("Board / SOC", Build.HARDWARE)
                        ProfileParam("Encoders Detected", "${caps.size} codecs")
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = onApplyRecommended,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apply_recommended_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF00363D)
                        )
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apply Recommended Settings for Device", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- 2. Title Header ---
        item {
            Text(
                text = "DEVICE ENCODER INFORMATION",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan,
                letterSpacing = 1.sp
            )
        }

        if (caps.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Text(
                        text = "Scanning device MediaCodecList…",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- 3. Encoder Capability Cards ---
        items(caps) { item ->
            val isSelected = currentEncoder.equals(item.encoderName, ignoreCase = true)
            EncoderCard(
                item = item,
                isSelected = isSelected,
                onSelect = { onSelectEncoder(item.encoderName, item.codecMime) }
            )
        }
    }
}

@Composable
private fun ProfileParam(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun EncoderCard(
    item: HardwareCapability,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("encoder_${item.encoderName}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isSelected) ElectricCyan else if (item.isHardware) EmeraldMatrix.copy(alpha = 0.5f) else CardBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.encoderName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active encoder",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(
                            color = if (item.isHardware) EmeraldMatrix.copy(alpha = 0.2f) else DarkSurfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (item.isHardware) "HARDWARE" else "SOFTWARE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isHardware) EmeraldMatrix else Color(0xFFFFB703)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoField("Codec", item.codecMime.substringAfter("video/").uppercase())
                InfoField("Max Resolution", item.maxResolution)
                InfoField("Max FPS", "${item.maxFps} FPS")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoField("Max Bitrate", "${item.maxBitrateKbps / 1000} Mbps")
                InfoField("Bitrate Modes", item.supportedBitrateModes.joinToString(", "))
            }

            if (item.limitationReason != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB703), modifier = Modifier.size(14.dp))
                    Text(
                        text = item.limitationReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB703)
                    )
                }
            }

            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("select_encoder_${item.encoderName}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) ElectricCyan else DarkSurfaceVariant,
                    contentColor = if (isSelected) Color(0xFF00363D) else Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isSelected) "Active Encoder" else "Use This Encoder",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
