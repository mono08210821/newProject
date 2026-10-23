package com.pockivo.nowmark.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CoreRulesTest {
    @Test fun interstitialNeedsThreeSavesAndFiveMinutes() {
        val f = AdFrequency()
        assertFalse(f.isDue(2, 0, 1_000_000))
        assertTrue(f.isDue(3, 0, 1_000_000))
        assertFalse(f.isDue(5, 1_000_000, 1_000_000 + 4 * 60_000))
        assertTrue(f.isDue(3, 1_000_000, 1_000_000 + 5 * 60_000))
    }

    @Test fun wavHeaderRoundTrip() {
        val h = WavFormat.header(44_100, 1, 16, 88_200)
        val info = WavFormat.parse(h)
        assertNotNull(info)
        assertEquals(44_100, info!!.frames)
        assertEquals(1_000, info.durationMs)
    }

    @Test fun zipNamesCannotEscape() {
        assertNull(ZipSafety.safeName("../evil"))
        assertNull(ZipSafety.safeName("/etc/passwd"))
        assertNull(ZipSafety.safeName("a/../../b"))
        assertNull(ZipSafety.safeName("C:/x"))
        assertNull(ZipSafety.safeName("a\\b"))
        assertEquals("audio/x.m4a", ZipSafety.safeName("./audio//x.m4a"))
    }

    @Test fun lengthTolerance() {
        assertTrue(LengthCheck.matches(AudioFormatKind.M4A, 44_100, 60_000, 60_046))
        assertFalse(LengthCheck.matches(AudioFormatKind.M4A, 44_100, 60_000, 61_000))
        assertTrue(LengthCheck.matches(AudioFormatKind.WAV, 44_100, 60_000, 60_001))
        assertFalse(LengthCheck.matches(AudioFormatKind.WAV, 44_100, 60_000, 60_050))
    }

    @Test fun durationFormat() {
        assertEquals("0:05", TimeFormat.duration(5_400, Locale.US))
        assertEquals("58:14", TimeFormat.duration(3_494_000, Locale.US))
        assertEquals("1:12:40", TimeFormat.duration(4_360_000, Locale.US))
    }
}
