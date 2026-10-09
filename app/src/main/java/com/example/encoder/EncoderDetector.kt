package com.example.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.example.model.HardwareCapability
import com.example.model.VideoCodec
import com.example.model.VideoResolution

object EncoderDetector {

    data class CodecDetectionResult(
        val codec: VideoCodec,
        val isSupported: Boolean,
        val isHardware: Boolean,
        val encoderName: String?,
        val badgeLabel: String,
        val maxResolution: String
    )

    fun getCodecDetectionResult(codec: VideoCodec): CodecDetectionResult {
        val encoders = getAllVideoEncoders().filter { info ->
            info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }
        }
        if (encoders.isEmpty()) {
            return CodecDetectionResult(
                codec = codec,
                isSupported = false,
                isHardware = false,
                encoderName = null,
                badgeLabel = "Not Supported",
                maxResolution = "N/A"
            )
        }
        val hw = encoders.firstOrNull { isHardwareEncoder(it) }
        val chosen = hw ?: encoders.first()
        val isHw = isHardwareEncoder(chosen)
        var maxRes = "1080p"
        try {
            val caps = chosen.getCapabilitiesForType(codec.mimeType)
            val w = caps.videoCapabilities?.supportedWidths?.upper ?: 1920
            val h = caps.videoCapabilities?.supportedHeights?.upper ?: 1080
            maxRes = "${w}x${h}"
        } catch (_: Exception) {}

        return CodecDetectionResult(
            codec = codec,
            isSupported = true,
            isHardware = isHw,
            encoderName = chosen.name,
            badgeLabel = if (isHw) "HW Accelerated" else "Software Only",
            maxResolution = maxRes
        )
    }

    enum class SupportLevel {
        SUPPORTED,
        LIMITED,
        UNSUPPORTED
    }

    data class SupportEvaluation(
        val level: SupportLevel,
        val label: String,
        val details: String
    )

    fun isHardwareEncoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") &&
                !name.startsWith("c2.android.") &&
                !name.contains("sw") &&
                !name.contains("software")
    }

    fun getAllVideoEncoders(): List<MediaCodecInfo> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.filter { it.isEncoder && isVideoCodec(it) }
    }

    private fun isVideoCodec(info: MediaCodecInfo): Boolean {
        val supportedTypes = info.supportedTypes
        return supportedTypes.any { type ->
            type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
            type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ||
            type.equals("video/av01", ignoreCase = true)
        }
    }

    fun getHardwareCapabilities(): List<HardwareCapability> {
        val list = mutableListOf<HardwareCapability>()
        val encoders = getAllVideoEncoders()

        for (encoder in encoders) {
            val isHw = isHardwareEncoder(encoder)
            for (type in encoder.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ||
                    type.equals("video/av01", ignoreCase = true)
                ) {
                    try {
                        val caps = encoder.getCapabilitiesForType(type)
                        val videoCaps = caps.videoCapabilities
                        val encoderCaps = caps.encoderCapabilities

                        val maxWidth = videoCaps?.supportedWidths?.upper ?: 1920
                        val maxHeight = videoCaps?.supportedHeights?.upper ?: 1080
                        val maxFps = videoCaps?.supportedFrameRates?.upper?.toInt() ?: 60
                        val maxBitrate = (videoCaps?.bitrateRange?.upper ?: (20_000_000)) / 1000

                        val bitrateModes = mutableListOf<String>()
                        if (encoderCaps != null) {
                            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                                bitrateModes.add("CBR")
                            }
                            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                                bitrateModes.add("VBR")
                            }
                            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                                bitrateModes.add("CQ")
                            }
                        }
                        if (bitrateModes.isEmpty()) {
                            bitrateModes.add("VBR")
                        }

                        list.add(
                            HardwareCapability(
                                encoderName = encoder.name,
                                codecMime = type,
                                isHardware = isHw,
                                isSupported = true,
                                maxResolution = "${maxWidth}x${maxHeight}",
                                maxFps = maxFps,
                                maxBitrateKbps = maxBitrate,
                                supportedBitrateModes = bitrateModes,
                                limitationReason = if (!isHw) "Software fallback encoder" else null
                            )
                        )
                    } catch (e: Exception) {
                        // Even if querying detailed caps fails, still expose the encoder
                        list.add(
                            HardwareCapability(
                                encoderName = encoder.name,
                                codecMime = type,
                                isHardware = isHw,
                                isSupported = true,
                                maxResolution = "1920x1080",
                                maxFps = 60,
                                maxBitrateKbps = 20000,
                                supportedBitrateModes = listOf("VBR", "CBR"),
                                limitationReason = if (!isHw) "Software fallback encoder" else null
                            )
                        )
                    }
                }
            }
        }
        return list.sortedWith(compareByDescending<HardwareCapability> { it.isHardware }.thenBy { it.codecMime })
    }

    fun getMimeForEncoder(encoderName: String): String? {
        val encoder = getAllVideoEncoders().firstOrNull { it.name.equals(encoderName, ignoreCase = true) }
            ?: return null
        return when {
            encoder.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } -> MediaFormat.MIMETYPE_VIDEO_HEVC
            encoder.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } -> "video/av01"
            encoder.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> encoder.supportedTypes.firstOrNull { it.startsWith("video/") }
        }
    }

    fun findBestEncoder(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC, preferHardware: Boolean = true): MediaCodecInfo? {
        val encoders = getAllVideoEncoders().filter { info ->
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
        if (preferHardware) {
            val hw = encoders.firstOrNull { isHardwareEncoder(it) }
            if (hw != null) return hw
        }
        return encoders.firstOrNull()
    }

    fun evaluateSupport(
        width: Int,
        height: Int,
        targetFps: Int,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        preferredEncoderName: String = "AUTO"
    ): SupportEvaluation {
        val encoder: MediaCodecInfo? = if (preferredEncoderName != "AUTO" && preferredEncoderName.isNotBlank()) {
            getAllVideoEncoders().firstOrNull { it.name.equals(preferredEncoderName, ignoreCase = true) }
                ?: findBestEncoder(mimeType)
        } else {
            findBestEncoder(mimeType)
        }

        if (encoder == null) {
            return SupportEvaluation(
                level = SupportLevel.UNSUPPORTED,
                label = "Not supported",
                details = "No compatible $mimeType encoder found on device."
            )
        }

        val isHw = isHardwareEncoder(encoder)
        try {
            val caps = encoder.getCapabilitiesForType(mimeType)
            val videoCaps = caps.videoCapabilities ?: return SupportEvaluation(
                level = SupportLevel.SUPPORTED,
                label = if (isHw) "Hardware supported" else "Software encoder",
                details = "Capabilities not restricted."
            )

            val isSizeOk = videoCaps.isSizeSupported(width, height)
            if (!isSizeOk) {
                val maxWidth = videoCaps.supportedWidths.upper
                val maxHeight = videoCaps.supportedHeights.upper
                return SupportEvaluation(
                    level = SupportLevel.UNSUPPORTED,
                    label = "Not supported",
                    details = "Requested ${width}x${height} exceeds maximum supported ${maxWidth}x${maxHeight} on ${encoder.name}."
                )
            }

            val maxFps = videoCaps.supportedFrameRates.upper.toInt()
            if (targetFps > maxFps) {
                return SupportEvaluation(
                    level = SupportLevel.LIMITED,
                    label = "Hardware limited",
                    details = "${encoder.name} supports max ${maxFps} FPS at this resolution."
                )
            }

            val isFullRateSupported = videoCaps.areSizeAndRateSupported(width, height, targetFps.toDouble())
            if (!isFullRateSupported) {
                return SupportEvaluation(
                    level = SupportLevel.LIMITED,
                    label = "Hardware limited",
                    details = "${encoder.name} may drop frames at ${width}x${height} @ ${targetFps}fps."
                )
            }

            return SupportEvaluation(
                level = SupportLevel.SUPPORTED,
                label = if (isHw) "Hardware supported" else "Software supported",
                details = "${if (isHw) "HW" else "SW"} encoder ${encoder.name} verified up to ${width}x${height} @ ${targetFps}fps."
            )
        } catch (e: Exception) {
            return SupportEvaluation(
                level = SupportLevel.SUPPORTED,
                label = "Hardware supported",
                details = "Encoder available (${encoder.name})."
            )
        }
    }

    /**
     * Determines the optimal configuration based on actual device hardware capabilities.
     */
    fun getRecommendedConfig(displayWidth: Int, displayHeight: Int): RecommendedSettings {
        val hwCaps = getHardwareCapabilities().filter { it.isHardware }
        val avcHw = hwCaps.firstOrNull { it.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }

        val canDo1080p60 = avcHw != null && avcHw.maxFps >= 60
        val canDo720p60 = avcHw != null

        return if (canDo1080p60) {
            RecommendedSettings(
                resolution = VideoResolution.RES_1080P,
                fps = 60,
                bitrateKbps = 8000,
                explanation = "Your device has a high-performance hardware encoder (${avcHw?.encoderName}). Recommended: 1080p 60FPS at 8 Mbps."
            )
        } else if (canDo720p60) {
            RecommendedSettings(
                resolution = VideoResolution.RES_720P,
                fps = 60,
                bitrateKbps = 4500,
                explanation = "Recommended for smooth gaming without thermal throttling: 720p 60FPS at 4.5 Mbps."
            )
        } else {
            RecommendedSettings(
                resolution = VideoResolution.RES_720P,
                fps = 30,
                bitrateKbps = 3000,
                explanation = "Recommended for low-end device stability: 720p 30FPS at 3 Mbps."
            )
        }
    }

    data class RecommendedSettings(
        val resolution: VideoResolution,
        val fps: Int,
        val bitrateKbps: Int,
        val explanation: String
    )
}
