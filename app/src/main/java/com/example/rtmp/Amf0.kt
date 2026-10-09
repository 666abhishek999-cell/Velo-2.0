package com.example.rtmp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Amf0 {

    const val TYPE_NUMBER = 0x00
    const val TYPE_BOOLEAN = 0x01
    const val TYPE_STRING = 0x02
    const val TYPE_OBJECT = 0x03
    const val TYPE_NULL = 0x05
    const val TYPE_ECMA_ARRAY = 0x08
    const val OBJECT_END = 0x09

    fun writeNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(TYPE_NUMBER)
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putDouble(value)
        out.write(buf.array())
    }

    fun writeBoolean(out: ByteArrayOutputStream, value: Boolean) {
        out.write(TYPE_BOOLEAN)
        out.write(if (value) 1 else 0)
    }

    fun writeString(out: ByteArrayOutputStream, value: String) {
        out.write(TYPE_STRING)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(bytes.size.toShort())
        out.write(buf.array())
        out.write(bytes)
    }

    fun writeNull(out: ByteArrayOutputStream) {
        out.write(TYPE_NULL)
    }

    fun writeObject(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_OBJECT)
        for ((k, v) in properties) {
            writeProperty(out, k, v)
        }
        // Object end marker: 2-byte empty name + 0x09
        out.write(0)
        out.write(0)
        out.write(OBJECT_END)
    }

    fun writeEcmaArray(out: ByteArrayOutputStream, properties: Map<String, Any?>) {
        out.write(TYPE_ECMA_ARRAY)
        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        countBuf.putInt(properties.size)
        out.write(countBuf.array())
        for ((k, v) in properties) {
            writeProperty(out, k, v)
        }
        out.write(0)
        out.write(0)
        out.write(OBJECT_END)
    }

    private fun writeProperty(out: ByteArrayOutputStream, key: String, value: Any?) {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putShort(keyBytes.size.toShort())
        out.write(lenBuf.array())
        out.write(keyBytes)

        when (value) {
            null -> writeNull(out)
            is Double -> writeNumber(out, value)
            is Number -> writeNumber(out, value.toDouble())
            is Boolean -> writeBoolean(out, value)
            is String -> writeString(out, value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                writeObject(out, value as Map<String, Any?>)
            }
            else -> writeString(out, value.toString())
        }
    }

    fun readValue(input: InputStream): Any? {
        val type = input.read()
        if (type == -1) return null
        return when (type) {
            TYPE_NUMBER -> {
                val buf = readBytes(input, 8)
                ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).double
            }
            TYPE_BOOLEAN -> input.read() == 1
            TYPE_STRING -> {
                val lenBuf = readBytes(input, 2)
                val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                val strBytes = readBytes(input, len)
                String(strBytes, Charsets.UTF_8)
            }
            TYPE_NULL -> null
            TYPE_OBJECT -> {
                val obj = mutableMapOf<String, Any?>()
                while (true) {
                    val lenBuf = readBytes(input, 2)
                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                    if (len == 0) {
                        val end = input.read()
                        if (end == OBJECT_END) break
                    }
                    val keyBytes = readBytes(input, len)
                    val key = String(keyBytes, Charsets.UTF_8)
                    val value = readValue(input)
                    obj[key] = value
                }
                obj
            }
            TYPE_ECMA_ARRAY -> {
                readBytes(input, 4) // Skip array length
                val obj = mutableMapOf<String, Any?>()
                while (true) {
                    val lenBuf = readBytes(input, 2)
                    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                    if (len == 0) {
                        val end = input.read()
                        if (end == OBJECT_END) break
                    }
                    val keyBytes = readBytes(input, len)
                    val key = String(keyBytes, Charsets.UTF_8)
                    val value = readValue(input)
                    obj[key] = value
                }
                obj
            }
            else -> null
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count == -1) break
            read += count
        }
        return buffer
    }
}
