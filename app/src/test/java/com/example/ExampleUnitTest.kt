package com.example

import com.example.model.AspectRatioMode
import com.example.model.BitrateModeType
import com.example.model.StreamConfig
import com.example.model.VideoResolution
import com.example.rtmp.Amf0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ExampleUnitTest {

    @Test
    fun testAmf0StringSerialization() {
        val out = ByteArrayOutputStream()
        Amf0.writeString(out, "testStreamKey")
        val bytes = out.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val readVal = Amf0.readValue(input)
        assertEquals("testStreamKey", readVal)
    }

    @Test
    fun testAmf0NumberSerialization() {
        val out = ByteArrayOutputStream()
        Amf0.writeNumber(out, 1080.0)
        val bytes = out.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val readVal = Amf0.readValue(input)
        assertEquals(1080.0, readVal)
    }

    @Test
    fun testDefaultConfigPreservesHighPerformance() {
        val config = StreamConfig()
        assertEquals(VideoResolution.RES_1080P, config.resolution)
        assertEquals(AspectRatioMode.STRETCH_16_9, config.aspectRatioMode)
        assertEquals(60, config.fps)
        assertEquals(8000, config.bitrateKbps)
        assertEquals(BitrateModeType.CBR, config.bitrateMode)
    }

    @Test
    fun testDurationCalculationAndFormatting() {
        fun formatDuration(totalSeconds: Long): String {
            return when {
                totalSeconds >= 3600 -> {
                    val hours = totalSeconds / 3600
                    val mins = (totalSeconds % 3600) / 60
                    val secs = totalSeconds % 60
                    String.format("%d:%02d:%02d", hours, mins, secs)
                }
                totalSeconds > 0 -> {
                    val mins = totalSeconds / 60
                    val secs = totalSeconds % 60
                    String.format("%02d:%02d", mins, secs)
                }
                else -> "00:00"
            }
        }

        assertEquals("00:00", formatDuration(0))
        assertEquals("00:15", formatDuration(15))
        assertEquals("01:30", formatDuration(90))
        assertEquals("1:01:05", formatDuration(3665))
    }

    @Test
    fun testBitrateConversion() {
        val bytes = 1_000_000L // 1 MB
        val deltaMs = 1000L // 1 sec
        val kbps = (bytes * 8000L) / (deltaMs * 1000L)
        assertEquals(8000L, kbps)
        val mbpsStr = String.format("%.1f", kbps / 1000f)
        assertEquals("8.0", mbpsStr)
    }

    @Test
    fun testFlvVideoHeaderGeneration() {
        val sampleNalu = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65) // Keyframe
        val isKeyframe = true
        val flvPayload = ByteArray(5 + sampleNalu.size)
        flvPayload[0] = if (isKeyframe) 0x17.toByte() else 0x27.toByte()
        flvPayload[1] = 0x01.toByte() // AVC NALU
        flvPayload[2] = 0x00.toByte()
        flvPayload[3] = 0x00.toByte()
        flvPayload[4] = 0x00.toByte()
        System.arraycopy(sampleNalu, 0, flvPayload, 5, sampleNalu.size)

        assertEquals(0x17.toByte(), flvPayload[0])
        assertEquals(0x01.toByte(), flvPayload[1])
        assertEquals(0x00.toByte(), flvPayload[2])
        assertEquals(10, flvPayload.size)
    }

    @Test
    fun testFlvAudioHeaderGeneration() {
        val sampleAac = byteArrayOf(0x21, 0x10)
        val flvPayload = ByteArray(2 + sampleAac.size)
        flvPayload[0] = 0xAF.toByte() // AAC 44.1kHz 16-bit stereo
        flvPayload[1] = 0x01.toByte() // Raw AAC frame
        System.arraycopy(sampleAac, 0, flvPayload, 2, sampleAac.size)

        assertEquals(0xAF.toByte(), flvPayload[0])
        assertEquals(0x01.toByte(), flvPayload[1])
        assertEquals(4, flvPayload.size)
    }

    @Test
    fun testAspectRatioModes() {
        assertEquals("Stretch to 16:9", AspectRatioMode.STRETCH_16_9.label)
        assertEquals("Native Aspect Ratio", AspectRatioMode.NATIVE.label)
        assertEquals("Fit 16:9 (Letterbox)", AspectRatioMode.FIT_16_9.label)
    }
}
