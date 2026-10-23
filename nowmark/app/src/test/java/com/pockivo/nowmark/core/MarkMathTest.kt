package com.pockivo.nowmark.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkMathTest {
    private fun mark(t: Long) = MarkMath.reserve(t, 30_000, 10_000)

    @Test fun markAtTwoSecondsStartsAtZero() {
        assertEquals(TimeRange(0, 12_000), MarkMath.clamp(mark(2_000), 120_000))
    }

    @Test fun overlappingMarksAreMergedOnce() {
        val ranges = listOf(mark(45_000), mark(50_000)).mapNotNull { MarkMath.clamp(it, 60_000) }
        assertEquals(listOf(TimeRange(15_000, 60_000)), MarkMath.union(ranges))
        assertEquals(45_000, MarkMath.totalMs(ranges))
    }

    @Test fun stopBeforeAfterWindowCutsAtFileEnd() {
        assertEquals(TimeRange(15_000, 48_000), MarkMath.clamp(mark(45_000), 48_000))
    }

    @Test fun rangeOutsideAudioDisappears() {
        assertNull(MarkMath.clamp(TimeRange(70_000, 80_000), 60_000))
    }

    @Test fun separateRangesStaySeparate() {
        val u = MarkMath.union(listOf(TimeRange(0, 10), TimeRange(20, 30), TimeRange(5, 12)))
        assertEquals(listOf(TimeRange(0, 12), TimeRange(20, 30)), u)
    }

    @Test fun widenStaysInsideAudio() {
        assertEquals(TimeRange(0, 60_000), MarkMath.widen(TimeRange(10_000, 50_000), 15_000, 15_000, 60_000))
    }

    @Test fun listeningTimeAtSpeed() {
        assertEquals(40_000, MarkMath.listeningTimeMs(50_000, 1.25f))
    }
}
