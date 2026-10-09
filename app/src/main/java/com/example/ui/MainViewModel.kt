package com.example.ui

import android.app.Application
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.RecordingsRepository
import com.example.data.SettingsRepository
import com.example.encoder.EncoderDetector
import com.example.hls.YouTubeHlsClient
import com.example.model.CaptureMode
import com.example.model.HardwareCapability
import com.example.model.LiveStatistics
import com.example.model.LocalRecordingItem
import com.example.model.StreamConfig
import com.example.model.StreamingProtocol
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import com.example.rtmp.RtmpClient
import com.example.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    STUDIO,
    SETTINGS,
    DIAGNOSTICS,
    RECORDINGS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val recordingsRepo = RecordingsRepository(application)

    val config: StateFlow<StreamConfig> = settingsRepo.config
    val liveStats: StateFlow<LiveStatistics> = ScreenCaptureService.serviceStats
    val recordings: StateFlow<List<LocalRecordingItem>> = recordingsRepo.recordings

    private val _currentTab = MutableStateFlow(MainTab.STUDIO)
    val currentTab: StateFlow<MainTab> = _currentTab.asStateFlow()

    private val _hardwareCaps = MutableStateFlow<List<HardwareCapability>>(emptyList())
    val hardwareCaps: StateFlow<List<HardwareCapability>> = _hardwareCaps.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<String?>(null)
    val connectionTestResult: StateFlow<String?> = _connectionTestResult.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    init {
        loadHardwareCapabilities()
        refreshRecordings()
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
        if (tab == MainTab.RECORDINGS) {
            refreshRecordings()
        }
    }

    fun updateConfig(newConfig: StreamConfig) {
        settingsRepo.updateConfig(newConfig)
    }

    private fun loadHardwareCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val caps = EncoderDetector.getHardwareCapabilities()
            _hardwareCaps.value = caps
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            recordingsRepo.refreshRecordings()
        }
    }

    fun deleteRecording(filePath: String) {
        viewModelScope.launch {
            recordingsRepo.deleteRecording(filePath)
        }
    }

    fun evaluateCurrentSupport(): EncoderDetector.SupportEvaluation {
        val current = config.value
        val (w, h) = if (current.resolution == VideoResolution.RES_NATIVE) {
            val metrics = getDisplayMetrics()
            Pair(metrics.widthPixels, metrics.heightPixels)
        } else {
            Pair(current.resolution.width16x9, current.resolution.height)
        }

        return EncoderDetector.evaluateSupport(
            width = maxOf(w, h),
            height = minOf(w, h),
            targetFps = current.fps,
            preferredEncoderName = current.encoderChoice
        )
    }

    fun applyRecommendedSettings() {
        val metrics = getDisplayMetrics()
        val rec = EncoderDetector.getRecommendedConfig(metrics.widthPixels, metrics.heightPixels)
        val updated = config.value.copy(
            resolution = rec.resolution,
            fps = rec.fps,
            bitrateKbps = rec.bitrateKbps
        )
        updateConfig(updated)
    }

    fun applyLowEndDevicePreset() {
        val updated = config.value.copy(
            lowEndDeviceMode = true,
            resolution = VideoResolution.RES_720P,
            fps = 30,
            bitrateKbps = 3000,
            gopSeconds = 2,
            encoderChoice = "AUTO"
        )
        updateConfig(updated)
    }

    fun testRtmpConnection() {
        val current = config.value
        _isTestingConnection.value = true
        _connectionTestResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val (success, message) = if (current.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                YouTubeHlsClient().testConnection(current.hlsStreamUrl, current.streamKey)
            } else {
                val client = RtmpClient()
                client.testConnection(current.rtmpUrl, current.streamKey)
            }
            withContext(Dispatchers.Main) {
                _isTestingConnection.value = false
                _connectionTestResult.value = message
            }
        }
    }

    fun selectEncoder(encoderName: String, codecMime: String) {
        val matchedCodec = when {
            codecMime.contains("hevc", ignoreCase = true) || codecMime.contains("265", ignoreCase = true) -> VideoCodec.H265
            codecMime.contains("av01", ignoreCase = true) || codecMime.contains("av1", ignoreCase = true) -> VideoCodec.AV1
            else -> VideoCodec.H264
        }
        val updated = config.value.copy(
            encoderChoice = encoderName,
            videoCodec = matchedCodec
        )
        updateConfig(updated)
    }

    fun clearTestResult() {
        _connectionTestResult.value = null
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = getApplication<Application>().getSystemService(Application.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
