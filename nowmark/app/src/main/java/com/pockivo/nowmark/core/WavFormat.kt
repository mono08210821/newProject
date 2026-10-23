package com.pockivo.nowmark.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical 44-byte PCM WAV header helpers. */
object WavFormat {
    const val HEADER_SIZE = 44

    /** RIFF sizes are 32-bit; anything larger cannot be a plain WAV file. */
    const val MAX_DATA_BYTES: Long = 0xFFFFFFFFL - 36L

    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        require(dataBytes in 0..MAX_DATA_BYTES) { "WAV data too large: $dataBytes" }
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36L + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(byteRate)
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long) {
        val frames: Long get() = dataBytes / (channels * bitsPerSample / 8)
        val durationMs: Long get() = frames * 1000L / sampleRate
    }

    /** Parses a header written by [header]; returns null for anything else. */
    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < HEADER_SIZE) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt " || tag(36) != "data") return null
        if (b.getShort(20).toInt() != 1) return null
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        val bits = b.getShort(34).toInt()
        val data = b.getInt(40).toLong() and 0xFFFFFFFFL
        if (channels <= 0 || rate <= 0 || bits <= 0) return null
        return Info(rate, channels, bits, data)
    }
}
