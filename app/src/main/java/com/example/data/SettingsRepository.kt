package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AspectRatioMode
import com.example.model.AudioSourceType
import com.example.model.BitrateModeType
import com.example.model.ScreenOrientationMode
import com.example.model.StreamConfig
import com.example.model.StreamingProtocol
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("velostream_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<StreamConfig> = _config.asStateFlow()

    private fun loadConfig(): StreamConfig {
        val resName = prefs.getString("resolution", VideoResolution.RES_1080P.name) ?: VideoResolution.RES_1080P.name
        val res = try { VideoResolution.valueOf(resName) } catch (_: Exception) { VideoResolution.RES_1080P }

        val aspectName = prefs.getString("aspect_ratio", AspectRatioMode.STRETCH_16_9.name) ?: AspectRatioMode.STRETCH_16_9.name
        val aspect = try { AspectRatioMode.valueOf(aspectName) } catch (_: Exception) { AspectRatioMode.STRETCH_16_9 }

        val orientName = prefs.getString("orientation", ScreenOrientationMode.AUTO.name) ?: ScreenOrientationMode.AUTO.name
        val orient = try { ScreenOrientationMode.valueOf(orientName) } catch (_: Exception) { ScreenOrientationMode.AUTO }

        val bitrateModeName = prefs.getString("bitrate_mode", BitrateModeType.CBR.name) ?: BitrateModeType.CBR.name
        val bitrateMode = try { BitrateModeType.valueOf(bitrateModeName) } catch (_: Exception) { BitrateModeType.CBR }

        val audioSourceName = prefs.getString("audio_source", AudioSourceType.INTERNAL_PLUS_MIC.name) ?: AudioSourceType.INTERNAL_PLUS_MIC.name
        val audioSource = try { AudioSourceType.valueOf(audioSourceName) } catch (_: Exception) { AudioSourceType.INTERNAL_PLUS_MIC }

        val audioDeviceName = prefs.getString("audio_input_device", com.example.model.AudioInputDevice.AUTO.name) ?: com.example.model.AudioInputDevice.AUTO.name
        val audioDevice = try { com.example.model.AudioInputDevice.valueOf(audioDeviceName) } catch (_: Exception) { com.example.model.AudioInputDevice.AUTO }

        val a11yBypass = prefs.getBoolean("accessibility_mic_bypass", true)

        val protocolName = prefs.getString("streaming_protocol", StreamingProtocol.RTMP.name) ?: StreamingProtocol.RTMP.name
        val protocol = try { StreamingProtocol.valueOf(protocolName) } catch (_: Exception) { StreamingProtocol.RTMP }

        val codecName = prefs.getString("video_codec", VideoCodec.H264.name) ?: VideoCodec.H264.name
        val codec = try { VideoCodec.valueOf(codecName) } catch (_: Exception) { VideoCodec.H264 }

        return StreamConfig(
            rtmpUrl = prefs.getString("rtmp_url", "rtmp://a.rtmp.youtube.com/live2") ?: "rtmp://a.rtmp.youtube.com/live2",
            streamKey = prefs.getString("stream_key", "") ?: "",
            streamingProtocol = protocol,
            hlsStreamUrl = prefs.getString("hls_stream_url", "https://a.upload.youtube.com/http_upload_hls?cid=") ?: "https://a.upload.youtube.com/http_upload_hls?cid=",
            videoCodec = codec,
            resolution = res,
            aspectRatioMode = aspect,
            orientationMode = orient,
            fps = prefs.getInt("fps", 60),
            bitrateKbps = prefs.getInt("bitrate_kbps", 8000),
            bitrateMode = bitrateMode,
            gopSeconds = prefs.getInt("gop_seconds", 2),
            encoderChoice = prefs.getString("encoder_choice", "AUTO") ?: "AUTO",
            audioSource = audioSource,
            audioInputDevice = audioDevice,
            accessibilityMicBypass = a11yBypass,
            audioBitrateKbps = prefs.getInt("audio_bitrate_kbps", 128),
            micVolume = prefs.getFloat("mic_volume", 1.0f),
            internalAudioVolume = prefs.getFloat("internal_volume", 1.0f),
            lowEndDeviceMode = prefs.getBoolean("low_end_mode", false),
            showFloatingOverlay = prefs.getBoolean("floating_overlay", false),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            reconnectDelaySeconds = prefs.getInt("reconnect_delay", 3)
        )
    }

    fun updateConfig(newConfig: StreamConfig) {
        prefs.edit().apply {
            putString("rtmp_url", newConfig.rtmpUrl)
            putString("stream_key", newConfig.streamKey)
            putString("streaming_protocol", newConfig.streamingProtocol.name)
            putString("hls_stream_url", newConfig.hlsStreamUrl)
            putString("video_codec", newConfig.videoCodec.name)
            putString("resolution", newConfig.resolution.name)
            putString("aspect_ratio", newConfig.aspectRatioMode.name)
            putString("orientation", newConfig.orientationMode.name)
            putInt("fps", newConfig.fps)
            putInt("bitrate_kbps", newConfig.bitrateKbps)
            putString("bitrate_mode", newConfig.bitrateMode.name)
            putInt("gop_seconds", newConfig.gopSeconds)
            putString("encoder_choice", newConfig.encoderChoice)
            putString("audio_source", newConfig.audioSource.name)
            putString("audio_input_device", newConfig.audioInputDevice.name)
            putBoolean("accessibility_mic_bypass", newConfig.accessibilityMicBypass)
            putInt("audio_bitrate_kbps", newConfig.audioBitrateKbps)
            putFloat("mic_volume", newConfig.micVolume)
            putFloat("internal_volume", newConfig.internalAudioVolume)
            putBoolean("low_end_mode", newConfig.lowEndDeviceMode)
            putBoolean("floating_overlay", newConfig.showFloatingOverlay)
            putBoolean("auto_reconnect", newConfig.autoReconnect)
            putInt("reconnect_delay", newConfig.reconnectDelaySeconds)
            apply()
        }
        _config.value = newConfig
    }
}
