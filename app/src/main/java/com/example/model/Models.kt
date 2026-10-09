package com.example.model

enum class VideoResolution(val label: String, val height: Int, val width16x9: Int) {
    RES_480P("480p (SD)", 480, 854),
    RES_720P("720p (HD)", 720, 1280),
    RES_900P("900p (HD+)", 900, 1600),
    RES_1080P("1080p (Full HD)", 1080, 1920),
    RES_1440P("1440p (2K QHD)", 1440, 2560),
    RES_2160P("2160p (4K UHD)", 2160, 3840),
    RES_NATIVE("Device Native", 0, 0);

    companion object {
        fun fromLabel(label: String): VideoResolution =
            entries.firstOrNull { it.label == label } ?: RES_1080P
    }
}

enum class AspectRatioMode(val label: String, val description: String) {
    NATIVE("Native Aspect Ratio", "Keeps exact device screen ratio"),
    STRETCH_16_9("Stretch to 16:9", "Perfect for 3:2, 4:3, 7:5 tablets/phones for YouTube. No black bars, no screen cropped"),
    FIT_16_9("Fit 16:9 (Letterbox)", "Pads 16:9 frame with black borders without stretching")
}

enum class ScreenOrientationMode(val label: String) {
    AUTO("Auto (Follow Display)"),
    LANDSCAPE("Landscape (Gaming)"),
    PORTRAIT("Portrait (Mobile)")
}

enum class BitrateModeType(val label: String) {
    CBR("CBR (Constant Bitrate)"),
    VBR("VBR (Variable Bitrate)")
}

enum class AudioSourceType(val label: String, val description: String) {
    INTERNAL_AUDIO("Internal Audio", "System & game sound only (Android 10+)"),
    MICROPHONE("Microphone", "Voice and room microphone"),
    INTERNAL_PLUS_MIC("Internal + Microphone", "Mixed game sound and voice commentary"),
    MUTED("Audio Off", "Silent video stream")
}

enum class AudioInputDevice(
    val label: String,
    val description: String,
    val isMono: Boolean
) {
    AUTO("Auto / System Default", "Uses Android default routing", false),
    PHONE_MIC("Phone Built-in Mic", "Internal device microphone", false),
    HEADPHONES("Headphones / Headset Mic", "3.5mm jack or USB-C wired headset mic (Mono)", true),
    BLUETOOTH("Bluetooth Mic (Nearby Device)", "Wireless Bluetooth headset / earbuds (Mono)", true)
}

enum class StreamingProtocol(val label: String, val description: String) {
    RTMP("RTMP / RTMPS", "Standard live stream protocol (YouTube, Twitch, Facebook, Kick)"),
    YOUTUBE_HLS("YouTube HLS", "HTTP Live Streaming for YouTube (Native H.265 / HEVC & 4K support)")
}

enum class VideoCodec(val id: String, val mimeType: String, val label: String, val description: String) {
    H264("H264", "video/avc", "H.264 / AVC", "Universal compatibility (100% of devices & RTMP platforms)"),
    H265("H265", "video/hevc", "H.265 / HEVC", "High efficiency, 50% smaller files, YouTube HLS & 4K ready"),
    AV1("AV1", "video/av01", "AV1 (Next-Gen)", "Advanced compression for modern Android 14+ devices")
}

enum class CaptureMode {
    STREAM_ONLY,
    RECORD_ONLY,
    STREAM_AND_RECORD
}

enum class StreamStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    ERROR
}

enum class RecordStatus {
    IDLE,
    RECORDING,
    SAVING,
    ERROR
}

data class StreamConfig(
    val rtmpUrl: String = "rtmp://a.rtmp.youtube.com/live2",
    val streamKey: String = "",
    val streamingProtocol: StreamingProtocol = StreamingProtocol.RTMP,
    val hlsStreamUrl: String = "https://a.upload.youtube.com/http_upload_hls?cid=",
    val videoCodec: VideoCodec = VideoCodec.H264,
    val resolution: VideoResolution = VideoResolution.RES_1080P,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.STRETCH_16_9,
    val orientationMode: ScreenOrientationMode = ScreenOrientationMode.AUTO,
    val fps: Int = 60,
    val bitrateKbps: Int = 8000,
    val bitrateMode: BitrateModeType = BitrateModeType.CBR,
    val gopSeconds: Int = 2,
    val encoderChoice: String = "AUTO", // AUTO, HARDWARE_AVC, HARDWARE_HEVC, or specific codec name
    val audioSource: AudioSourceType = AudioSourceType.INTERNAL_PLUS_MIC,
    val audioInputDevice: AudioInputDevice = AudioInputDevice.AUTO,
    val accessibilityMicBypass: Boolean = true,
    val audioBitrateKbps: Int = 128,
    val micVolume: Float = 1.0f,
    val internalAudioVolume: Float = 1.0f,
    val lowEndDeviceMode: Boolean = false,
    val showFloatingOverlay: Boolean = false,
    val autoReconnect: Boolean = true,
    val reconnectDelaySeconds: Int = 3
)

data class HardwareCapability(
    val encoderName: String,
    val codecMime: String,
    val isHardware: Boolean,
    val isSupported: Boolean,
    val maxResolution: String,
    val maxFps: Int,
    val maxBitrateKbps: Int,
    val supportedBitrateModes: List<String>,
    val limitationReason: String? = null
)

data class LiveStatistics(
    val isStreaming: Boolean = false,
    val isRecording: Boolean = false,
    val streamStatus: StreamStatus = StreamStatus.IDLE,
    val recordStatus: RecordStatus = RecordStatus.IDLE,
    val currentFps: Float = 0f,
    val targetFps: Int = 60,
    val uploadBitrateKbps: Long = 0L,
    val recordingBitrateKbps: Long = 0L,
    val activeBitrateKbps: Long = 0L,
    val targetBitrateKbps: Int = 8000,
    val droppedFrames: Long = 0L,
    val encodedFrames: Long = 0L,
    val durationSeconds: Long = 0L,
    val encoderName: String = "HW AVC",
    val activeResolution: String = "1080p",
    val networkStatus: String = "Idle",
    val memoryUsageMb: Long = 0L,
    val recordedBytes: Long = 0L,
    val lastRecordedFilePath: String? = null,
    val errorMessage: String? = null
)

data class LocalRecordingItem(
    val id: Long,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long
)
