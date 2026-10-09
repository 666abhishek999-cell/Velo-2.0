package com.example.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.encoder.EncoderDetector
import com.example.model.AspectRatioMode
import com.example.model.BitrateModeType
import com.example.model.ScreenOrientationMode
import com.example.model.StreamConfig
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoderPipeline(
    private val config: StreamConfig,
    private val mediaProjection: MediaProjection,
    private val displayMetrics: DisplayMetrics,
    private val onVideoConfig: (MediaFormat, ByteArray, ByteArray) -> Unit,
    private val onVideoSampleEncoded: (ByteArray, ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "VideoEncoderPipeline"
    }

    private val isRunning = AtomicBoolean(false)
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    private var glRenderer: GlStretchRenderer? = null

    var activeWidth: Int = 1920
        private set
    var activeHeight: Int = 1080
        private set
    var captureWidth: Int = 1920
        private set
    var captureHeight: Int = 1080
        private set
    var activeEncoderName: String = "HW AVC"
        private set

    var vpsBytes: ByteArray? = null
        private set
    var spsBytes: ByteArray? = null
        private set
    var ppsBytes: ByteArray? = null
        private set

    // Real-time FPS and encoding bitrate calculation
    @Volatile var currentFps: Float = 0f
        private set
    @Volatile var currentEncodingBitrateKbps: Long = 0L
        private set
    var encodedFramesCount: Long = 0L
        private set

    fun start(): Boolean {
        try {
            calculateDimensions()

            // 1. Check if user specified a concrete encoder name (e.g. OMX.qcom.video.encoder.avc or c2.qti.avc.encoder)
            val specificEncoder = if (config.encoderChoice != "AUTO" &&
                config.encoderChoice != "HARDWARE_AVC" &&
                config.encoderChoice != "HARDWARE_HEVC" &&
                config.encoderChoice.isNotBlank()
            ) {
                EncoderDetector.getAllVideoEncoders().firstOrNull { it.name.equals(config.encoderChoice, ignoreCase = true) }
            } else null

            // 2. Derive MIME type from the specific encoder or configuration
            val mimeType = when {
                specificEncoder != null -> {
                    EncoderDetector.getMimeForEncoder(specificEncoder.name)
                        ?: if (config.videoCodec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                }
                config.videoCodec == VideoCodec.H265 || config.encoderChoice == "HARDWARE_HEVC" -> MediaFormat.MIMETYPE_VIDEO_HEVC
                config.videoCodec == VideoCodec.AV1 -> "video/av01"
                else -> MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val encoderInfo = specificEncoder
                ?: if (config.encoderChoice == "HARDWARE_HEVC" || config.videoCodec == VideoCodec.H265) {
                    EncoderDetector.findBestEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, preferHardware = true)
                        ?: EncoderDetector.findBestEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, preferHardware = true)
                } else if (config.encoderChoice == "HARDWARE_AVC") {
                    EncoderDetector.findBestEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, preferHardware = true)
                } else {
                    EncoderDetector.findBestEncoder(mimeType, preferHardware = !config.lowEndDeviceMode)
                        ?: EncoderDetector.findBestEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, preferHardware = true)
                }

            if (encoderInfo == null) {
                val fallbackAvc = EncoderDetector.findBestEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, preferHardware = true)
                if (fallbackAvc == null) {
                    onError("No compatible video encoder found on device")
                    return false
                }
            }

            activeEncoderName = encoderInfo?.name ?: (if (config.encoderChoice.isNotBlank() && config.encoderChoice != "AUTO") config.encoderChoice else "HW AVC")
            Log.d(TAG, "Selected encoder: $activeEncoderName ($mimeType) for ${activeWidth}x${activeHeight} @ ${config.fps}fps")

            val format = MediaFormat.createVideoFormat(mimeType, activeWidth, activeHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.gopSeconds)

                // Bitrate mode (CBR / VBR) - HEVC hardware encoders prefer VBR
                val bitrateMode = if (config.bitrateMode == BitrateModeType.CBR && mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
                setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)

                // Profiles
                if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    try {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                    } catch (_: Exception) {}
                } else if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    try {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    } catch (_: Exception) {}
                }

                // Instruct hardware encoder to repeat previous frame if screen has no changes
                try {
                    val repeatIntervalUs = 1_000_000L / config.fps.coerceAtLeast(15)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatIntervalUs)
                } catch (_: Exception) {}
            }

            var encoder: MediaCodec? = null
            try {
                encoder = MediaCodec.createByCodecName(activeEncoderName)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "Configuring $activeEncoderName failed: ${e.message}. Trying generic mime: $mimeType")
                try { encoder?.release() } catch (_: Exception) {}
                try {
                    encoder = MediaCodec.createEncoderByType(mimeType)
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    activeEncoderName = encoder.name
                } catch (e2: Exception) {
                    Log.w(TAG, "Generic encoder failed: ${e2.message}. Falling back to H.264/AVC...")
                    try { encoder?.release() } catch (_: Exception) {}
                    val avcFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, activeWidth, activeHeight).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.gopSeconds)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    encoder.configure(avcFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    activeEncoderName = encoder.name
                }
            }

            inputSurface = encoder.createInputSurface()
            encoder.start()
            videoEncoder = encoder

            val surface = inputSurface ?: throw IllegalStateException("Input surface is null")

            try {
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped (pipeline callback)")
                        stop()
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection callback notice: ${e.message}")
            }

            var displaySurface = surface
            var vDisplayW = activeWidth
            var vDisplayH = activeHeight

            // Initialize OpenGL stretch renderer for true zero-black-bar stretching
            val renderer = GlStretchRenderer(
                encoderSurface = surface,
                outputWidth = activeWidth,
                outputHeight = activeHeight,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                aspectRatioMode = config.aspectRatioMode,
                targetFps = config.fps
            )
            if (renderer.start()) {
                glRenderer = renderer
                val glSurface = renderer.captureSurface
                if (glSurface != null) {
                    displaySurface = glSurface
                    vDisplayW = captureWidth
                    vDisplayH = captureHeight
                    Log.d(TAG, "Using GlStretchRenderer: capture ${captureWidth}x${captureHeight} -> output ${activeWidth}x${activeHeight} ($config.aspectRatioMode)")
                }
            } else {
                Log.w(TAG, "GlStretchRenderer initialization failed, falling back to direct surface")
                renderer.release()
            }

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VeloStream-Display",
                vDisplayW,
                vDisplayH,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                displaySurface,
                null,
                Handler(Looper.getMainLooper())
            )

            isRunning.set(true)
            startDrainLoop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Video encoder pipeline failed: ${e.message}", e)
            onError("Encoder error: ${e.message}")
            stop()
            return false
        }
    }

    private fun calculateDimensions() {
        val displayW = displayMetrics.widthPixels
        val displayH = displayMetrics.heightPixels

        // Determine orientation
        val isTargetLandscape = when (config.orientationMode) {
            ScreenOrientationMode.LANDSCAPE -> true
            ScreenOrientationMode.PORTRAIT -> false
            ScreenOrientationMode.AUTO -> displayW >= displayH
        }

        // Native screen capture dimensions (matching physical display aspect ratio to eliminate OS letterboxing)
        val rawCapW = if (isTargetLandscape) maxOf(displayW, displayH) else minOf(displayW, displayH)
        val rawCapH = if (isTargetLandscape) minOf(displayW, displayH) else maxOf(displayW, displayH)

        // Ensure capture dimensions are multiples of 2 and capped at 2560 for GPU performance
        val maxDim = maxOf(rawCapW, rawCapH)
        val capScale = if (maxDim > 2560) 2560f / maxDim else 1.0f
        captureWidth = (((rawCapW * capScale).toInt() + 1) / 2) * 2
        captureHeight = (((rawCapH * capScale).toInt() + 1) / 2) * 2

        val baseH = if (config.resolution == VideoResolution.RES_NATIVE) {
            minOf(displayW, displayH)
        } else {
            config.resolution.height
        }

        when (config.aspectRatioMode) {
            AspectRatioMode.STRETCH_16_9, AspectRatioMode.FIT_16_9 -> {
                // Strict 16:9 standard resolution - stretches tablets (3:2, 4:3, 7:5) directly into 16:9!
                val w16x9 = (baseH * 16) / 9
                // Round to multiple of 16 for hardware encoder macroblock alignment
                val alignedW = ((w16x9 + 15) / 16) * 16
                val alignedH = ((baseH + 15) / 16) * 16

                if (isTargetLandscape) {
                    activeWidth = maxOf(alignedW, alignedH)
                    activeHeight = minOf(alignedW, alignedH)
                } else {
                    activeWidth = minOf(alignedW, alignedH)
                    activeHeight = maxOf(alignedW, alignedH)
                }
            }
            AspectRatioMode.NATIVE -> {
                // Preserves physical display ratio
                val larger = maxOf(displayW, displayH)
                val smaller = minOf(displayW, displayH)
                val ratio = larger.toDouble() / smaller.toDouble()

                val alignedH = ((baseH + 15) / 16) * 16
                val calculatedW = (alignedH * ratio).toInt()
                val alignedW = ((calculatedW + 15) / 16) * 16

                if (isTargetLandscape) {
                    activeWidth = alignedW
                    activeHeight = alignedH
                } else {
                    activeWidth = alignedH
                    activeHeight = alignedW
                }
            }
        }
    }

    private fun startDrainLoop() {
        drainThread = Thread({
            val encoder = videoEncoder ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()

            var fpsFramesCount = 0
            var lastFpsCalcTime = System.currentTimeMillis()
            var bytesInCurrentSec = 0L
            var lastBitrateCalcTime = System.currentTimeMillis()

            while (isRunning.get()) {
                val outputIndex = try {
                    encoder.dequeueOutputBuffer(bufferInfo, 10000)
                } catch (e: Exception) {
                    break
                }

                // Check periodic FPS and Bitrate calculation on every pass so telemetry updates reliably
                val now = System.currentTimeMillis()
                val fpsDelta = now - lastFpsCalcTime
                if (fpsDelta >= 1000) {
                    currentFps = (fpsFramesCount * 1000f) / fpsDelta
                    fpsFramesCount = 0
                    lastFpsCalcTime = now
                }
                val bitrateDelta = now - lastBitrateCalcTime
                if (bitrateDelta >= 1000) {
                    if (bytesInCurrentSec > 0) {
                        currentEncodingBitrateKbps = (bytesInCurrentSec * 8000L) / (bitrateDelta * 1000L)
                    }
                    bytesInCurrentSec = 0L
                    lastBitrateCalcTime = now
                }

                if (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            parseSpsPps(outputBuffer, bufferInfo)
                        } else {
                            // Extract AVCC format for RTMP (replace Annex B start codes with 4-byte length)
                            val avccPacket = convertAnnexBToAvcc(outputBuffer, bufferInfo)

                            // Reset buffer position for raw MediaMuxer recording
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            encodedFramesCount++
                            fpsFramesCount++
                            bytesInCurrentSec += bufferInfo.size

                            onVideoSampleEncoded(avccPacket, outputBuffer, bufferInfo, isKeyframe)
                        }
                    }
                    try {
                        encoder.releaseOutputBuffer(outputIndex, false)
                    } catch (_: Exception) {}
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    Log.d(TAG, "Video encoder output format changed: $newFormat")
                    if (isH265) {
                        val allCsdNals = mutableListOf<ByteArray>()
                        for (csdKey in listOf("csd-0", "csd-1", "csd-2")) {
                            val csdBuf = try { newFormat.getByteBuffer(csdKey) } catch (_: Exception) { null }
                            if (csdBuf != null && csdBuf.remaining() > 0) {
                                val bytes = ByteArray(csdBuf.remaining()).also { csdBuf.get(it); csdBuf.rewind() }
                                val split = splitNalUnits(bytes)
                                if (split.isNotEmpty()) {
                                    allCsdNals.addAll(split)
                                } else {
                                    allCsdNals.add(bytes)
                                }
                            }
                        }
                        for (rawNal in allCsdNals) {
                            val nal = stripStartCode(rawNal)
                            if (nal.isNotEmpty()) {
                                val type = (nal[0].toInt() shr 1) and 0x3F
                                when (type) {
                                    32 -> vpsBytes = nal
                                    33 -> spsBytes = nal
                                    34 -> ppsBytes = nal
                                }
                            }
                        }
                        if (spsBytes != null) {
                            onVideoConfig(newFormat, spsBytes!!, ppsBytes ?: ByteArray(0))
                        }
                    } else {
                        val csd0 = newFormat.getByteBuffer("csd-0")
                        val csd1 = newFormat.getByteBuffer("csd-1")
                        if (csd0 != null && csd1 != null) {
                            val sps = ByteArray(csd0.remaining()).also { csd0.get(it); csd0.rewind() }
                            val pps = ByteArray(csd1.remaining()).also { csd1.get(it); csd1.rewind() }
                            spsBytes = stripStartCode(sps)
                            ppsBytes = stripStartCode(pps)
                            onVideoConfig(newFormat, spsBytes!!, ppsBytes!!)
                        } else if (csd0 != null) {
                            val bytes = ByteArray(csd0.remaining()).also { csd0.get(it); csd0.rewind() }
                            val nals = splitNalUnits(bytes)
                            for (rawNal in nals) {
                                val nal = stripStartCode(rawNal)
                                if (nal.isNotEmpty()) {
                                    val type = nal[0].toInt() and 0x1F
                                    if (type == 7) spsBytes = nal
                                    if (type == 8) ppsBytes = nal
                                }
                            }
                            if (spsBytes != null) {
                                onVideoConfig(newFormat, spsBytes!!, ppsBytes ?: ByteArray(0))
                            }
                        }
                    }
                }
            }
        }, "VeloStream-VideoDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun parseSpsPps(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val bytes = ByteArray(bufferInfo.size)
        val origPos = buffer.position()
        buffer.get(bytes)
        buffer.position(origPos)

        val nals = splitNalUnits(bytes)
        for (rawNal in nals) {
            val nal = stripStartCode(rawNal)
            if (nal.isNotEmpty()) {
                if (isH265) {
                    val h265Type = (nal[0].toInt() shr 1) and 0x3F
                    if (h265Type == 32) vpsBytes = nal
                    else if (h265Type == 33) spsBytes = nal
                    else if (h265Type == 34) ppsBytes = nal
                } else {
                    val h264Type = nal[0].toInt() and 0x1F
                    if (h264Type == 7) spsBytes = nal
                    else if (h264Type == 8) ppsBytes = nal
                }
            }
        }
        val format = videoEncoder?.outputFormat ?: MediaFormat()
        onVideoConfig(format, spsBytes ?: ByteArray(0), ppsBytes ?: ByteArray(0))
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        var start = 0
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            start = 4
        } else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
            start = 3
        }
        return if (start > 0) nal.copyOfRange(start, nal.size) else nal
    }

    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i <= data.size - 3) {
            val is3Byte = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val is4Byte = i <= data.size - 4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (is4Byte || is3Byte) {
                val prefixLen = if (is4Byte) 4 else 3
                if (start != -1 && i > start) {
                    list.add(data.copyOfRange(start, i))
                }
                start = i + prefixLen
                i += prefixLen
            } else {
                i++
            }
        }
        if (start != -1 && start < data.size) {
            list.add(data.copyOfRange(start, data.size))
        }
        return list
    }

    private fun convertAnnexBToAvcc(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): ByteArray {
        val bytes = ByteArray(bufferInfo.size)
        buffer.get(bytes)
        val nals = splitNalUnits(bytes)

        if (nals.isEmpty() && bytes.isNotEmpty()) {
            return bytes
        }

        var totalSize = 0
        for (nal in nals) {
            totalSize += 4 + nal.size
        }

        val avcc = ByteBuffer.allocate(totalSize)
        for (nal in nals) {
            avcc.putInt(nal.size)
            avcc.put(nal)
        }
        return avcc.array()
    }

    fun stop() {
        isRunning.set(false)
        try {
            drainThread?.interrupt()
            drainThread?.join(500)
        } catch (_: Exception) {}
        drainThread = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            glRenderer?.release()
        } catch (_: Exception) {}
        glRenderer = null

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (_: Exception) {}
        videoEncoder = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}
