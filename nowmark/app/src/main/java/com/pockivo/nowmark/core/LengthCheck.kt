package com.pockivo.nowmark.core

import kotlin.math.abs

/**
 * Compares the audio we captured with the length of the saved file.
 *
 * A match only means the file length equals the captured audio length within the
 * tolerance below. It does NOT mean no speech was lost (input can be silenced by the
 * system while frames keep arriving), so input interruptions are reported separately.
 *
 * Tolerance:
 *  - WAV: PCM frames are copied 1:1, so the only error is rounding to ms (<= 1 frame): 2 ms.
 *  - M4A/AAC: the encoder adds 1024..2112 priming samples and pads the final 1024-sample
 *    frame, and MP4 duration may or may not subtract the priming (edit list). So we allow
 *    priming (2112) + one frame (1024) samples at the stream's rate, plus 10 ms.
 */
object LengthCheck {
    fun toleranceMs(format: AudioFormatKind, sampleRate: Int): Long = when (format) {
        AudioFormatKind.WAV -> 2L
        AudioFormatKind.M4A -> (2112L + 1024L) * 1000L / sampleRate + 10L
    }

    fun matches(format: AudioFormatKind, sampleRate: Int, capturedMs: Long, fileMs: Long): Boolean =
        abs(capturedMs - fileMs) <= toleranceMs(format, sampleRate)
}

enum class AudioFormatKind(val extension: String, val mime: String) {
    M4A("m4a", "audio/mp4"),
    WAV("wav", "audio/wav");

    companion object {
        fun from(value: String?): AudioFormatKind = entries.firstOrNull { it.name == value } ?: M4A
    }
}
