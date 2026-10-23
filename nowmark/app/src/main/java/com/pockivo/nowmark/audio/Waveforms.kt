package com.pockivo.nowmark.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Waveforms are real peaks: one byte per 100 ms of audio. Recordings made in the app keep
 * the peaks computed while recording; imported/trimmed audio is decoded once and cached.
 */
object Waveforms {
    const val STEP_MS = 100L

    private fun dir(context: Context) = File(context.filesDir, "peaks").apply { mkdirs() }
    fun file(context: Context, id: String) = File(dir(context), "$id.bin")

    fun adoptRecordedPeaks(context: Context, id: String, source: File) {
        if (!source.exists()) return
        val target = file(context, id)
        if (!source.renameTo(target)) source.copyTo(target, overwrite = true)
    }

    fun delete(context: Context, id: String) { file(context, id).delete() }

    suspend fun load(context: Context, id: String, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val f = file(context, id)
        if (f.exists() && f.length() > 0) return@withContext f.readBytes()
        val out = java.io.ByteArrayOutputStream()
        var fmt: PcmFormat? = null
        var framesInStep = 0L
        var peak = 0
        val ok = PcmDecoder.decode(
            context, uri,
            onFormat = { fmt = it },
            onPcm = { buf, off, len ->
                val f0 = fmt!!
                val stepFrames = f0.sampleRate * STEP_MS / 1000
                val bpf = f0.channels * 2
                var i = off
                while (i + 1 < off + len) {
                    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    val a = abs(s.toShort().toInt())
                    if (a > peak) peak = a
                    if ((i - off) % bpf == bpf - 2) {
                        framesInStep++
                        if (framesInStep >= stepFrames) {
                            out.write((peak * 255 / 32768).coerceIn(0, 255))
                            peak = 0
                            framesInStep = 0
                        }
                    }
                    i += 2
                }
            },
        )
        if (!ok) return@withContext null
        val bytes = out.toByteArray()
        try { f.writeBytes(bytes) } catch (_: Exception) { }
        bytes
    }

    /** Reduces peaks to [bins] bars in 0..1. */
    fun bars(peaks: ByteArray, bins: Int): FloatArray {
        if (peaks.isEmpty() || bins <= 0) return FloatArray(bins)
        val out = FloatArray(bins)
        for (b in 0 until bins) {
            val from = (b.toLong() * peaks.size / bins).toInt()
            val to = maxOf(from + 1, ((b + 1).toLong() * peaks.size / bins).toInt()).coerceAtMost(peaks.size)
            var m = 0
            for (i in from until to) m = maxOf(m, peaks[i].toInt() and 0xFF)
            out[b] = m / 255f
        }
        return out
    }
}
