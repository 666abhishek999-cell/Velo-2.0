package com.example.rtmp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class RtmpClient(
    private val onStatusChanged: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "RtmpClient"
        private const val DEFAULT_RTMP_PORT = 1935
        private const val DEFAULT_RTMPS_PORT = 443
        private const val CHUNK_SIZE = 4096
        private const val MAX_QUEUE_SIZE = 80

        // Packet types
        const val TYPE_SET_CHUNK_SIZE = 0x01
        const val TYPE_ABORT_MESSAGE = 0x02
        const val TYPE_ACKNOWLEDGEMENT = 0x03
        const val TYPE_USER_CONTROL = 0x04
        const val TYPE_WINDOW_ACK_SIZE = 0x05
        const val TYPE_SET_PEER_BANDWIDTH = 0x06
        const val TYPE_AUDIO = 0x08
        const val TYPE_VIDEO = 0x09
        const val TYPE_DATA = 0x12
        const val TYPE_COMMAND_AMF0 = 0x14

        // CSIDs
        const val CSID_CONTROL = 2
        const val CSID_COMMAND = 3
        const val CSID_AUDIO = 4
        const val CSID_VIDEO = 6
    }

    private val writeLock = Any()
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private val isConnected = AtomicBoolean(false)
    private val isPublishing = AtomicBoolean(false)
    private var streamId = 1
    private var serverChunkSize = 128
    private var outgoingChunkSize = 128

    private var avcSequenceHeaderDispatched = false
    private var hevcSequenceHeaderDispatched = false
    private var aacSequenceHeaderDispatched = false
    private var hasSentFirstKeyframe = false

    private class MessageState(var type: Int = 0, var length: Int = 0, var received: Int = 0, var buffer: ByteArray = ByteArray(0))
    private val activeMessages = mutableMapOf<Int, MessageState>()

    private val packetQueue = LinkedBlockingQueue<RtmpPacket>(MAX_QUEUE_SIZE)
    private var senderThread: Thread? = null
    private var readerThread: Thread? = null

    // Pending headers cache (to prevent broken pipe before handshake finishes)
    data class MetadataParams(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrateKbps: Int,
        val audioBitrateKbps: Int,
        val isHevc: Boolean
    )
    private var pendingMetadata: MetadataParams? = null
    private var pendingAvcHeader: Pair<ByteArray, ByteArray>? = null
    private var pendingHevcHeader: Triple<ByteArray, ByteArray, ByteArray>? = null
    private var pendingAacHeader: ByteArray? = null

    // Stats
    val droppedFramesCount = AtomicLong(0)
    val totalBytesSent = AtomicLong(0)
    private var lastSpeedCheckTime = System.currentTimeMillis()
    private var lastBytesCount = 0L
    @Volatile var currentUploadSpeedKbps: Long = 0L
        private set

    private var startTimeMs: Long = 0

    fun isLive(): Boolean = isPublishing.get()

    /**
     * Connects to RTMP or RTMPS server and begins publishing.
     */
    fun connectAndPublish(serverUrl: String, streamKey: String): Boolean {
        try {
            onStatusChanged("Connecting to server…")
            val target = parseUrl(serverUrl, streamKey)
            Log.d(TAG, "Parsed destination: host=${target.host}, port=${target.port}, app=${target.app}, tcUrl=${target.tcUrl}, isSsl=${target.isSsl}")

            outgoingChunkSize = 128
            serverChunkSize = 128
            avcSequenceHeaderDispatched = false
            hevcSequenceHeaderDispatched = false
            aacSequenceHeaderDispatched = false
            hasSentFirstKeyframe = false
            activeMessages.clear()

            val rawSocket = createAndConnectSocket(target, 6000)
            socket = rawSocket
            inStream = BufferedInputStream(rawSocket.getInputStream(), 64 * 1024)
            outStream = BufferedOutputStream(rawSocket.getOutputStream(), 64 * 1024)

            onStatusChanged("Handshaking…")
            doHandshake()

            onStatusChanged("Connecting AMF0…")
            // Connect is sent with default 128-byte chunk size per RTMP specification
            sendConnectCommand(target.app, target.tcUrl)

            val connectResult = readServerCommand(expectedTransactionId = 1.0, timeoutMs = 8000)
            Log.d(TAG, "Connect result: name=${connectResult?.name}, obj=${connectResult?.infoObj}")

            if (connectResult == null) {
                throw IllegalStateException("Connection timeout from RTMP server")
            }
            if (connectResult.name == "_error") {
                val desc = extractErrorDescription(connectResult.infoObj)
                throw IllegalStateException("Connect rejected: $desc")
            }

            // Negotiate 4096-byte chunk size and window ack size after connect succeeds
            sendWindowAckSize(2500000)
            sendChunkSize(CHUNK_SIZE)
            outgoingChunkSize = CHUNK_SIZE

            sendReleaseStream(target.streamKey)
            sendFCPublish(target.streamKey)
            sendCreateStream()

            val createStreamResult = readServerCommand(expectedTransactionId = 4.0, timeoutMs = 8000)
            Log.d(TAG, "CreateStream response: name=${createStreamResult?.name}, streamId=${createStreamResult?.infoObj}")

            if (createStreamResult != null && createStreamResult.infoObj is Number) {
                val returnedId = (createStreamResult.infoObj as Number).toInt()
                if (returnedId > 0) {
                    streamId = returnedId
                }
            }

            onStatusChanged("Publishing…")
            sendPublish(target.streamKey)

            // Verify publish response from server (handles BadName, stream key errors before sending frames)
            val publishResult = readServerCommand(expectedCommand = "onStatus", timeoutMs = 8000)
            Log.d(TAG, "Publish result: name=${publishResult?.name}, info=${publishResult?.infoObj}")
            if (publishResult?.name == "_error") {
                val desc = extractErrorDescription(publishResult.infoObj)
                throw IllegalStateException("Publish error: $desc")
            }
            if (publishResult?.infoObj is Map<*, *>) {
                val info = publishResult.infoObj as Map<*, *>
                val level = info["level"]?.toString()
                val code = info["code"]?.toString()
                val desc = info["description"]?.toString() ?: code ?: "Publish error"
                if (level == "error" || (code != null && code.contains("BadName", ignoreCase = true))) {
                    throw IllegalStateException("Stream key rejected: $desc ($code)")
                }
            }

            isConnected.set(true)
            isPublishing.set(true)
            startTimeMs = System.currentTimeMillis()

            // Dispatch any sequence headers that were prepared by MediaCodec while connecting
            pendingMetadata?.let { dispatchMetadata(it) }
            pendingAvcHeader?.let { dispatchAvcSequenceHeader(it.first, it.second) }
            pendingHevcHeader?.let { dispatchHevcSequenceHeader(it.first, it.second, it.third) }
            pendingAacHeader?.let { dispatchAacSequenceHeader(it) }

            startReaderThread()
            startSenderThread()
            onStatusChanged("Live")
            Log.d(TAG, "RTMP publish successfully started on streamId $streamId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            disconnect()
            onError(e.message ?: "Connection failed")
            return false
        }
    }

    private fun extractErrorDescription(obj: Any?): String {
        if (obj is Map<*, *>) {
            return (obj["description"] ?: obj["code"] ?: "Unknown error").toString()
        }
        return obj?.toString() ?: "Connection rejected"
    }

    private fun startSenderThread() {
        senderThread = Thread({
            while (isPublishing.get()) {
                try {
                    val packet = packetQueue.take()
                    val out = outStream ?: break

                    sendRtmpMessage(
                        csid = if (packet.type == TYPE_AUDIO) CSID_AUDIO else CSID_VIDEO,
                        messageType = packet.type,
                        timestamp = packet.timestamp,
                        streamId = streamId,
                        payload = packet.payload,
                        out = out
                    )

                    val bytes = packet.payload.size.toLong()
                    totalBytesSent.addAndGet(bytes)
                    updateSpeed()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Send error: ${e.message}")
                    val wasLive = isPublishing.getAndSet(false)
                    if (wasLive) {
                        onError("Streaming interrupted: ${e.message ?: "Broken pipe"}")
                    }
                    break
                }
            }
        }, "VeloStream-RTMP-Sender").apply {
            priority = Thread.MAX_PRIORITY
            start()
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
     * Queues H.264 or Enhanced RTMP H.265 (HEVC) video packet with proper FLV video tag header.
     */
    fun sendVideo(payload: ByteArray, timestampMs: Long, isKeyframe: Boolean, isHevc: Boolean = false) {
        if (!isPublishing.get()) return

        // Must not send video before codec sequence header has reached server
        if (!isHevc && !avcSequenceHeaderDispatched) return
        if (isHevc && !hevcSequenceHeaderDispatched) return

        // First video frame must be a keyframe (IDR) to establish decoder state on server
        if (!hasSentFirstKeyframe) {
            if (!isKeyframe) return
            hasSentFirstKeyframe = true
        }

        // If queue backs up, drop non-keyframe to keep gaming low latency
        if (packetQueue.size > 35 && !isKeyframe) {
            droppedFramesCount.incrementAndGet()
            return
        }

        val flvPayload = if (!isHevc) {
            // RTMP Video Message requires 5-byte FLV video tag header for H.264 (AVC):
            // Byte 0: FrameType (1=keyframe, 2=inter) << 4 | CodecID (7=AVC) -> 0x17 for keyframe, 0x27 for inter
            // Byte 1: AVCPacketType (1=AVC NALU)
            // Bytes 2..4: CompositionTime offset (0x00, 0x00, 0x00)
            val p = ByteArray(5 + payload.size)
            p[0] = if (isKeyframe) 0x17.toByte() else 0x27.toByte()
            p[1] = 0x01.toByte()
            p[2] = 0x00.toByte()
            p[3] = 0x00.toByte()
            p[4] = 0x00.toByte()
            System.arraycopy(payload, 0, p, 5, payload.size)
            p
        } else {
            // Enhanced RTMP HEVC video packet (8-byte header for 'hvc1'):
            // Byte 0: IsExHeader (0x80) | (FrameType << 4) | PacketType (1 = CodedFrames)
            // Keyframe: 0x80 | (1 << 4) | 1 = 0x91; Interframe: 0x80 | (2 << 4) | 1 = 0xA1
            // Bytes 1..4: FourCC 'h', 'v', 'c', '1'
            // Bytes 5..7: CompositionTime (0x00, 0x00, 0x00)
            val p = ByteArray(8 + payload.size)
            p[0] = if (isKeyframe) 0x91.toByte() else 0xA1.toByte()
            p[1] = 'h'.code.toByte()
            p[2] = 'v'.code.toByte()
            p[3] = 'c'.code.toByte()
            p[4] = '1'.code.toByte()
            p[5] = 0x00.toByte()
            p[6] = 0x00.toByte()
            p[7] = 0x00.toByte()
            System.arraycopy(payload, 0, p, 8, payload.size)
            p
        }

        val packet = RtmpPacket(
            type = TYPE_VIDEO,
            timestamp = timestampMs,
            payload = flvPayload,
            isKeyframe = isKeyframe
        )
        if (!packetQueue.offer(packet)) {
            if (!isKeyframe) {
                droppedFramesCount.incrementAndGet()
            }
        }
    }

    /**
     * Queues AAC audio packet with proper 2-byte FLV audio tag header.
     */
    fun sendAudio(payload: ByteArray, timestampMs: Long) {
        if (!isPublishing.get()) return
        if (!aacSequenceHeaderDispatched) return

        // RTMP Audio Message requires 2-byte FLV audio tag header for AAC:
        // Byte 0: SoundFormat(10=AAC)<<4 | SoundRate(3=44k)<<2 | SoundSize(1=16bit)<<1 | SoundType(1=Stereo) -> 0xAF
        // Byte 1: AACPacketType (1=AAC raw frame)
        val flvPayload = ByteArray(2 + payload.size)
        flvPayload[0] = 0xAF.toByte()
        flvPayload[1] = 0x01.toByte()
        System.arraycopy(payload, 0, flvPayload, 2, payload.size)

        val packet = RtmpPacket(
            type = TYPE_AUDIO,
            timestamp = timestampMs,
            payload = flvPayload
        )
        packetQueue.offer(packet)
    }

    fun sendMetadata(width: Int, height: Int, fps: Int, videoBitrateKbps: Int, audioBitrateKbps: Int, isHevc: Boolean = false) {
        val meta = MetadataParams(width, height, fps, videoBitrateKbps, audioBitrateKbps, isHevc)
        pendingMetadata = meta
        if (isPublishing.get()) {
            dispatchMetadata(meta)
        }
    }

    private fun dispatchMetadata(meta: MetadataParams) {
        val out = outStream ?: return
        try {
            val body = ByteArrayOutputStream()
            Amf0.writeString(body, "@setDataFrame")
            Amf0.writeString(body, "onMetaData")

            val metaMap = mutableMapOf<String, Any?>(
                "duration" to 0.0,
                "width" to meta.width.toDouble(),
                "height" to meta.height.toDouble(),
                "videodatarate" to meta.videoBitrateKbps.toDouble(),
                "framerate" to meta.fps.toDouble(),
                "videocodecid" to if (meta.isHevc) "hvc1" else 7.0,
                "audiodatarate" to meta.audioBitrateKbps.toDouble(),
                "audiosamplerate" to 44100.0,
                "audiosamplesize" to 16.0,
                "stereo" to true,
                "audiocodecid" to 10.0 // AAC
            )
            if (meta.isHevc) {
                metaMap["fourcc"] = "hvc1"
            }
            Amf0.writeEcmaArray(body, metaMap)

            sendRtmpMessage(
                csid = CSID_COMMAND,
                messageType = TYPE_DATA,
                timestamp = 0,
                streamId = streamId,
                payload = body.toByteArray(),
                out = out
            )
            Log.d(TAG, "Dispatched RTMP onMetaData (${meta.width}x${meta.height} @ ${meta.fps}fps, HEVC=${meta.isHevc})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send metadata", e)
        }
    }

    fun sendHevcSequenceHeader(vps: ByteArray, sps: ByteArray, pps: ByteArray) {
        if (sps.isEmpty()) return
        pendingHevcHeader = Triple(vps, sps, pps)
        if (isPublishing.get()) {
            dispatchHevcSequenceHeader(vps, sps, pps)
        }
    }

    private fun dispatchHevcSequenceHeader(vps: ByteArray, sps: ByteArray, pps: ByteArray) {
        if (sps.isEmpty()) return
        val out = outStream ?: return
        try {
            val body = ByteArrayOutputStream()
            // Enhanced RTMP SequenceStart: Byte 0 = 0x80 (IsExHeader) | (1 << 4) | 0 = 0x90
            body.write(0x90)
            // FourCC 'hvc1'
            body.write('h'.code)
            body.write('v'.code)
            body.write('c'.code)
            body.write('1'.code)

            // HEVCDecoderConfigurationRecord (HVCC) specification (ISO/IEC 14496-15 Section 8.3.3.1.2)
            // Total 23 bytes before NAL arrays:
            // 1. configurationVersion = 1
            body.write(0x01)

            // 2. general_profile_space (2 bits), general_tier_flag (1 bit), general_profile_idc (5 bits)
            val profileByte = if (sps.size >= 4) {
                sps[3].toInt() and 0xFF
            } else {
                0x01 // Main Profile
            }
            body.write(profileByte)

            // 3..6. general_profile_compatibility_flags (32 bits = 4 bytes)
            if (sps.size >= 8) {
                body.write(sps, 4, 4)
            } else {
                body.write(0x60)
                body.write(0x00)
                body.write(0x00)
                body.write(0x00)
            }

            // 7..12. general_constraint_indicator_flags (48 bits = 6 bytes)
            if (sps.size >= 14) {
                body.write(sps, 8, 6)
            } else {
                body.write(0xB0)
                body.write(0x00)
                body.write(0x00)
                body.write(0x00)
                body.write(0x00)
                body.write(0x00)
            }

            // 13. general_level_idc (8 bits = 1 byte)
            val levelByte = if (sps.size >= 15) {
                sps[14].toInt() and 0xFF
            } else {
                120 // Level 4.0
            }
            body.write(levelByte)

            // 14..15. min_spatial_segmentation_idc (4 bits reserved 1111b + 12 bits = 2 bytes)
            body.write(0xF0)
            body.write(0x00)

            // 16. parallelismType (6 bits reserved 111111b + 2 bits = 0xFC)
            body.write(0xFC)

            // 17. chromaFormat (6 bits reserved 111111b + 2 bits: 1 for 4:2:0 -> 0xFD)
            body.write(0xFD)

            // 18. bitDepthLumaMinus8 (5 bits reserved 11111b + 3 bits: 0 for 8-bit -> 0xF8)
            body.write(0xF8)

            // 19. bitDepthChromaMinus8 (5 bits reserved 11111b + 3 bits: 0 for 8-bit -> 0xF8)
            body.write(0xF8)

            // 20..21. avgFrameRate (16 bits = 2 bytes)
            body.write(0x00)
            body.write(0x00)

            // 22. constantFrameRate(2) | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2) -> 0x03
            body.write(0x03)

            // 23. numOfArrays (8 bits = 1 byte)
            var arrayCount = 0
            if (vps.isNotEmpty()) arrayCount++
            if (sps.isNotEmpty()) arrayCount++
            if (pps.isNotEmpty()) arrayCount++
            body.write(arrayCount)

            // Array 1: VPS (type 32)
            if (vps.isNotEmpty()) {
                body.write(0xA0) // 0x80 (array_completeness) or 32
                body.write(0x00)
                body.write(0x01) // numNalus = 1
                body.write((vps.size shr 8) and 0xFF)
                body.write(vps.size and 0xFF)
                body.write(vps)
            }

            // Array 2: SPS (type 33)
            if (sps.isNotEmpty()) {
                body.write(0xA1) // 0x80 (array_completeness) or 33
                body.write(0x00)
                body.write(0x01) // numNalus = 1
                body.write((sps.size shr 8) and 0xFF)
                body.write(sps.size and 0xFF)
                body.write(sps)
            }

            // Array 3: PPS (type 34)
            if (pps.isNotEmpty()) {
                body.write(0xA2) // 0x80 (array_completeness) or 34
                body.write(0x00)
                body.write(0x01) // numNalus = 1
                body.write((pps.size shr 8) and 0xFF)
                body.write(pps.size and 0xFF)
                body.write(pps)
            }

            sendRtmpMessage(
                csid = CSID_VIDEO,
                messageType = TYPE_VIDEO,
                timestamp = 0,
                streamId = streamId,
                payload = body.toByteArray(),
                out = out
            )
            hevcSequenceHeaderDispatched = true
            Log.d(TAG, "Sent Enhanced RTMP HEVC Sequence Header (VPS ${vps.size}B, SPS ${sps.size}B, PPS ${pps.size}B, arrays=$arrayCount)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send HEVC sequence header", e)
        }
    }

    fun sendAvcSequenceHeader(sps: ByteArray, pps: ByteArray) {
        if (sps.isEmpty() || pps.isEmpty()) return
        pendingAvcHeader = Pair(sps, pps)
        if (isPublishing.get()) {
            dispatchAvcSequenceHeader(sps, pps)
        }
    }

    private fun dispatchAvcSequenceHeader(sps: ByteArray, pps: ByteArray) {
        if (sps.isEmpty() || pps.isEmpty()) return
        val out = outStream ?: return
        try {
            val body = ByteArrayOutputStream()
            // Frame type: 1 (Keyframe) + Codec ID: 7 (AVC) -> 0x17
            body.write(0x17)
            // AVC packet type: 0 (AVC sequence header)
            body.write(0x00)
            // Composition time offset: 3 bytes 0
            body.write(0x00)
            body.write(0x00)
            body.write(0x00)

            // AVCDecoderConfigurationRecord
            body.write(0x01) // configurationVersion
            val profile = if (sps.size > 1) sps[1].toInt() and 0xFF else 0x64
            val compat = if (sps.size > 2) sps[2].toInt() and 0xFF else 0x00
            val level = if (sps.size > 3) sps[3].toInt() and 0xFF else 0x1F
            body.write(profile)
            body.write(compat)
            body.write(level)
            body.write(0xFF) // lengthSizeMinusOne: 3 (4 bytes) | 0xFC = 0xFF

            // SPS
            body.write(0xE1) // numOfSequenceParameterSets = 1 | 0xE0
            body.write((sps.size shr 8) and 0xFF)
            body.write(sps.size and 0xFF)
            body.write(sps)

            // PPS
            body.write(0x01) // numOfPictureParameterSets = 1
            body.write((pps.size shr 8) and 0xFF)
            body.write(pps.size and 0xFF)
            body.write(pps)

            sendRtmpMessage(
                csid = CSID_VIDEO,
                messageType = TYPE_VIDEO,
                timestamp = 0,
                streamId = streamId,
                payload = body.toByteArray(),
                out = out
            )
            avcSequenceHeaderDispatched = true
            Log.d(TAG, "Sent AVC Sequence Header: SPS ${sps.size}B, PPS ${pps.size}B")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send AVC sequence header", e)
        }
    }

    fun sendAacSequenceHeader(ascBytes: ByteArray) {
        if (ascBytes.isEmpty()) return
        pendingAacHeader = ascBytes
        if (isPublishing.get()) {
            dispatchAacSequenceHeader(ascBytes)
        }
    }

    private fun dispatchAacSequenceHeader(ascBytes: ByteArray) {
        if (ascBytes.isEmpty()) return
        val out = outStream ?: return
        try {
            val body = ByteArrayOutputStream()
            // Format: 10 (AAC) + SoundRate: 3 (44kHz) + SoundSize: 1 (16bit) + SoundType: 1 (Stereo) -> 0xAF
            body.write(0xAF)
            // AAC packet type: 0 (AAC sequence header)
            body.write(0x00)
            body.write(ascBytes)

            sendRtmpMessage(
                csid = CSID_AUDIO,
                messageType = TYPE_AUDIO,
                timestamp = 0,
                streamId = streamId,
                payload = body.toByteArray(),
                out = out
            )
            aacSequenceHeaderDispatched = true
            Log.d(TAG, "Sent AAC Sequence Header (${ascBytes.size}B)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send AAC sequence header", e)
        }
    }

    private fun startReaderThread() {
        readerThread = Thread({
            val inS = inStream ?: return@Thread
            var ackWindowSize = 2500000L
            var totalBytesRead = 0L
            var lastAckBytes = 0L

            while (isPublishing.get()) {
                try {
                    val b0 = inS.read()
                    if (b0 == -1) {
                        Log.d(TAG, "Server closed incoming stream (EOF)")
                        if (isPublishing.getAndSet(false)) {
                            onError("Connection closed by server")
                        }
                        break
                    }
                    totalBytesRead++

                    val fmt = (b0 shr 6) and 0x03
                    var csid = b0 and 0x3F
                    if (csid == 0) {
                        val c1 = inS.read()
                        if (c1 == -1) break
                        totalBytesRead++
                        csid = 64 + c1
                    } else if (csid == 1) {
                        val b1 = inS.read()
                        val b2 = inS.read()
                        if (b1 == -1 || b2 == -1) break
                        totalBytesRead += 2
                        csid = 64 + b1 + (b2 shl 8)
                    }

                    val state = activeMessages.getOrPut(csid) { MessageState() }

                    when (fmt) {
                        0 -> {
                            val header = readFully(inS, 11)
                            totalBytesRead += 11
                            val ts = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                            val len = ((header[3].toInt() and 0xFF) shl 16) or ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                            val type = header[6].toInt() and 0xFF
                            if (ts == 0xFFFFFF) {
                                readFully(inS, 4)
                                totalBytesRead += 4
                            }
                            state.type = type
                            state.length = len
                            state.received = 0
                            state.buffer = ByteArray(len)
                        }
                        1 -> {
                            val header = readFully(inS, 7)
                            totalBytesRead += 7
                            val delta = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                            val len = ((header[3].toInt() and 0xFF) shl 16) or ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                            val type = header[6].toInt() and 0xFF
                            if (delta == 0xFFFFFF) {
                                readFully(inS, 4)
                                totalBytesRead += 4
                            }
                            state.type = type
                            state.length = len
                            state.received = 0
                            state.buffer = ByteArray(len)
                        }
                        2 -> {
                            val header = readFully(inS, 3)
                            totalBytesRead += 3
                            val delta = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                            if (delta == 0xFFFFFF) {
                                readFully(inS, 4)
                                totalBytesRead += 4
                            }
                            state.received = 0
                            state.buffer = ByteArray(state.length)
                        }
                        3 -> {
                            // Continuation chunk
                        }
                    }

                    val toRead = minOf(serverChunkSize, state.length - state.received)
                    if (toRead > 0) {
                        val chunk = readFully(inS, toRead)
                        totalBytesRead += toRead
                        System.arraycopy(chunk, 0, state.buffer, state.received, toRead)
                        state.received += toRead
                    }

                    if (state.received >= state.length && state.length > 0) {
                        when (state.type) {
                            TYPE_SET_CHUNK_SIZE -> {
                                if (state.buffer.size >= 4) {
                                    val newSize = ByteBuffer.wrap(state.buffer).order(ByteOrder.BIG_ENDIAN).int
                                    if (newSize > 0) {
                                        serverChunkSize = newSize
                                        Log.d(TAG, "Server updated chunk size: $serverChunkSize")
                                    }
                                }
                            }
                            TYPE_WINDOW_ACK_SIZE -> {
                                if (state.buffer.size >= 4) {
                                    ackWindowSize = ByteBuffer.wrap(state.buffer).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                                }
                            }
                            TYPE_USER_CONTROL -> {
                                if (state.buffer.size >= 6) {
                                    val eventType = ((state.buffer[0].toInt() and 0xFF) shl 8) or (state.buffer[1].toInt() and 0xFF)
                                    if (eventType == 0x0006) {
                                        // Ping Request -> send Ping Response
                                        val pingData = state.buffer.copyOfRange(2, 6)
                                        sendPingResponse(pingData)
                                    }
                                }
                            }
                            TYPE_COMMAND_AMF0 -> {
                                try {
                                    val bais = ByteArrayInputStream(state.buffer)
                                    val cmdName = Amf0.readValue(bais)?.toString() ?: ""
                                    val transId = (Amf0.readValue(bais) as? Number)?.toDouble() ?: 0.0
                                    val propObj = Amf0.readValue(bais)
                                    val infoObj = Amf0.readValue(bais)
                                    Log.d(TAG, "Server AMF0: $cmdName, info: $infoObj")
                                    if (cmdName == "onStatus" && infoObj is Map<*, *>) {
                                        val level = infoObj["level"]?.toString()
                                        val code = infoObj["code"]?.toString()
                                        if (level == "error") {
                                            if (isPublishing.getAndSet(false)) {
                                                onError("Stream status error: $code")
                                            }
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        state.received = 0
                    }

                    if (totalBytesRead - lastAckBytes >= ackWindowSize) {
                        sendAcknowledgement(totalBytesRead)
                        lastAckBytes = totalBytesRead
                    }
                } catch (e: Exception) {
                    if (isPublishing.get()) {
                        Log.w(TAG, "Reader thread exception: ${e.message}")
                    }
                    break
                }
            }
        }, "VeloStream-RTMP-Reader").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun sendPingResponse(pingData: ByteArray) {
        val out = outStream ?: return
        val payload = ByteArray(6)
        payload[0] = 0x00
        payload[1] = 0x07 // Event 7: Ping Response
        System.arraycopy(pingData, 0, payload, 2, minOf(4, pingData.size))
        sendRtmpMessage(CSID_CONTROL, TYPE_USER_CONTROL, 0, 0, payload, out)
        Log.d(TAG, "Sent RTMP Ping Response")
    }

    private fun sendAcknowledgement(sequenceNumber: Long) {
        val out = outStream ?: return
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((sequenceNumber and 0xFFFFFFFFL).toInt()).array()
        sendRtmpMessage(CSID_CONTROL, TYPE_ACKNOWLEDGEMENT, 0, 0, payload, out)
    }

    private fun sendWindowAckSize(size: Int) {
        val out = outStream ?: return
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()
        sendRtmpMessage(CSID_CONTROL, TYPE_WINDOW_ACK_SIZE, 0, 0, payload, out)
    }

    private fun sendSetPeerBandwidth(size: Int, limitType: Int) {
        val out = outStream ?: return
        val payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).putInt(size).put(limitType.toByte()).array()
        sendRtmpMessage(CSID_CONTROL, TYPE_SET_PEER_BANDWIDTH, 0, 0, payload, out)
    }

    private fun doHandshake() {
        val inS = inStream ?: throw IllegalStateException("Input stream is null")
        val outS = outStream ?: throw IllegalStateException("Output stream is null")

        // 1. Client sends C0 (0x03) + C1 (1536 bytes)
        outS.write(0x03)
        val c1 = ByteArray(1536)
        SecureRandom().nextBytes(c1)
        c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0
        outS.write(c1)
        outS.flush()

        // 2. Server sends S0 (1 byte) + S1 (1536 bytes)
        val s0 = inS.read()
        if (s0 != 0x03) {
            throw IllegalStateException("Unexpected S0 version: $s0")
        }
        val s1 = readFully(inS, 1536)

        // 3. Client sends C2 (echoes S1)
        outS.write(s1)
        outS.flush()

        // 4. Server sends S2 (1536 bytes)
        readFully(inS, 1536)
    }

    private fun sendChunkSize(size: Int) {
        val out = outStream ?: return
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()
        sendRtmpMessage(CSID_CONTROL, TYPE_SET_CHUNK_SIZE, 0, 0, payload, out)
    }

    private fun sendConnectCommand(app: String, tcUrl: String) {
        val out = outStream ?: return
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "connect")
        Amf0.writeNumber(body, 1.0) // Transaction ID
        val obj = mapOf<String, Any?>(
            "app" to app,
            "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)",
            "tcUrl" to tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 0x0400.toDouble(), // AAC
            "videoCodecs" to 0x0080.toDouble(), // AVC
            "videoFunction" to 1.0
        )
        Amf0.writeObject(body, obj)
        sendRtmpMessage(CSID_COMMAND, TYPE_COMMAND_AMF0, 0, 0, body.toByteArray(), out)
    }

    private fun sendReleaseStream(streamKey: String) {
        val out = outStream ?: return
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "releaseStream")
        Amf0.writeNumber(body, 2.0)
        Amf0.writeNull(body)
        Amf0.writeString(body, streamKey)
        sendRtmpMessage(CSID_COMMAND, TYPE_COMMAND_AMF0, 0, 0, body.toByteArray(), out)
    }

    private fun sendFCPublish(streamKey: String) {
        val out = outStream ?: return
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "FCPublish")
        Amf0.writeNumber(body, 3.0)
        Amf0.writeNull(body)
        Amf0.writeString(body, streamKey)
        sendRtmpMessage(CSID_COMMAND, TYPE_COMMAND_AMF0, 0, 0, body.toByteArray(), out)
    }

    private fun sendCreateStream() {
        val out = outStream ?: return
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "createStream")
        Amf0.writeNumber(body, 4.0)
        Amf0.writeNull(body)
        sendRtmpMessage(CSID_COMMAND, TYPE_COMMAND_AMF0, 0, 0, body.toByteArray(), out)
    }

    private fun sendPublish(streamKey: String) {
        val out = outStream ?: return
        val body = ByteArrayOutputStream()
        Amf0.writeString(body, "publish")
        Amf0.writeNumber(body, 5.0)
        Amf0.writeNull(body)
        Amf0.writeString(body, streamKey)
        Amf0.writeString(body, "live")
        sendRtmpMessage(CSID_COMMAND, TYPE_COMMAND_AMF0, 0, streamId, body.toByteArray(), out)
    }

    data class ServerCommand(
        val name: String,
        val transactionId: Double,
        val propObj: Any?,
        val infoObj: Any?
    )

    /**
     * Reads incoming RTMP chunk stream until an AMF0 command packet is received,
     * correctly handling chunk reassembly, server chunk size changes, and skipping control packets.
     */
    private fun readServerCommand(
        expectedTransactionId: Double? = null,
        expectedCommand: String? = null,
        timeoutMs: Long = 8000
    ): ServerCommand? {
        val inS = inStream ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val b0 = inS.read()
            if (b0 == -1) break

            val fmt = (b0 shr 6) and 0x03
            var csid = b0 and 0x3F
            if (csid == 0) {
                csid = 64 + inS.read()
            } else if (csid == 1) {
                val b1 = inS.read()
                val b2 = inS.read()
                csid = 64 + b1 + (b2 shl 8)
            }

            val state = activeMessages.getOrPut(csid) { MessageState() }

            when (fmt) {
                0 -> {
                    // 11 bytes: 3 timestamp, 3 len, 1 type, 4 streamId
                    val header = readFully(inS, 11)
                    val ts = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                    val len = ((header[3].toInt() and 0xFF) shl 16) or ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                    val type = header[6].toInt() and 0xFF
                    if (ts == 0xFFFFFF) {
                        readFully(inS, 4)
                    }
                    state.type = type
                    state.length = len
                    state.received = 0
                    state.buffer = ByteArray(len)
                }
                1 -> {
                    // 7 bytes: 3 delta, 3 len, 1 type
                    val header = readFully(inS, 7)
                    val delta = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                    val len = ((header[3].toInt() and 0xFF) shl 16) or ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                    val type = header[6].toInt() and 0xFF
                    if (delta == 0xFFFFFF) {
                        readFully(inS, 4)
                    }
                    state.type = type
                    state.length = len
                    state.received = 0
                    state.buffer = ByteArray(len)
                }
                2 -> {
                    // 3 bytes delta, length and type unchanged
                    val header = readFully(inS, 3)
                    val delta = ((header[0].toInt() and 0xFF) shl 16) or ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                    if (delta == 0xFFFFFF) {
                        readFully(inS, 4)
                    }
                    state.received = 0
                    state.buffer = ByteArray(state.length)
                }
                3 -> {
                    // Continuation chunk: format remains same
                }
            }

            val toRead = minOf(serverChunkSize, state.length - state.received)
            if (toRead > 0) {
                val chunk = readFully(inS, toRead)
                System.arraycopy(chunk, 0, state.buffer, state.received, toRead)
                state.received += toRead
            }

            // If message is complete
            if (state.received >= state.length && state.length > 0) {
                val finishedType = state.type
                val finishedData = state.buffer
                state.received = 0

                when (finishedType) {
                    TYPE_SET_CHUNK_SIZE -> {
                        if (finishedData.size >= 4) {
                            val newSize = ByteBuffer.wrap(finishedData).order(ByteOrder.BIG_ENDIAN).int
                            if (newSize > 0) {
                                serverChunkSize = newSize
                                Log.d(TAG, "Server updated chunk size: $serverChunkSize")
                            }
                        }
                    }
                    TYPE_WINDOW_ACK_SIZE, TYPE_SET_PEER_BANDWIDTH -> {
                        // Handled silently
                    }
                    TYPE_USER_CONTROL -> {
                        if (finishedData.size >= 6) {
                            val eventType = ((finishedData[0].toInt() and 0xFF) shl 8) or (finishedData[1].toInt() and 0xFF)
                            if (eventType == 0x0006) {
                                val pingData = finishedData.copyOfRange(2, 6)
                                sendPingResponse(pingData)
                            }
                        }
                    }
                    TYPE_COMMAND_AMF0 -> {
                        try {
                            val bais = ByteArrayInputStream(finishedData)
                            val cmdName = Amf0.readValue(bais)?.toString() ?: ""
                            val transId = (Amf0.readValue(bais) as? Number)?.toDouble() ?: 0.0
                            val propObj = Amf0.readValue(bais)
                            val infoObj = Amf0.readValue(bais)
                            Log.d(TAG, "Server command parsed: $cmdName, transId=$transId, info=$infoObj")

                            val cmd = ServerCommand(cmdName, transId, propObj, infoObj)
                            if (cmdName == "_error") {
                                return cmd
                            }
                            if (expectedTransactionId != null && transId == expectedTransactionId) {
                                return cmd
                            }
                            if (expectedCommand != null && cmdName == expectedCommand) {
                                return cmd
                            }
                            if (expectedTransactionId == null && expectedCommand == null) {
                                return cmd
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed parsing AMF0 command: ${e.message}")
                        }
                    }
                }
            }
        }
        return null
    }

    private fun sendRtmpMessage(
        csid: Int,
        messageType: Int,
        timestamp: Long,
        streamId: Int,
        payload: ByteArray,
        out: OutputStream
    ) {
        synchronized(writeLock) {
            val length = payload.size
            var offset = 0
            var isFirstChunk = true

            while (offset < length) {
                val chunkSize = minOf(outgoingChunkSize, length - offset)
                if (isFirstChunk) {
                    // Type 0 Chunk Header: 1 byte basic + 11 bytes message header
                    val basicHeader = (0 shl 6) or (csid and 0x3F)
                    out.write(basicHeader)

                    // Timestamp (3 bytes)
                    val ts = if (timestamp >= 0xFFFFFF) 0xFFFFFFL else timestamp
                    out.write(((ts shr 16) and 0xFF).toInt())
                    out.write(((ts shr 8) and 0xFF).toInt())
                    out.write((ts and 0xFF).toInt())

                    // Message length (3 bytes)
                    out.write(((length shr 16) and 0xFF))
                    out.write(((length shr 8) and 0xFF))
                    out.write((length and 0xFF))

                    // Message type (1 byte)
                    out.write(messageType)

                    // Stream ID (4 bytes little endian)
                    out.write(streamId and 0xFF)
                    out.write((streamId shr 8) and 0xFF)
                    out.write((streamId shr 16) and 0xFF)
                    out.write((streamId shr 24) and 0xFF)

                    // Extended timestamp if >= 0xFFFFFF
                    if (timestamp >= 0xFFFFFF) {
                        val ext = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(timestamp.toInt()).array()
                        out.write(ext)
                    }
                    isFirstChunk = false
                } else {
                    // Type 3 Chunk Header: 1 byte basic header
                    val basicHeader = (3 shl 6) or (csid and 0x3F)
                    out.write(basicHeader)
                    if (timestamp >= 0xFFFFFF) {
                        val ext = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(timestamp.toInt()).array()
                        out.write(ext)
                    }
                }

                out.write(payload, offset, chunkSize)
                offset += chunkSize
            }
            out.flush()
        }
    }

    fun disconnect() {
        isPublishing.set(false)
        isConnected.set(false)
        outgoingChunkSize = 128
        serverChunkSize = 128
        avcSequenceHeaderDispatched = false
        hevcSequenceHeaderDispatched = false
        aacSequenceHeaderDispatched = false
        hasSentFirstKeyframe = false
        activeMessages.clear()
        senderThread?.interrupt()
        senderThread = null
        readerThread?.interrupt()
        readerThread = null
        packetQueue.clear()
        pendingMetadata = null
        pendingAvcHeader = null
        pendingHevcHeader = null
        pendingAacHeader = null

        try {
            inStream?.close()
        } catch (_: Exception) {}
        try {
            outStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = null
        inStream = null
        outStream = null
    }

    private fun readFully(inS: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var total = 0
        while (total < length) {
            val count = inS.read(buf, total, length - total)
            if (count == -1) throw IllegalStateException("EOF reached prematurely after $total of $length bytes")
            total += count
        }
        return buf
    }

    data class TargetAddress(
        val host: String,
        val port: Int,
        val app: String,
        val streamKey: String,
        val tcUrl: String,
        val isSsl: Boolean
    )

    private fun parseUrl(serverUrl: String, customStreamKey: String): TargetAddress {
        var cleanUrl = serverUrl.trim()
        val isSsl = cleanUrl.startsWith("rtmps://", ignoreCase = true)
        val defaultPort = if (isSsl) DEFAULT_RTMPS_PORT else DEFAULT_RTMP_PORT

        if (!cleanUrl.startsWith("rtmp://", ignoreCase = true) && !cleanUrl.startsWith("rtmps://", ignoreCase = true)) {
            cleanUrl = "rtmp://$cleanUrl"
        }

        val uri = try {
            URI(cleanUrl)
        } catch (e: Exception) {
            URI("rtmp://a.rtmp.youtube.com/live2")
        }

        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else defaultPort
        val path = uri.path?.trimStart('/') ?: "live2"

        val parts = path.split('/')
        val app = if (parts.isNotEmpty() && parts[0].isNotBlank()) parts[0] else "live2"
        val keyInUrl = if (parts.size > 1) parts.drop(1).joinToString("/") else ""

        val finalKey = if (customStreamKey.isNotBlank()) customStreamKey.trim() else keyInUrl
        
        // Standard RTMP servers (e.g. YouTube, Twitch) require tcUrl without port if default port
        val tcUrl = if (port == defaultPort) {
            "${if (isSsl) "rtmps" else "rtmp"}://$host/$app"
        } else {
            "${if (isSsl) "rtmps" else "rtmp"}://$host:$port/$app"
        }

        return TargetAddress(
            host = host,
            port = port,
            app = app,
            streamKey = finalKey,
            tcUrl = tcUrl,
            isSsl = isSsl
        )
    }

    private fun createAndConnectSocket(target: TargetAddress, timeoutMs: Int): Socket {
        val allAddresses = try {
            val addrs = java.net.InetAddress.getAllByName(target.host)
            addrs.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        } catch (_: Exception) {
            listOf(java.net.InetAddress.getByName(target.host))
        }

        var lastEx: Exception? = null
        for (addr in allAddresses) {
            try {
                if (target.isSsl) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = sslFactory.createSocket() as SSLSocket
                    try {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(target.host))
                        ssl.sslParameters = params
                    } catch (_: Exception) {}
                    ssl.tcpNoDelay = true
                    ssl.sendBufferSize = 256 * 1024
                    ssl.receiveBufferSize = 64 * 1024
                    ssl.connect(InetSocketAddress(addr, target.port), timeoutMs)
                    ssl.startHandshake()
                    return ssl
                } else {
                    val plain = Socket()
                    plain.tcpNoDelay = true
                    plain.sendBufferSize = 256 * 1024
                    plain.receiveBufferSize = 64 * 1024
                    plain.connect(InetSocketAddress(addr, target.port), timeoutMs)
                    return plain
                }
            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "Failed connecting to ${addr.hostAddress}:${target.port}: ${e.message}")
            }
        }
        throw (lastEx ?: java.io.IOException("Unable to connect to ${target.host}:${target.port}"))
    }

    /**
     * Diagnostic connection test: verifies TCP/TLS connection, handshake, and handshake latency.
     */
    fun testConnection(serverUrl: String, streamKey: String): Pair<Boolean, String> {
        val startTime = System.currentTimeMillis()
        try {
            val target = parseUrl(serverUrl, streamKey)
            val testSocket = createAndConnectSocket(target, 5000)

            val testIn = BufferedInputStream(testSocket.getInputStream())
            val testOut = BufferedOutputStream(testSocket.getOutputStream())

            // C0 + C1
            testOut.write(0x03)
            val c1 = ByteArray(1536)
            testOut.write(c1)
            testOut.flush()

            val s0 = testIn.read()
            if (s0 != 0x03) {
                testSocket.close()
                return Pair(false, "Server rejected RTMP handshake (S0: $s0)")
            }
            val s1 = readFully(testIn, 1536)
            testOut.write(s1)
            testOut.flush()
            readFully(testIn, 1536)

            val latency = System.currentTimeMillis() - startTime
            testSocket.close()
            return Pair(true, "Connected successfully! Handshake verified (${target.host}:${target.port}, ${latency}ms latency)")
        } catch (e: Exception) {
            return Pair(false, "Connection test failed: ${e.localizedMessage ?: e.message}")
        }
    }
}
