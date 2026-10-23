package com.pockivo.nowmark.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class PcmFormat(val sampleRate: Int, val channels: Int)

/**
 * Decodes any audio the platform supports (M4A/AAC, WAV, MP3...) to 16-bit PCM, optionally
 * only between [startUs] and [endUs]. Samples are cut at the exact frame, not at the
 * nearest codec frame, so shared clips contain nothing outside the selected range.
 */
object PcmDecoder {

    fun probe(context: Context, uri: Uri): Pair<PcmFormat, Long>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val f = ex.getTrackFormat(track)
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else -1L
            PcmFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) to dur
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /**
     * Calls [onFormat] once and then [onPcm] repeatedly. Returns false if decoding failed.
     * [isCancelled] is polled between buffers.
     */
    fun decode(
        context: Context,
        uri: Uri,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
        onFormat: (PcmFormat) -> Unit,
        onPcm: (ByteArray, Int, Int) -> Unit,
    ): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var formatSent = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var scratch = ByteArray(0)
            while (true) {
                if (isCancelled()) return false
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT
                    ) return false
                } else if (o >= 0) {
                    if (!formatSent) {
                        onFormat(PcmFormat(sampleRate, channels))
                        formatSent = true
                    }
                    val out = c.getOutputBuffer(o)!!
                    val bpf = channels * 2
                    var size = info.size
                    if (size > 0) {
                        if (scratch.size < size) scratch = ByteArray(size)
                        out.position(info.offset)
                        out.get(scratch, 0, size)
                        val bufStartUs = info.presentationTimeUs
                        val frames = size / bpf
                        var from = 0
                        var to = frames
                        if (bufStartUs < startUs) {
                            from = ((startUs - bufStartUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                        }
                        val bufEndUs = bufStartUs + frames * 1_000_000L / sampleRate
                        if (bufEndUs > endUs) {
                            to = ((endUs - bufStartUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                        }
                        if (to > from) onPcm(scratch, from * bpf, (to - from) * bpf)
                        size = 0
                        if (bufEndUs >= endUs) {
                            c.releaseOutputBuffer(o, false)
                            return true
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        } catch (e: Exception) {
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    /** Exact length of a file by walking its samples (no decoding). */
    fun measureDurationUs(context: Context, uri: Uri): Long? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val f = ex.getTrackFormat(track)
            f.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }
}
