package com.pockivo.nowmark.core

import java.util.Locale

object TimeFormat {
    /** 0:05, 12:34, 1:02:03 — uses the given locale's digits. */
    fun duration(ms: Long, locale: Locale = Locale.getDefault()): String {
        val total = (if (ms < 0) 0 else ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s)
        else String.format(locale, "%d:%02d", m, s)
    }
}
