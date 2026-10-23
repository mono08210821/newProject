package com.pockivo.nowmark.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.pockivo.nowmark.core.WavFormat
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Receives interleaved 16-bit little-endian PCM. */
interface PcmSink {
    fun write(pcm: ByteArray, offset: Int, length: Int)
    /** Finishes the container. Returns the number of PCM frames written. */
    fun finish(): Long
    fun abort()
}

/** Streams PCM through the platform AAC-LC encoder into an MPEG-4 (.m4a) file. */
class AacSink(
    fd: FileDescriptor,
    private val sampleRate: Int,
    private val channels: Int,
    bitRate: Int,
) : PcmSink {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var framesIn = 0L
    private val bytesPerFrame = channels * 2

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf: ByteBuffer = codec.getInputBuffer(idx)!!
                buf.clear()
                var n = minOf(buf.remaining(), end - pos)
                n -= n % bytesPerFrame
                if (n <= 0) n = minOf(bytesPerFrame, end - pos)
                buf.put(pcm, pos, n)
                val ptsUs = framesIn * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                framesIn += n / bytesPerFrame
                pos += n
            }
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish(): Long {
        var queued = false
        while (!queued) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, framesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(false)
            }
        }
        drain(true)
        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        return framesIn
    }

    override fun abort() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}

/**
 * Writes a canonical WAV file. The header is written first with size 0 and patched at the
 * end, so the stream must come from a seekable descriptor (opened "rw").
 */
class WavSink(
    fd: FileDescriptor,
    private val sampleRate: Int,
    private val channels: Int,
) : PcmSink {
    private val out = FileOutputStream(fd)
    private var dataBytes = 0L

    init {
        out.channel.position(0)
        out.channel.truncate(0)
        out.write(WavFormat.header(sampleRate, channels, 16, 0))
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int) {
        if (dataBytes + length > WavFormat.MAX_DATA_BYTES) throw java.io.IOException("WAV size limit")
        out.write(pcm, offset, length)
        dataBytes += length
    }

    override fun finish(): Long {
        out.flush()
        out.channel.write(ByteBuffer.wrap(WavFormat.header(sampleRate, channels, 16, dataBytes)), 0)
        out.channel.force(true)
        out.fd.sync()
        return dataBytes / (channels * 2)
    }

    override fun abort() { }
}
