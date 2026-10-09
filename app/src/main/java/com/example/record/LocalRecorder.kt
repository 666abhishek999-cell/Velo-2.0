package com.example.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LocalRecorder(
    private val context: Context,
    private val isAudioExpected: Boolean,
    private val onRecordingFinished: (File, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "LocalRecorder"
        private const val MAX_PENDING_SAMPLES = 45
    }

    private var mediaMuxer: MediaMuxer? = null
    private var outputFile: File? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val isMuxerStarted = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)

    private val writeLock = Any()
    @Volatile var totalRecordedBytes: Long = 0L
        private set

    // Independent PTS management (zero-based, strictly monotonic for each track)
    private var videoBasePtsUs = -1L
    private var audioBasePtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    private var videoSamplesWritten = 0
    private var audioSamplesWritten = 0
    private var recordingStartTimeMs = 0L

    // Live recording bitrate calculation
    private var lastRecordedBytesSnapshot = 0L
    private var lastRecordedTimeSnapshot = System.currentTimeMillis()
    @Volatile var currentBitrateKbps: Long = 0L
        private set

    private data class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val bufferInfo: MediaCodec.BufferInfo
    )
    private val pendingSamples = mutableListOf<PendingSample>()

    fun start(): Boolean {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "VeloStream_$timestamp.mp4"

            // Save directly to the public Movies/VeloStream directory for instant access in any File Manager
            val publicMovies = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VeloStream")
            val moviesDir = try {
                if (!publicMovies.exists()) publicMovies.mkdirs()
                if (publicMovies.exists() && publicMovies.canWrite()) publicMovies else null
            } catch (_: Exception) { null }
                ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(context.filesDir, "movies").apply { mkdirs() }

            val file = File(moviesDir, filename)
            outputFile = file

            videoBasePtsUs = -1L
            audioBasePtsUs = -1L
            lastVideoPtsUs = -1L
            lastAudioPtsUs = -1L
            videoSamplesWritten = 0
            audioSamplesWritten = 0
            totalRecordedBytes = 0L
            lastRecordedBytesSnapshot = 0L
            lastRecordedTimeSnapshot = System.currentTimeMillis()
            currentBitrateKbps = 0L
            recordingStartTimeMs = System.currentTimeMillis()
            videoTrackIndex = -1
            audioTrackIndex = -1
            pendingSamples.clear()

            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRecording.set(true)
            Log.d(TAG, "Recording started -> ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer: ${e.message}", e)
            onError("Failed to start recorder: ${e.message}")
            return false
        }
    }

    fun calculateBitrateKbps(): Long {
        val now = System.currentTimeMillis()
        val deltaMs = now - lastRecordedTimeSnapshot
        if (deltaMs >= 750) {
            val bytes = totalRecordedBytes - lastRecordedBytesSnapshot
            if (bytes > 0 && deltaMs > 0) {
                currentBitrateKbps = (bytes * 8000L) / (deltaMs * 1000L)
            }
            lastRecordedBytesSnapshot = totalRecordedBytes
            lastRecordedTimeSnapshot = now
        }
        return currentBitrateKbps
    }

    fun onVideoFormatReady(format: MediaFormat) {
        synchronized(writeLock) {
            if (!isRecording.get() || isMuxerStarted.get()) return
            val muxer = mediaMuxer ?: return
            try {
                if (videoTrackIndex < 0) {
                    videoTrackIndex = muxer.addTrack(format)
                    Log.d(TAG, "Video track added: $videoTrackIndex")
                    checkStartMuxer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add video track", e)
            }
        }
    }

    fun onAudioFormatReady(format: MediaFormat) {
        synchronized(writeLock) {
            if (!isRecording.get() || isMuxerStarted.get()) return
            val muxer = mediaMuxer ?: return
            try {
                if (audioTrackIndex < 0) {
                    audioTrackIndex = muxer.addTrack(format)
                    Log.d(TAG, "Audio track added: $audioTrackIndex")
                    checkStartMuxer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add audio track", e)
            }
        }
    }

    fun onAudioFailed() {
        synchronized(writeLock) {
            if (isAudioExpected && !isMuxerStarted.get()) {
                Log.d(TAG, "Audio format unavailable, starting muxer with video track only")
                checkStartMuxer(forceWithoutAudio = true)
            }
        }
    }

    private fun checkStartMuxer(forceWithoutAudio: Boolean = false) {
        val muxer = mediaMuxer ?: return
        if (isMuxerStarted.get()) return
        if (videoTrackIndex >= 0) {
            if (!isAudioExpected || audioTrackIndex >= 0 || forceWithoutAudio) {
                try {
                    muxer.start()
                    isMuxerStarted.set(true)
                    Log.d(TAG, "MediaMuxer started (video=$videoTrackIndex, audio=$audioTrackIndex)")
                    flushPendingSamples()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaMuxer: ${e.message}")
                }
            }
        }
    }

    fun writeVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isRecording.get()) return
        synchronized(writeLock) {
            if (!isMuxerStarted.get()) {
                // Buffer early video frames while waiting for audio format
                if (pendingSamples.size < MAX_PENDING_SAMPLES) {
                    val bytes = ByteArray(bufferInfo.size)
                    val pos = buffer.position()
                    buffer.get(bytes)
                    buffer.position(pos)
                    val copyInfo = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    pendingSamples.add(PendingSample(true, bytes, copyInfo))
                } else {
                    // Audio timed out, proceed with video only
                    Log.w(TAG, "Audio track timed out after $MAX_PENDING_SAMPLES frames; starting video-only muxer")
                    checkStartMuxer(forceWithoutAudio = true)
                }
                return
            }

            writeSampleInternal(videoTrackIndex, buffer, bufferInfo, isVideo = true)
        }
    }

    fun writeAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isRecording.get()) return
        synchronized(writeLock) {
            if (!isMuxerStarted.get()) {
                if (pendingSamples.size < MAX_PENDING_SAMPLES) {
                    val bytes = ByteArray(bufferInfo.size)
                    val pos = buffer.position()
                    buffer.get(bytes)
                    buffer.position(pos)
                    val copyInfo = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    pendingSamples.add(PendingSample(false, bytes, copyInfo))
                }
                return
            }

            if (audioTrackIndex >= 0) {
                writeSampleInternal(audioTrackIndex, buffer, bufferInfo, isVideo = false)
            }
        }
    }

    private fun writeSampleInternal(
        trackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        isVideo: Boolean
    ) {
        if (trackIndex < 0 || !isMuxerStarted.get()) return
        try {
            val originalPts = bufferInfo.presentationTimeUs
            val ptsUs: Long

            if (isVideo) {
                if (videoBasePtsUs == -1L) {
                    videoBasePtsUs = originalPts
                }
                var calculated = originalPts - videoBasePtsUs
                if (calculated < 0L) calculated = 0L
                if (calculated <= lastVideoPtsUs) {
                    calculated = lastVideoPtsUs + 1000L // Ensure strictly monotonic increase
                }
                lastVideoPtsUs = calculated
                ptsUs = calculated
                videoSamplesWritten++
            } else {
                if (audioBasePtsUs == -1L) {
                    audioBasePtsUs = originalPts
                }
                var calculated = originalPts - audioBasePtsUs
                if (calculated < 0L) calculated = 0L
                if (calculated <= lastAudioPtsUs) {
                    calculated = lastAudioPtsUs + 500L // Ensure strictly monotonic increase
                }
                lastAudioPtsUs = calculated
                ptsUs = calculated
                audioSamplesWritten++
            }

            val normalizedInfo = MediaCodec.BufferInfo().apply {
                set(bufferInfo.offset, bufferInfo.size, ptsUs, bufferInfo.flags)
            }
            mediaMuxer?.writeSampleData(trackIndex, buffer, normalizedInfo)
            totalRecordedBytes += bufferInfo.size
        } catch (e: Exception) {
            Log.w(TAG, "Error writing ${if (isVideo) "video" else "audio"} sample: ${e.message}")
        }
    }

    private fun flushPendingSamples() {
        for (sample in pendingSamples) {
            val track = if (sample.isVideo) videoTrackIndex else audioTrackIndex
            if (track >= 0) {
                val buf = ByteBuffer.wrap(sample.data)
                writeSampleInternal(track, buf, sample.bufferInfo, sample.isVideo)
            }
        }
        pendingSamples.clear()
    }

    fun stop(): File? {
        isRecording.set(false)
        val started = isMuxerStarted.getAndSet(false)
        val wallClockDurationMs = if (recordingStartTimeMs > 0) {
            maxOf(0L, System.currentTimeMillis() - recordingStartTimeMs)
        } else 0L

        synchronized(writeLock) {
            pendingSamples.clear()
            val muxer = mediaMuxer
            mediaMuxer = null
            if (muxer != null) {
                if (started && videoSamplesWritten > 0) {
                    // Prevent MPEG4Writer: Stop() called but track is not started error:
                    // If audio track was added but 0 audio samples were written, write a dummy silent sample
                    if (audioTrackIndex >= 0 && audioSamplesWritten == 0) {
                        try {
                            val dummyAudio = byteArrayOf(0x21, 0x10.toByte(), 0x04.toByte(), 0x60.toByte())
                            val dummyBuf = ByteBuffer.wrap(dummyAudio)
                            val dummyInfo = MediaCodec.BufferInfo().apply {
                                set(0, dummyAudio.size, 0L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            }
                            muxer.writeSampleData(audioTrackIndex, dummyBuf, dummyInfo)
                            audioSamplesWritten++
                        } catch (e: Exception) {
                            Log.w(TAG, "Fallback dummy audio packet: ${e.message}")
                        }
                    }

                    try {
                        muxer.stop()
                        Log.d(TAG, "MediaMuxer stopped successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception stopping muxer: ${e.message}")
                    }
                } else if (started) {
                    Log.w(TAG, "Muxer started but 0 video samples written; skipping muxer.stop() to prevent MPEG4Writer track error")
                }
                try {
                    muxer.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception releasing muxer: ${e.message}")
                }
            }
        }

        val file = outputFile
        outputFile = null

        if (file != null && file.exists() && file.length() > 1024 && (videoSamplesWritten > 0 || totalRecordedBytes > 0)) {
            // Determine the accurate video duration:
            var finalDurationMs = 0L

            // 1. Query MediaMetadataRetriever directly from the finalized MP4 file header
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val retrievedMs = durStr?.toLongOrNull() ?: 0L
                retriever.release()
                if (retrievedMs > 0) {
                    finalDurationMs = retrievedMs
                    Log.d(TAG, "Retrieved MP4 header duration: ${finalDurationMs}ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever duration check failed: ${e.message}")
            }

            // 2. If retriever couldn't extract duration, calculate from presentation timestamps
            if (finalDurationMs <= 0L) {
                val ptsDurationMs = maxOf(lastVideoPtsUs / 1000L, lastAudioPtsUs / 1000L)
                finalDurationMs = if (ptsDurationMs > 0) ptsDurationMs else wallClockDurationMs
                Log.d(TAG, "Calculated PTS/Wall-clock duration: ${finalDurationMs}ms")
            }

            // 3. Guarantee duration is realistic based on wall clock time
            if (wallClockDurationMs >= 1000L && finalDurationMs < 500L) {
                finalDurationMs = wallClockDurationMs
            }

            exportToMediaStore(file)
            onRecordingFinished(file, finalDurationMs)
            return file
        } else {
            try {
                if (file?.exists() == true) file.delete()
            } catch (_: Exception) {}
            return null
        }
    }

    private fun exportToMediaStore(file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.d(TAG, "Scanned into MediaStore: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner failed: ${e.message}")
        }
    }
}
