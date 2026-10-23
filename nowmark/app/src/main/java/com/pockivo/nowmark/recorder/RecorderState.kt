package com.pockivo.nowmark.recorder

import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.data.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, RECORDING, PAUSED, FINALIZING, SAVED, FAILED }

/** What we can say about the input right now. Never "healthy" when we cannot observe. */
enum class InputStatus { DETECTED, LOW, SILENCED_BY_SYSTEM, UNKNOWN }

enum class FailureKind { NONE, START_FAILED, WRITE_FAILED, STORAGE_FULL, ENCODE_FAILED }

data class LiveEvent(
    val type: String,
    val startMs: Long,
    val endMs: Long?,
    val wallTime: Long,
    val wallDurationMs: Long,
    val detail: String,
)

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val sessionId: String? = null,
    val preset: Preset = Preset.MEETING,
    val format: AudioFormatKind = AudioFormatKind.M4A,
    /** Audio captured so far (paused time excluded). */
    val audioMs: Long = 0,
    /** Audio position confirmed as written (fsync'ed) to the intermediate files. */
    val confirmedMs: Long = 0,
    val startedAtWall: Long = 0,
    val levels: List<Float> = emptyList(),
    val inputStatus: InputStatus = InputStatus.UNKNOWN,
    /** AudioDeviceInfo type of the routed input, or null when unknown. */
    val inputDeviceType: Int? = null,
    val marks: List<JournalMark> = emptyList(),
    val noteCount: Int = 0,
    val events: List<LiveEvent> = emptyList(),
    val freeBytes: Long = -1,
    val bytesPerSecondEstimate: Long = 0,
    /** Observing input silencing requires API 29+. */
    val silenceObservable: Boolean = false,
    val savedRecordingId: String? = null,
    val failure: FailureKind = FailureKind.NONE,
) {
    val isActive: Boolean get() = phase == Phase.RECORDING || phase == Phase.PAUSED || phase == Phase.FINALIZING
    val openInterruption: LiveEvent?
        get() = events.lastOrNull { it.type == "INPUT_SILENCED" && it.endMs == null }

    /** Estimated remaining minutes for intermediate + final files; always shown as an estimate. */
    val remainingMinutesEstimate: Long?
        get() = if (freeBytes < 0 || bytesPerSecondEstimate <= 0) null
        else freeBytes / bytesPerSecondEstimate / 60
}

/** Process-wide recording state, written only by the recording service. */
object RecorderState {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    fun update(block: (LiveState) -> LiveState) = _state.update(block)
    fun reset() { _state.value = LiveState() }
    /** UI calls this after it has shown the saved screen. */
    fun consumeSaved() = _state.update { if (it.phase == Phase.SAVED || it.phase == Phase.FAILED) LiveState() else it }
}
