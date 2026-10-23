package com.pockivo.nowmark.core

import kotlin.math.max
import kotlin.math.min

/** A half-open range of original-audio time in milliseconds. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Rules for "방금 중요했어요" marks. All times are positions in the recorded audio
 * (paused time is never counted), never wall-clock time.
 */
object MarkMath {
    const val DEFAULT_BEFORE_MS = 30_000L
    const val DEFAULT_AFTER_MS = 10_000L

    /** Range reserved when the user taps at audio time [tMs]; the end may lie in the future. */
    fun reserve(tMs: Long, beforeMs: Long, afterMs: Long): TimeRange =
        TimeRange(max(0L, tMs - beforeMs), tMs + afterMs)

    /** Cuts a reserved range to the real audio length. Returns null when nothing remains. */
    fun clamp(range: TimeRange, durationMs: Long): TimeRange? {
        val start = min(max(0L, range.startMs), durationMs)
        val end = min(max(0L, range.endMs), durationMs)
        return if (end > start) TimeRange(start, end) else null
    }

    /** Union of ranges of ONE recording, so overlapping marks are played once. */
    fun union(ranges: List<TimeRange>): List<TimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<TimeRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                out[out.size - 1] = TimeRange(last.startMs, max(last.endMs, r.endMs))
            } else {
                out.add(r)
            }
        }
        return out
    }

    /** Total listening time of one recording's marks, overlaps counted once. */
    fun totalMs(ranges: List<TimeRange>): Long = union(ranges).sumOf { it.lengthMs }

    /** Expected wall time to listen to [audioMs] at [speed]; kept separate from audio length. */
    fun listeningTimeMs(audioMs: Long, speed: Float): Long =
        if (speed <= 0f) audioMs else (audioMs / speed).toLong()

    /** Widens a range by [extraMs] on one or both sides, inside the audio. */
    fun widen(range: TimeRange, beforeMs: Long, afterMs: Long, durationMs: Long): TimeRange =
        TimeRange(max(0L, range.startMs - beforeMs), min(durationMs, range.endMs + afterMs))
}
