package com.example.rtmp

data class RtmpPacket(
    val type: Int, // 8 for Audio, 9 for Video, 18 for Data
    val timestamp: Long,
    val payload: ByteArray,
    val isKeyframe: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtmpPacket
        return type == other.type && timestamp == other.timestamp && isKeyframe == other.isKeyframe
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isKeyframe.hashCode()
        return result
    }
}
