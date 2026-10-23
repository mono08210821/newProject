package com.pockivo.nowmark.core

/**
 * Interstitial frequency rule: at most one interstitial per [savesPerAd] successful saves
 * AND at least [minIntervalMs] since the previous interstitial. Both must hold.
 */
class AdFrequency(
    val savesPerAd: Int = 3,
    val minIntervalMs: Long = 5 * 60_000L,
) {
    fun isDue(savesSinceLastAd: Int, lastAdAtMs: Long, nowMs: Long): Boolean {
        if (savesSinceLastAd < savesPerAd) return false
        if (lastAdAtMs <= 0L) return true
        return nowMs - lastAdAtMs >= minIntervalMs
    }
}
