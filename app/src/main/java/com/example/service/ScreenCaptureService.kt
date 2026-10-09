package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureEngine
import com.example.data.RecordingsRepository
import com.example.data.SettingsRepository
import com.example.hls.YouTubeHlsClient
import com.example.model.AudioSourceType
import com.example.model.CaptureMode
import com.example.model.LiveStatistics
import com.example.model.RecordStatus
import com.example.model.StreamConfig
import com.example.model.StreamStatus
import com.example.model.StreamingProtocol
import com.example.model.VideoCodec
import com.example.record.LocalRecorder
import com.example.rtmp.RtmpClient
import com.example.video.VideoEncoderPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "velostream_capture_channel"

        const val ACTION_START = "com.example.velostream.START"
        const val ACTION_STOP = "com.example.velostream.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CAPTURE_MODE = "extra_capture_mode"

        private val _serviceStats = MutableStateFlow(LiveStatistics())
        val serviceStats: StateFlow<LiveStatistics> = _serviceStats.asStateFlow()

        @Volatile var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var statsJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var videoPipeline: VideoEncoderPipeline? = null
    private var audioEngine: AudioCaptureEngine? = null
    private var rtmpClient: RtmpClient? = null
    private var hlsClient: YouTubeHlsClient? = null
    private var localRecorder: LocalRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection session terminated by system/user")
            stopCapture()
            stopSelf()
        }
    }

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var recordingsRepository: RecordingsRepository
    private var activeConfig: StreamConfig = StreamConfig()
    private var activeMode: CaptureMode = CaptureMode.STREAM_ONLY

    private val shouldStream: Boolean
        get() = activeMode == CaptureMode.STREAM_ONLY || activeMode == CaptureMode.STREAM_AND_RECORD

    private val shouldRecord: Boolean
        get() = activeMode == CaptureMode.RECORD_ONLY || activeMode == CaptureMode.STREAM_AND_RECORD

    private var startTimeMs = 0L
    private var streamBasePtsUs = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        recordingsRepository = RecordingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val modeStr = intent.getStringExtra(EXTRA_CAPTURE_MODE) ?: CaptureMode.STREAM_ONLY.name
                activeMode = CaptureMode.valueOf(modeStr)

                if (resultCode != 0 && resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isRunning) return
        isRunning = true
        activeConfig = settingsRepository.config.value
        startTimeMs = System.currentTimeMillis()
        streamBasePtsUs = -1L

        // Acquire partial wake lock for gaming session
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeloStream:CaptureWakeLock").apply {
                acquire(4 * 60 * 60 * 1000L) // 4 hours max
            }
        } catch (_: Exception) {}

        startForegroundServiceNotification()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpManager.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            _serviceStats.value = _serviceStats.value.copy(
                errorMessage = "Failed to obtain screen capture authorization."
            )
            stopCapture()
            stopSelf()
            return
        }
        mediaProjection = mp
        mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val shouldStream = activeMode == CaptureMode.STREAM_ONLY || activeMode == CaptureMode.STREAM_AND_RECORD
        val shouldRecord = activeMode == CaptureMode.RECORD_ONLY || activeMode == CaptureMode.STREAM_AND_RECORD

        _serviceStats.value = LiveStatistics(
            isStreaming = shouldStream,
            isRecording = shouldRecord,
            streamStatus = if (shouldStream) StreamStatus.CONNECTING else StreamStatus.IDLE,
            recordStatus = if (shouldRecord) RecordStatus.RECORDING else RecordStatus.IDLE,
            targetFps = activeConfig.fps,
            targetBitrateKbps = activeConfig.bitrateKbps,
            activeResolution = activeConfig.resolution.label
        )

        // Initialize Local Recorder if recording
        if (shouldRecord) {
            val isAudioExpected = activeConfig.audioSource != AudioSourceType.MUTED
            localRecorder = LocalRecorder(
                context = applicationContext,
                isAudioExpected = isAudioExpected,
                onRecordingFinished = { file, durationMs ->
                    Log.d(TAG, "Recording finished: ${file.absolutePath}, duration: ${durationMs}ms")
                    recordingsRepository.saveRecordedDuration(file.absolutePath, durationMs)
                    _serviceStats.value = _serviceStats.value.copy(
                        lastRecordedFilePath = file.absolutePath,
                        recordStatus = RecordStatus.IDLE
                    )
                },
                onError = { err ->
                    _serviceStats.value = _serviceStats.value.copy(
                        errorMessage = err,
                        recordStatus = RecordStatus.ERROR
                    )
                }
            ).apply {
                start()
            }
        }

        // Initialize Streaming Client (RTMP/RTMPS or YouTube HLS)
        if (shouldStream) {
            val isHevc = activeConfig.videoCodec == VideoCodec.H265
            if (activeConfig.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                hlsClient = YouTubeHlsClient(
                    onStatusChanged = { status ->
                        _serviceStats.value = _serviceStats.value.copy(
                            networkStatus = status,
                            streamStatus = if (status.startsWith("Live")) StreamStatus.STREAMING else StreamStatus.CONNECTING
                        )
                        updateNotification(status)
                    },
                    onError = { error ->
                        Log.e(TAG, "HLS error: $error")
                        _serviceStats.value = _serviceStats.value.copy(
                            networkStatus = "Error: $error",
                            streamStatus = StreamStatus.ERROR,
                            errorMessage = error
                        )
                        if (activeConfig.autoReconnect && isRunning) {
                            scheduleReconnect()
                        }
                    }
                )

                serviceScope.launch(Dispatchers.IO) {
                    hlsClient?.connectAndPublish(activeConfig.hlsStreamUrl, activeConfig.streamKey, isHevc)
                }
            } else {
                rtmpClient = RtmpClient(
                    onStatusChanged = { status ->
                        _serviceStats.value = _serviceStats.value.copy(
                            networkStatus = status,
                            streamStatus = if (status == "Live") StreamStatus.STREAMING else StreamStatus.CONNECTING
                        )
                        updateNotification(status)
                    },
                    onError = { error ->
                        Log.e(TAG, "RTMP error: $error")
                        _serviceStats.value = _serviceStats.value.copy(
                            networkStatus = "Error: $error",
                            streamStatus = StreamStatus.ERROR,
                            errorMessage = error
                        )
                        if (activeConfig.autoReconnect && isRunning) {
                            scheduleReconnect()
                        }
                    }
                )

                serviceScope.launch(Dispatchers.IO) {
                    rtmpClient?.connectAndPublish(activeConfig.rtmpUrl, activeConfig.streamKey)
                }
            }
        }

        val proj = mediaProjection ?: return

        // Initialize Video Pipeline
        videoPipeline = VideoEncoderPipeline(
            config = activeConfig,
            mediaProjection = proj,
            displayMetrics = displayMetrics,
            onVideoConfig = { format, sps, pps ->
                if (shouldStream) {
                    val isHevc = activeConfig.videoCodec == VideoCodec.H265
                    val client = rtmpClient
                    if (client != null) {
                        client.sendMetadata(
                            width = videoPipeline?.activeWidth ?: 1920,
                            height = videoPipeline?.activeHeight ?: 1080,
                            fps = activeConfig.fps,
                            videoBitrateKbps = activeConfig.bitrateKbps,
                            audioBitrateKbps = activeConfig.audioBitrateKbps,
                            isHevc = isHevc
                        )
                        if (isHevc) {
                            val vps = videoPipeline?.vpsBytes ?: ByteArray(0)
                            client.sendHevcSequenceHeader(vps, sps, pps)
                        } else {
                            client.sendAvcSequenceHeader(sps, pps)
                        }
                    }
                }
                if (shouldRecord) {
                    localRecorder?.onVideoFormatReady(format)
                }
            },
            onVideoSampleEncoded = { avccBytes, rawBuffer, bufferInfo, isKeyframe ->
                if (streamBasePtsUs == -1L) {
                    streamBasePtsUs = bufferInfo.presentationTimeUs
                }
                val ptsMs = maxOf(0L, (bufferInfo.presentationTimeUs - streamBasePtsUs) / 1000)
                if (shouldStream) {
                    val isHevc = activeConfig.videoCodec == VideoCodec.H265
                    if (activeConfig.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                        hlsClient?.sendVideo(rawBuffer, bufferInfo, isKeyframe, isHevc)
                    } else {
                        rtmpClient?.sendVideo(avccBytes, ptsMs, isKeyframe, isHevc)
                    }
                }
                if (shouldRecord) {
                    localRecorder?.writeVideoSample(rawBuffer, bufferInfo)
                }
            },
            onError = { error ->
                Log.e(TAG, "Video pipeline error: $error")
                _serviceStats.value = _serviceStats.value.copy(errorMessage = error)
            }
        ).apply {
            start()
        }

        // Initialize Audio Engine
        audioEngine = AudioCaptureEngine(
            context = applicationContext,
            audioSourceType = activeConfig.audioSource,
            audioInputDevice = activeConfig.audioInputDevice,
            accessibilityMicBypass = activeConfig.accessibilityMicBypass,
            audioBitrateKbps = activeConfig.audioBitrateKbps,
            micVolumeMultiplier = activeConfig.micVolume,
            internalVolumeMultiplier = activeConfig.internalAudioVolume,
            mediaProjection = proj,
            onAudioFormatConfigured = { format, ascBytes ->
                if (shouldStream) {
                    rtmpClient?.sendAacSequenceHeader(ascBytes)
                }
                if (shouldRecord) {
                    localRecorder?.onAudioFormatReady(format)
                }
            },
            onAudioSampleEncoded = { aacBytes, ptsMs ->
                val audioPtsUs = ptsMs * 1000
                if (streamBasePtsUs == -1L) {
                    streamBasePtsUs = audioPtsUs
                }
                val streamPtsMs = maxOf(0L, (audioPtsUs - streamBasePtsUs) / 1000)
                if (shouldStream) {
                    if (activeConfig.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                        hlsClient?.sendAudio(aacBytes, streamPtsMs)
                    } else {
                        rtmpClient?.sendAudio(aacBytes, streamPtsMs)
                    }
                }
                if (shouldRecord) {
                    val buf = ByteBuffer.wrap(aacBytes)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, aacBytes.size, audioPtsUs, 0)
                    }
                    localRecorder?.writeAudioSample(buf, info)
                }
            },
            onError = { error ->
                Log.w(TAG, "Audio error: $error")
                localRecorder?.onAudioFailed()
            }
        ).apply {
            val audioOk = start()
            if (!audioOk) {
                localRecorder?.onAudioFailed()
            }
        }

        startStatsLoop()

        // Launch floating overlay if enabled
        if (activeConfig.showFloatingOverlay) {
            try {
                startService(Intent(this, FloatingOverlayService::class.java))
            } catch (_: Exception) {}
        }
    }

    private fun scheduleReconnect() {
        serviceScope.launch {
            _serviceStats.value = _serviceStats.value.copy(streamStatus = StreamStatus.RECONNECTING)
            delay(activeConfig.reconnectDelaySeconds * 1000L)
            if (isRunning && activeMode != CaptureMode.RECORD_ONLY) {
                Log.d(TAG, "Attempting auto-reconnect...")
                rtmpClient?.disconnect()
                rtmpClient?.connectAndPublish(activeConfig.rtmpUrl, activeConfig.streamKey)
            }
        }
    }

    private fun startStatsLoop() {
        val intervalMs = if (activeConfig.lowEndDeviceMode) 2000L else 1000L
        statsJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(intervalMs)
                val runtime = Runtime.getRuntime()
                val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val durationSec = (System.currentTimeMillis() - startTimeMs) / 1000

                val video = videoPipeline
                val rtmp = rtmpClient
                val rec = localRecorder

                val encodingBitrate = video?.currentEncodingBitrateKbps ?: 0L
                val uploadBitrate = if (activeConfig.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                    hlsClient?.currentUploadSpeedKbps ?: 0L
                } else {
                    rtmp?.currentUploadSpeedKbps ?: 0L
                }
                val droppedFrames = if (activeConfig.streamingProtocol == StreamingProtocol.YOUTUBE_HLS) {
                    hlsClient?.droppedFramesCount?.get() ?: 0L
                } else {
                    rtmp?.droppedFramesCount?.get() ?: 0L
                }
                val recBitrate = rec?.calculateBitrateKbps() ?: 0L

                val activeBitrate = if (shouldStream) {
                    if (uploadBitrate > 0) uploadBitrate
                    else if (encodingBitrate > 0) encodingBitrate
                    else activeConfig.bitrateKbps.toLong()
                } else {
                    if (recBitrate > 0) recBitrate
                    else if (encodingBitrate > 0) encodingBitrate
                    else if (rec != null && rec.totalRecordedBytes > 0 && durationSec > 0) {
                        (rec.totalRecordedBytes * 8L) / (durationSec * 1000L)
                    } else {
                        activeConfig.bitrateKbps.toLong()
                    }
                }

                val finalRecBitrate = if (recBitrate > 0) recBitrate
                else if (encodingBitrate > 0) encodingBitrate
                else activeBitrate

                _serviceStats.value = _serviceStats.value.copy(
                    currentFps = if (video != null && video.currentFps > 0) video.currentFps else activeConfig.fps.toFloat(),
                    uploadBitrateKbps = uploadBitrate,
                    recordingBitrateKbps = finalRecBitrate,
                    activeBitrateKbps = activeBitrate,
                    droppedFrames = droppedFrames,
                    encodedFrames = video?.encodedFramesCount ?: 0L,
                    durationSeconds = durationSec,
                    encoderName = video?.activeEncoderName ?: "HW AVC",
                    memoryUsageMb = usedMemMb,
                    recordedBytes = rec?.totalRecordedBytes ?: 0L
                )

                if (durationSec % 3 == 0L) {
                    val m = durationSec / 60
                    val s = durationSec % 60
                    val timeStr = String.format("%02d:%02d", m, s)
                    val statusText = if (shouldStream && shouldRecord) {
                        "Streaming & Recording • ${String.format("%.1f", activeBitrate / 1000f)} Mbps • $timeStr"
                    } else if (shouldStream) {
                        "Streaming • ${String.format("%.1f", activeBitrate / 1000f)} Mbps • $timeStr"
                    } else {
                        "Recording • ${String.format("%.1f", finalRecBitrate / 1000f)} Mbps • $timeStr"
                    }
                    updateNotification(statusText)
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification("Initializing…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasMicPerm && (activeConfig.audioSource == AudioSourceType.MICROPHONE || activeConfig.audioSource == AudioSourceType.INTERNAL_PLUS_MIC)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (activeMode) {
            CaptureMode.STREAM_ONLY -> "VeloStream Live"
            CaptureMode.RECORD_ONLY -> "VeloStream Recording"
            CaptureMode.STREAM_AND_RECORD -> "VeloStream Live & Recording"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopCapture() {
        isRunning = false
        statsJob?.cancel()
        statsJob = null

        videoPipeline?.stop()
        videoPipeline = null

        audioEngine?.stop()
        audioEngine = null

        rtmpClient?.disconnect()
        rtmpClient = null

        hlsClient?.disconnect()
        hlsClient = null

        hlsClient?.disconnect()
        hlsClient = null

        localRecorder?.stop()
        localRecorder = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            stopService(Intent(this, FloatingOverlayService::class.java))
        } catch (_: Exception) {}

        _serviceStats.value = LiveStatistics(
            isStreaming = false,
            isRecording = false,
            streamStatus = StreamStatus.IDLE,
            recordStatus = RecordStatus.IDLE
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
