package com.example.hls

import android.media.MediaCodec
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/**
 * High-performance YouTube Live HLS Ingestion Client.
 * Implements HTTP Live Streaming ingestion via HTTP POST for YouTube.
 * Natively packages H.264/AVC or H.265/HEVC video and AAC audio into standard 188-byte MPEG-TS segments.
 */
class YouTubeHlsClient(
    private val onStatusChanged: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "YouTubeHlsClient"
        private const val TS_PACKET_SIZE = 188
        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val PID_AUDIO = 0x0101

        private const val STREAM_TYPE_AVC = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val STREAM_TYPE_AAC = 0x0F
    }

    private val isPublishing = AtomicBoolean(false)
    private var uploaderThread: Thread? = null
    private val segmentQueue = LinkedBlockingQueue<HlsSegment>(10)

    val droppedFramesCount = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    @Volatile var currentUploadSpeedKbps: Long = 0L
        private set
    private var lastSpeedCheckTime = System.currentTimeMillis()
    private var lastBytesCount = 0L

    private var activeStreamKey = ""
    private var baseEndpoint = "https://a.upload.youtube.com/http_upload_hls?cid="

    // MPEG-TS segment builder state
    private val currentSegmentBuffer = ByteArrayOutputStream(1024 * 512)
    private var segmentIndex = 0
    private var segmentStartTimeMs = -1L
    private var isHevcStream = false

    private val recentSegments = java.util.concurrent.CopyOnWriteArrayList<HlsSegment>()

    // Continuity counters (0..15)
    private var patContinuity = 0
    private var pmtContinuity = 0
    private var videoContinuity = 0
    private var audioContinuity = 0

    data class HlsSegment(
        val index: Int,
        val data: ByteArray,
        val durationMs: Long
    )

    fun isLive(): Boolean = isPublishing.get()

    fun connectAndPublish(serverUrl: String, streamKey: String, isHevc: Boolean = false): Boolean {
        try {
            onStatusChanged("Connecting to YouTube HLS…")
            activeStreamKey = streamKey.trim()
            baseEndpoint = if (serverUrl.isNotBlank() && serverUrl.startsWith("http", ignoreCase = true)) {
                serverUrl.trim()
            } else {
                "https://a.upload.youtube.com/http_upload_hls?cid="
            }
            isHevcStream = isHevc

            segmentIndex = 0
            segmentStartTimeMs = -1L
            patContinuity = 0
            pmtContinuity = 0
            videoContinuity = 0
            audioContinuity = 0
            currentSegmentBuffer.reset()
            segmentQueue.clear()
            recentSegments.clear()

            isPublishing.set(true)
            startUploaderThread()
            onStatusChanged("Live (YouTube HLS)")
            Log.d(TAG, "YouTube HLS Ingestion active: endpoint=$baseEndpoint, isHevc=$isHevc")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HLS client: ${e.message}", e)
            onError("HLS start error: ${e.message}")
            return false
        }
    }

    private fun startUploaderThread() {
        uploaderThread = Thread({
            while (isPublishing.get()) {
                val segment = try {
                    segmentQueue.take()
                } catch (_: InterruptedException) {
                    break
                }

                uploadSegmentToYouTube(segment)
                updateSpeed()
            }
        }, "VeloStream-HLS-Uploader").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun uploadSegmentToYouTube(segment: HlsSegment) {
        val segmentFilename = "live_${segment.index}.ts"
        val targetUrlStr = buildFileUrl(segmentFilename)
        var connection: HttpsURLConnection? = null
        try {
            val url = URL(targetUrlStr)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "video/MP2T")
                setRequestProperty("Connection", "Keep-Alive")
                setFixedLengthStreamingMode(segment.data.size)
            }

            connection.outputStream.use { out ->
                out.write(segment.data)
                out.flush()
            }

            val responseCode = connection.responseCode
            totalBytesSent.addAndGet(segment.data.size.toLong())

            if (responseCode in 200..299) {
                Log.d(TAG, "Uploaded HLS segment #${segment.index} (${segment.data.size} bytes, HTTP $responseCode)")
                recentSegments.add(segment)
                while (recentSegments.size > 5) {
                    recentSegments.removeAt(0)
                }
                uploadPlaylistToYouTube()
            } else {
                Log.w(TAG, "YouTube HLS upload response: HTTP $responseCode for segment #${segment.index}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS Segment upload error: ${e.message}")
            droppedFramesCount.incrementAndGet()
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun uploadPlaylistToYouTube() {
        if (recentSegments.isEmpty()) return
        val playlistFilename = "live.m3u8"
        val playlistUrl = buildFileUrl(playlistFilename)

        val targetDurationSec = 4
        val mediaSequence = recentSegments.first().index
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$targetDurationSec\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
        for (seg in recentSegments) {
            val dur = String.format(java.util.Locale.US, "%.3f", seg.durationMs / 1000.0)
            sb.append("#EXTINF:$dur,\n")
            sb.append("live_${seg.index}.ts\n")
        }

        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        var connection: HttpsURLConnection? = null
        try {
            val url = URL(playlistUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/x-mpegURL")
                setRequestProperty("Connection", "Keep-Alive")
                setFixedLengthStreamingMode(bytes.size)
            }
            connection.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }
            val code = connection.responseCode
            Log.d(TAG, "Uploaded live.m3u8 playlist (seq=$mediaSequence, ${recentSegments.size} segments, HTTP $code)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload HLS playlist: ${e.message}")
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildFileUrl(filename: String): String {
        return if (baseEndpoint.contains("cid=")) {
            if (baseEndpoint.endsWith("cid=")) {
                "$baseEndpoint$activeStreamKey&copy=0&file=$filename"
            } else {
                "$baseEndpoint&copy=0&file=$filename"
            }
        } else {
            "${baseEndpoint.trimEnd('/')}/$filename"
        }
    }

    private fun updateSpeed() {
        val now = System.currentTimeMillis()
        val delta = now - lastSpeedCheckTime
        if (delta >= 1000) {
            val total = totalBytesSent.get()
            val bytesInSec = total - lastBytesCount
            currentUploadSpeedKbps = (bytesInSec * 8) / 1000
            lastBytesCount = total
            lastSpeedCheckTime = now
        }
    }

    /**
     * Packages encoded video sample into MPEG-TS packets.
     */
    @Synchronized
    fun sendVideo(rawBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isKeyframe: Boolean, isHevc: Boolean) {
        if (!isPublishing.get()) return

        val now = System.currentTimeMillis()
        if (segmentStartTimeMs == -1L) {
            segmentStartTimeMs = now
        }

        // On keyframe after ~1.5 - 2 seconds, finalize previous segment and begin new segment
        val segmentDuration = now - segmentStartTimeMs
        if (isKeyframe && segmentDuration >= 1800 && currentSegmentBuffer.size() > 0) {
            flushSegment(segmentDuration)
            segmentStartTimeMs = now
        }

        // Write PAT and PMT at segment start or before keyframes
        if (currentSegmentBuffer.size() == 0 || isKeyframe) {
            writePatPacket()
            writePmtPacket(isHevc)
        }

        // Write Video PES packet
        val rawBytes = ByteArray(bufferInfo.size)
        val pos = rawBuffer.position()
        rawBuffer.get(rawBytes)
        rawBuffer.position(pos)

        // Ensure Annex B format and prepend Access Unit Delimiter (AUD) for MPEG-TS compliance
        val audBytes = if (isHevc) {
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x46.toByte(), 0x01, 0x50) // HEVC AUD NAL unit
        } else {
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x09, 0xF0.toByte()) // AVC AUD NAL unit
        }

        val videoBytes = if (isAnnexB(rawBytes)) {
            val combined = ByteArray(audBytes.size + rawBytes.size)
            System.arraycopy(audBytes, 0, combined, 0, audBytes.size)
            System.arraycopy(rawBytes, 0, combined, audBytes.size, rawBytes.size)
            combined
        } else {
            // Convert AVCC length prefixes to Annex B start codes
            val converted = convertAvccToAnnexB(rawBytes)
            val combined = ByteArray(audBytes.size + converted.size)
            System.arraycopy(audBytes, 0, combined, 0, audBytes.size)
            System.arraycopy(converted, 0, combined, audBytes.size, converted.size)
            combined
        }

        val pts90k = (bufferInfo.presentationTimeUs * 90) / 1000
        writePesPacket(PID_VIDEO, videoBytes, pts90k, isVideo = true, isKeyframe = isKeyframe)
    }

    private fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0.toByte() && data[1] == 0.toByte() &&
                (data[2] == 1.toByte() || (data[2] == 0.toByte() && data[3] == 1.toByte()))
    }

    private fun convertAvccToAnnexB(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 32)
        var offset = 0
        while (offset + 4 <= data.size) {
            val len = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (len > 0 && offset + len <= data.size) {
                out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x01)
                out.write(data, offset, len)
                offset += len
            } else {
                break
            }
        }
        return if (out.size() > 0) out.toByteArray() else data
    }

    /**
     * Packages encoded audio sample into MPEG-TS packets.
     */
    @Synchronized
    fun sendAudio(aacBytes: ByteArray, ptsMs: Long) {
        if (!isPublishing.get()) return
        if (currentSegmentBuffer.size() == 0) return // Wait for first video keyframe to establish timing

        val pts90k = ptsMs * 90
        writePesPacket(PID_AUDIO, aacBytes, pts90k, isVideo = false, isKeyframe = false)
    }

    private fun flushSegment(durationMs: Long) {
        val data = currentSegmentBuffer.toByteArray()
        currentSegmentBuffer.reset()
        if (data.isNotEmpty()) {
            val seg = HlsSegment(segmentIndex++, data, durationMs)
            if (!segmentQueue.offer(seg)) {
                segmentQueue.poll() // Drop oldest segment to prevent lag
                segmentQueue.offer(seg)
                droppedFramesCount.incrementAndGet()
            }
        }
    }

    private fun calculateMpeg2Crc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val bitVal = (b shr bit) and 1
                val c31 = (crc ushr 31) and 1
                crc = crc shl 1
                if ((c31 xor bitVal) != 0) {
                    crc = crc xor 0x04C11DB7
                }
            }
        }
        return crc
    }

    private fun writePatPacket() {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47.toByte() // Sync byte
        packet[1] = 0x40.toByte() // Payload unit start indicator, PID high (0)
        packet[2] = 0x00.toByte() // PID low (0)
        packet[3] = (0x10 or (patContinuity and 0x0F)).toByte()
        patContinuity = (patContinuity + 1) and 0x0F

        packet[4] = 0x00.toByte() // Pointer field
        packet[5] = 0x00.toByte() // Table ID: PAT
        packet[6] = 0xB0.toByte() // Section syntax indicator
        packet[7] = 0x0D.toByte() // Section length = 13
        packet[8] = 0x00.toByte() // Transport stream ID
        packet[9] = 0x01.toByte()
        packet[10] = 0xC1.toByte() // Version 0, current
        packet[11] = 0x00.toByte() // Section number 0
        packet[12] = 0x00.toByte() // Last section number 0
        // Program 1 -> PMT PID 0x1000
        packet[13] = 0x00.toByte()
        packet[14] = 0x01.toByte()
        packet[15] = (0xF0 or ((PID_PMT shr 8) and 0x1F)).toByte()
        packet[16] = (PID_PMT and 0xFF).toByte()

        // Calculate standard MPEG-2 CRC32 over PAT section (bytes 5..16)
        val crc = calculateMpeg2Crc32(packet, 5, 12)
        packet[17] = ((crc shr 24) and 0xFF).toByte()
        packet[18] = ((crc shr 16) and 0xFF).toByte()
        packet[19] = ((crc shr 8) and 0xFF).toByte()
        packet[20] = (crc and 0xFF).toByte()

        currentSegmentBuffer.write(packet)
    }

    private fun writePmtPacket(isHevc: Boolean) {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47.toByte()
        packet[1] = (0x40 or ((PID_PMT shr 8) and 0x1F)).toByte()
        packet[2] = (PID_PMT and 0xFF).toByte()
        packet[3] = (0x10 or (pmtContinuity and 0x0F)).toByte()
        pmtContinuity = (pmtContinuity + 1) and 0x0F

        packet[4] = 0x00.toByte() // Pointer field
        packet[5] = 0x02.toByte() // Table ID: PMT
        packet[6] = 0xB0.toByte()
        packet[7] = 0x17.toByte() // Section length = 23
        packet[8] = 0x00.toByte() // Program number 1
        packet[9] = 0x01.toByte()
        packet[10] = 0xC1.toByte()
        packet[11] = 0x00.toByte()
        packet[12] = 0x00.toByte()
        packet[13] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte() // PCR PID
        packet[14] = (PID_VIDEO and 0xFF).toByte()
        packet[15] = 0xF0.toByte() // Program info length
        packet[16] = 0x00.toByte()

        // Video Stream
        packet[17] = (if (isHevc) STREAM_TYPE_HEVC else STREAM_TYPE_AVC).toByte()
        packet[18] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte()
        packet[19] = (PID_VIDEO and 0xFF).toByte()
        packet[20] = 0xF0.toByte()
        packet[21] = 0x00.toByte()

        // Audio Stream
        packet[22] = STREAM_TYPE_AAC.toByte()
        packet[23] = (0xE0 or ((PID_AUDIO shr 8) and 0x1F)).toByte()
        packet[24] = (PID_AUDIO and 0xFF).toByte()
        packet[25] = 0xF0.toByte()
        packet[26] = 0x00.toByte()

        // Calculate standard MPEG-2 CRC32 over PMT section (bytes 5..26)
        val crc = calculateMpeg2Crc32(packet, 5, 22)
        packet[27] = ((crc shr 24) and 0xFF).toByte()
        packet[28] = ((crc shr 16) and 0xFF).toByte()
        packet[29] = ((crc shr 8) and 0xFF).toByte()
        packet[30] = (crc and 0xFF).toByte()

        currentSegmentBuffer.write(packet)
    }

    private fun writePesPacket(pid: Int, payload: ByteArray, pts90k: Long, isVideo: Boolean, isKeyframe: Boolean) {
        val pesHeader = ByteArrayOutputStream()
        // Start code prefix 0x000001
        pesHeader.write(0x00)
        pesHeader.write(0x00)
        pesHeader.write(0x01)
        // Stream ID: 0xE0 for Video, 0xC0 for Audio
        pesHeader.write(if (isVideo) 0xE0 else 0xC0)

        // PES packet length: 0 for unbounded video, or payload + 8 for audio
        val pesLen = if (isVideo) 0 else minOf(0xFFFF, payload.size + 8)
        pesHeader.write((pesLen shr 8) and 0xFF)
        pesHeader.write(pesLen and 0xFF)

        // Flags: PTS present (0x80)
        pesHeader.write(0x80)
        pesHeader.write(0x80) // PTS only
        pesHeader.write(0x05) // PES header data length = 5 bytes for PTS

        // 33-bit PTS
        pesHeader.write(0x20 or (((pts90k shr 30).toInt() and 0x07) shl 1) or 0x01)
        pesHeader.write((pts90k shr 22).toInt() and 0xFF)
        pesHeader.write((((pts90k shr 15).toInt() and 0x7F) shl 1) or 0x01)
        pesHeader.write((pts90k shr 7).toInt() and 0xFF)
        pesHeader.write((((pts90k and 0x7F).toInt()) shl 1) or 0x01)

        val pesBytes = pesHeader.toByteArray()
        val totalPayloadSize = pesBytes.size + payload.size
        var offset = 0
        var isFirstPacket = true

        while (offset < totalPayloadSize) {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = 0x47.toByte()

            val cc = if (isVideo) {
                val c = videoContinuity
                videoContinuity = (videoContinuity + 1) and 0x0F
                c
            } else {
                val c = audioContinuity
                audioContinuity = (audioContinuity + 1) and 0x0F
                c
            }

            var pusi = if (isFirstPacket) 0x40 else 0x00
            packet[1] = (pusi or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()

            val remaining = totalPayloadSize - offset
            if (isFirstPacket && isVideo && isKeyframe) {
                // Adaptation field with PCR for video keyframes
                packet[3] = (0x30 or (cc and 0x0F)).toByte() // Adaptation field + payload
                packet[4] = 0x07.toByte() // Adaptation length = 7 bytes
                packet[5] = 0x50.toByte() // Random access indicator (keyframe) + PCR flag
                // PCR 6 bytes
                packet[6] = ((pts90k shr 25) and 0xFF).toByte()
                packet[7] = ((pts90k shr 17) and 0xFF).toByte()
                packet[8] = ((pts90k shr 9) and 0xFF).toByte()
                packet[9] = ((pts90k shr 1) and 0xFF).toByte()
                packet[10] = (((pts90k and 0x01) shl 7) or 0x7E).toByte()
                packet[11] = 0x00.toByte()

                val availablePayload = TS_PACKET_SIZE - 12
                val toWrite = minOf(availablePayload, remaining)
                copyPesBytes(pesBytes, payload, offset, packet, 12, toWrite)
                offset += toWrite
            } else if (remaining < 184) {
                // Last packet needs adaptation padding
                val paddingLen = 184 - remaining
                packet[3] = (0x30 or (cc and 0x0F)).toByte()
                packet[4] = (paddingLen - 1).toByte()
                if (paddingLen > 1) {
                    packet[5] = 0x00.toByte() // flags
                }
                copyPesBytes(pesBytes, payload, offset, packet, 4 + paddingLen, remaining)
                offset += remaining
            } else {
                // Full payload packet
                packet[3] = (0x10 or (cc and 0x0F)).toByte()
                copyPesBytes(pesBytes, payload, offset, packet, 4, 184)
                offset += 184
            }

            currentSegmentBuffer.write(packet)
            isFirstPacket = false
        }
    }

    private fun copyPesBytes(header: ByteArray, data: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
        var written = 0
        var currentSrc = srcOffset
        while (written < length) {
            val b = if (currentSrc < header.size) {
                header[currentSrc]
            } else {
                val dataIndex = currentSrc - header.size
                if (dataIndex < data.size) data[dataIndex] else 0.toByte()
            }
            dst[dstOffset + written] = b
            written++
            currentSrc++
        }
    }

    fun disconnect() {
        isPublishing.set(false)
        uploaderThread?.interrupt()
        uploaderThread = null
        segmentQueue.clear()
        currentSegmentBuffer.reset()
    }

    /**
     * Diagnostic connection test to the YouTube HLS ingestion endpoint.
     */
    fun testConnection(serverUrl: String, streamKey: String): Pair<Boolean, String> {
        val startTime = System.currentTimeMillis()
        try {
            val key = streamKey.trim()
            val base = if (serverUrl.isNotBlank() && serverUrl.startsWith("http", ignoreCase = true)) {
                serverUrl.trim()
            } else {
                "https://a.upload.youtube.com/http_upload_hls?cid="
            }
            val testUrl = if (base.contains("cid=")) {
                if (base.endsWith("cid=")) "$base$key&copy=0&file=probe.ts" else "$base&copy=0&file=probe.ts"
            } else {
                "${base.trimEnd('/')}/probe.ts"
            }

            val conn = (URL(testUrl).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "video/MP2T")
                setFixedLengthStreamingMode(188)
                doOutput = true
            }

            // Write 1 dummy TS sync packet for probe
            conn.outputStream.use { out ->
                val dummy = ByteArray(188) { 0x47.toByte() }
                out.write(dummy)
                out.flush()
            }

            val code = conn.responseCode
            val latency = System.currentTimeMillis() - startTime
            conn.disconnect()

            return if (code in 200..299 || code == 400 || code == 403) {
                // If endpoint responds (even with 400 bad chunk or 200), HTTP reachability is verified!
                Pair(true, "YouTube HLS Endpoint reached successfully! (HTTP $code, ${latency}ms latency)")
            } else {
                Pair(false, "Server returned HTTP $code")
            }
        } catch (e: Exception) {
            return Pair(false, "HLS Endpoint test failed: ${e.localizedMessage ?: e.message}")
        }
    }
}
