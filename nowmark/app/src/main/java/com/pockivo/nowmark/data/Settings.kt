package com.pockivo.nowmark.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.core.MarkMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Recording presets. Sample rates are ones every Android AAC encoder accepts. */
enum class Preset(val sampleRate: Int, val channels: Int, val aacBitrate: Int, val preferUnprocessed: Boolean) {
    VOICE(44_100, 1, 64_000, false),
    MEETING(44_100, 1, 96_000, false),
    MUSIC(48_000, 2, 192_000, true);

    companion object {
        fun from(v: String?): Preset = entries.firstOrNull { it.name == v } ?: MEETING
    }
}

enum class SortOrder { NEWEST, OLDEST, LONGEST, TITLE }

data class AppSettings(
    val preset: Preset = Preset.MEETING,
    val format: AudioFormatKind = AudioFormatKind.M4A,
    val markBeforeMs: Long = MarkMath.DEFAULT_BEFORE_MS,
    val markAfterMs: Long = MarkMath.DEFAULT_AFTER_MS,
    val vibrate: Boolean = true,
    val skipSilence: Boolean = false,
    val sort: SortOrder = SortOrder.NEWEST,
    val consentNoticeSeen: Boolean = false,
)

private val Context.store by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val preset = stringPreferencesKey("preset")
        val format = stringPreferencesKey("format")
        val before = longPreferencesKey("mark_before_ms")
        val after = longPreferencesKey("mark_after_ms")
        val vibrate = booleanPreferencesKey("vibrate")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val sort = stringPreferencesKey("sort")
        val consentSeen = booleanPreferencesKey("recording_consent_notice_seen")
        val savesSinceAd = intPreferencesKey("saves_since_interstitial")
        val lastAdAt = longPreferencesKey("last_interstitial_at")
    }

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            preset = Preset.from(p[K.preset]),
            format = AudioFormatKind.from(p[K.format]),
            markBeforeMs = p[K.before] ?: MarkMath.DEFAULT_BEFORE_MS,
            markAfterMs = p[K.after] ?: MarkMath.DEFAULT_AFTER_MS,
            vibrate = p[K.vibrate] ?: true,
            skipSilence = p[K.skipSilence] ?: false,
            sort = SortOrder.entries.firstOrNull { it.name == p[K.sort] } ?: SortOrder.NEWEST,
            consentNoticeSeen = p[K.consentSeen] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }

    suspend fun setPreset(v: Preset) = set(K.preset, v.name)
    suspend fun setFormat(v: AudioFormatKind) = set(K.format, v.name)
    suspend fun setMarkWindow(beforeMs: Long, afterMs: Long) {
        context.store.edit { it[K.before] = beforeMs; it[K.after] = afterMs }
    }
    suspend fun setVibrate(v: Boolean) = set(K.vibrate, v)
    suspend fun setSkipSilence(v: Boolean) = set(K.skipSilence, v)
    suspend fun setSort(v: SortOrder) = set(K.sort, v.name)
    suspend fun setConsentNoticeSeen() = set(K.consentSeen, true)

    // Interstitial frequency bookkeeping.
    suspend fun adCounters(): Pair<Int, Long> {
        val p = context.store.data.first()
        return (p[K.savesSinceAd] ?: 0) to (p[K.lastAdAt] ?: 0L)
    }
    suspend fun countSuccessfulSave() {
        context.store.edit { it[K.savesSinceAd] = (it[K.savesSinceAd] ?: 0) + 1 }
    }
    suspend fun interstitialShown(now: Long) {
        context.store.edit { it[K.savesSinceAd] = 0; it[K.lastAdAt] = now }
    }
}
