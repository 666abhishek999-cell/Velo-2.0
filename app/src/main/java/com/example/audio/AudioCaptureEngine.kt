package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.model.AudioInputDevice
import com.example.model.AudioSourceType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low latency Audio Capture Engine.
 * Supports:
 * 1. Internal Audio Playback Capture (Android 10+ via MediaProjection).
 * 2. Mobile Built-in Mic, Wired Headphones/Headset Mic, and Bluetooth Mic routing.
 * 3. Hardware Mono handling for Headphones and Bluetooth (automatically cloned to Stereo AAC).
 * 4. Game Mic Concurrency Bypass (VOICE_COMMUNICATION / Accessibility privilege for BGMI & game chat).
 */
class AudioCaptureEngine(
    private val context: Context,
    private val audioSourceType: AudioSourceType,
    private val audioInputDevice: AudioInputDevice = AudioInputDevice.AUTO,
    private val accessibilityMicBypass: Boolean = true,
    private val audioBitrateKbps: Int = 128,
    private val micVolumeMultiplier: Float = 1.0f,
    private val internalVolumeMultiplier: Float = 1.0f,
    private val mediaProjection: MediaProjection?,
    private val onAudioFormatConfigured: (MediaFormat, ByteArray) -> Unit,
    private val onAudioSampleEncoded: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioCaptureEngine"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_STEREO = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_CONFIG_MONO = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val OUTPUT_CHANNELS = 2
    }

    private val isRunning = AtomicBoolean(false)
    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioManager: AudioManager? = null
    private var bluetoothScoStarted = false

    private var workerThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (audioSourceType == AudioSourceType.MUTED) {
            Log.d(TAG, "Audio is muted by configuration.")
            return true
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val isMonoMic = audioInputDevice.isMono
        val micChannelConfig = if (isMonoMic) CHANNEL_CONFIG_MONO else CHANNEL_CONFIG_STEREO
        val minMicBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, micChannelConfig, AUDIO_FORMAT)
        val micBufferSize = maxOf(minMicBufSize, 4096 * 4)

        val minIntBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_STEREO, AUDIO_FORMAT)
        val intBufferSize = maxOf(minIntBufSize, 4096 * 4)

        // Setup microphone if requested
        if (audioSourceType == AudioSourceType.MICROPHONE || audioSourceType == AudioSourceType.INTERNAL_PLUS_MIC) {
            setupMicrophone(micChannelConfig, micBufferSize)
        }

        // Setup internal audio if requested
        if (audioSourceType == AudioSourceType.INTERNAL_AUDIO || audioSourceType == AudioSourceType.INTERNAL_PLUS_MIC) {
            setupInternalAudio(intBufferSize)
        }

        if (micRecord == null && internalRecord == null) {
            onError("Unable to initialize audio sources. Please check microphone permissions.")
            return false
        }

        // Initialize Stereo AAC Encoder
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, OUTPUT_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AAC audio encoder: ${e.message}")
            onError("Failed to start AAC encoder: ${e.message}")
            release()
            return false
        }

        isRunning.set(true)
        try {
            micRecord?.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting micRecord: ${e.message}")
        }
        try {
            internalRecord?.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting internalRecord: ${e.message}")
        }

        startAudioLoop(intBufferSize, isMonoMic)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun setupMicrophone(micChannelConfig: Int, bufferSize: Int) {
        // Start Bluetooth SCO if Bluetooth microphone is requested
        if (audioInputDevice == AudioInputDevice.BLUETOOTH) {
            try {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
                bluetoothScoStarted = true
                Log.d(TAG, "Started Bluetooth SCO for Bluetooth microphone routing")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start Bluetooth SCO: ${e.message}")
            }
        }

        // Audio source strategies:
        // When accessibilityMicBypass is enabled (for BGMI / gaming voice chat),
        // try VOICE_COMMUNICATION first because VoIP and games share communication focus!
        val audioSourcesToTry = if (accessibilityMicBypass) {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER
            )
        }

        for (source in audioSourcesToTry) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    micChannelConfig,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord = record
                    Log.d(TAG, "Microphone AudioRecord initialized with source=$source, channelConfig=$micChannelConfig")
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed initializing mic with source=$source: ${e.message}")
            }
        }

        // Configure preferred device on Android 6+ (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && micRecord != null) {
            routePreferredDevice(micRecord!!)
        }
    }

    private fun routePreferredDevice(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = audioManager ?: return
        val inputDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val targetDevice = when (audioInputDevice) {
            AudioInputDevice.BLUETOOTH -> inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            AudioInputDevice.HEADPHONES -> inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            AudioInputDevice.PHONE_MIC -> inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
            AudioInputDevice.AUTO -> null
        }

        if (targetDevice != null) {
            val success = record.setPreferredDevice(targetDevice)
            Log.d(TAG, "Set preferred audio device to ${targetDevice.productName} (type=${targetDevice.type}, success=$success)")
        }
    }

    private fun setupInternalAudio(bufferSize: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                internalRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (internalRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    internalRecord?.release()
                    internalRecord = null
                    Log.w(TAG, "Internal AudioRecord failed to initialize")
                } else {
                    Log.d(TAG, "Internal AudioRecord successfully initialized")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setup AudioPlaybackCapture: ${e.message}")
            }
        } else {
            Log.w(TAG, "Internal audio capture requires Android 10+ and active capture authorization.")
        }
    }

    private fun startAudioLoop(intBufferSize: Int, isMonoMic: Boolean) {
        workerThread = Thread({
            // Internal audio is always 2-channel Stereo (L, R)
            val stereoSamplesPerFrame = intBufferSize / 2
            val intShorts = ShortArray(stereoSamplesPerFrame)
            val mixedShorts = ShortArray(stereoSamplesPerFrame)
            val mixedBytes = ByteArray(stereoSamplesPerFrame * 2)

            // If mic is Mono, it produces half the sample count for the same duration
            val micShorts = if (isMonoMic) ShortArray(stereoSamplesPerFrame / 2) else ShortArray(stereoSamplesPerFrame)

            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get()) {
                val mic = micRecord
                val internal = internalRecord

                var micRead = 0
                var intRead = 0

                if (mic != null) {
                    micRead = mic.read(micShorts, 0, micShorts.size)
                }
                if (internal != null) {
                    intRead = internal.read(intShorts, 0, intShorts.size)
                }

                val hasMicData = micRead > 0
                val hasIntData = intRead > 0

                if (!hasMicData && !hasIntData) {
                    Thread.sleep(10)
                    continue
                }

                val frameCount = if (hasIntData) {
                    intRead / 2
                } else if (isMonoMic) {
                    micRead
                } else {
                    micRead / 2
                }

                val totalSamples = frameCount * 2
                val clampedTotalSamples = minOf(totalSamples, mixedShorts.size)

                // Mix samples:
                // If mic is MONO (Wired headphones or Bluetooth), duplicate mono mic to both Left and Right channels!
                for (f in 0 until (clampedTotalSamples / 2)) {
                    val micVal = if (hasMicData) {
                        if (isMonoMic) {
                            if (f < micRead) (micShorts[f] * micVolumeMultiplier).toInt() else 0
                        } else {
                            // Already stereo
                            0
                        }
                    } else 0

                    val intL = if (hasIntData && (f * 2) < intRead) (intShorts[f * 2] * internalVolumeMultiplier).toInt() else 0
                    val intR = if (hasIntData && (f * 2 + 1) < intRead) (intShorts[f * 2 + 1] * internalVolumeMultiplier).toInt() else 0

                    if (isMonoMic) {
                        // Blend mono mic equally into both L & R stereo channels
                        mixedShorts[f * 2] = (micVal + intL).coerceIn(-32768, 32767).toShort()
                        mixedShorts[f * 2 + 1] = (micVal + intR).coerceIn(-32768, 32767).toShort()
                    } else {
                        val micL = if (hasMicData && (f * 2) < micRead) (micShorts[f * 2] * micVolumeMultiplier).toInt() else 0
                        val micR = if (hasMicData && (f * 2 + 1) < micRead) (micShorts[f * 2 + 1] * micVolumeMultiplier).toInt() else 0
                        mixedShorts[f * 2] = (micL + intL).coerceIn(-32768, 32767).toShort()
                        mixedShorts[f * 2 + 1] = (micR + intR).coerceIn(-32768, 32767).toShort()
                    }
                }

                // Convert shorts to 16-bit PCM bytes (little endian)
                val byteCount = clampedTotalSamples * 2
                ByteBuffer.wrap(mixedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixedShorts, 0, clampedTotalSamples)

                // Feed into MediaCodec
                val encoder = audioEncoder ?: break
                val inputIndex = encoder.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    val inputBuf = encoder.getInputBuffer(inputIndex)
                    if (inputBuf != null) {
                        inputBuf.clear()
                        inputBuf.put(mixedBytes, 0, byteCount)
                        val presentationTimeUs = System.nanoTime() / 1000
                        encoder.queueInputBuffer(inputIndex, 0, byteCount, presentationTimeUs, 0)
                    }
                }

                // Drain output from MediaCodec
                drainEncoder(encoder, bufferInfo)
            }
        }, "VeloStream-AudioLoop").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun drainEncoder(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (isRunning.get()) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val bytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(bytes)

                    if (isConfig) {
                        Log.d(TAG, "Audio encoder output config: ${bytes.size} bytes")
                        val format = encoder.outputFormat
                        onAudioFormatConfigured(format, bytes)
                    } else {
                        val ptsMs = bufferInfo.presentationTimeUs / 1000
                        onAudioSampleEncoded(bytes, ptsMs)
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = encoder.outputFormat
                val csd0 = format.getByteBuffer("csd-0")
                if (csd0 != null) {
                    val asc = ByteArray(csd0.remaining())
                    csd0.get(asc)
                    csd0.rewind()
                    onAudioFormatConfigured(format, asc)
                }
            } else {
                break
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (_: Exception) {}
        workerThread = null
        release()
    }

    private fun release() {
        try {
            micRecord?.stop()
            micRecord?.release()
        } catch (_: Exception) {}
        micRecord = null

        try {
            internalRecord?.stop()
            internalRecord?.release()
        } catch (_: Exception) {}
        internalRecord = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (_: Exception) {}
        audioEncoder = null

        if (bluetoothScoStarted) {
            try {
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
            } catch (_: Exception) {}
            bluetoothScoStarted = false
        }
    }
}
