package com.pockivo.nowmark.recorder

import android.content.Context
import android.text.format.DateFormat
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AacSink
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.audio.PcmDecoder
import com.pockivo.nowmark.audio.PcmSink
import com.pockivo.nowmark.audio.WavSink
import com.pockivo.nowmark.audio.Waveforms
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.core.LengthCheck
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.core.WavFormat
import com.pockivo.nowmark.data.HealthEventEntity
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.data.MarkEntity
import com.pockivo.nowmark.data.MetadataSource
import com.pockivo.nowmark.data.NoteEntity
import com.pockivo.nowmark.data.Preset
import com.pockivo.nowmark.data.RecordingEntity
import com.pockivo.nowmark.data.VerifyStatus
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface FinalizeResult {
    data class Saved(val recordingId: String) : FinalizeResult
    data object Empty : FinalizeResult
    data class Failed(val kind: FailureKind) : FinalizeResult
}

/**
 * Turns a session directory (PCM chunks + journal) into a finished, verified audio file and
 * database rows. Chunks are deleted only after the output is complete and inserted; on any
 * failure the session is kept so it can be retried from the recovery screen.
 */
object Finalizer {

    fun sessionsRoot(context: Context): File = File(context.filesDir, "sessions").apply { mkdirs() }

    /** Frames actually present in a chunk file (frame aligned). */
    private fun framesOnDisk(f: File, bytesPerFrame: Int): Long =
        if (f.exists()) f.length() / bytesPerFrame else 0L

    suspend fun finalize(context: Context, dir: File, journal: Journal, recovered: Boolean): FinalizeResult {
        val bpf = journal.channels * 2
        val chunkFiles = journal.chunks.map { File(dir, it.name) }.filter { it.exists() }
        val totalFrames = chunkFiles.sumOf { framesOnDisk(it, bpf) }
        if (totalFrames <= 0) {
            dir.deleteRecursively()
            return FinalizeResult.Empty
        }
        val preset = Preset.from(journal.preset)
        var format = AudioFormatKind.from(journal.format)
        if (format == AudioFormatKind.WAV && totalFrames * bpf > WavFormat.MAX_DATA_BYTES) {
            // Plain WAV cannot exceed 4 GiB; keep the audio by saving as M4A instead.
            format = AudioFormatKind.M4A
        }
        val capturedMs = totalFrames * 1000L / journal.sampleRate
        val displayName = "Nowmark_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(journal.startedAt))

        journal.copy(state = Journal.STATE_FINALIZING).let { try { it.writeAtomically(dir) } catch (_: IOException) { } }

        val pending = try {
            AudioStore.create(context, displayName, format)
        } catch (e: Exception) {
            markFailed(dir, journal)
            return FinalizeResult.Failed(FailureKind.STORAGE_FULL.takeIf { isNoSpace(e) } ?: FailureKind.ENCODE_FAILED)
        }
        var sink: PcmSink? = null
        try {
            val out: PcmSink = when (format) {
                AudioFormatKind.M4A -> AacSink(pending.pfd.fileDescriptor, journal.sampleRate, journal.channels, preset.aacBitrate)
                AudioFormatKind.WAV -> WavSink(pending.pfd.fileDescriptor, journal.sampleRate, journal.channels)
            }
            sink = out
            val buf = ByteArray(256 * 1024)
            for (f in chunkFiles) {
                val limit = framesOnDisk(f, bpf) * bpf
                var read = 0L
                FileInputStream(f).use { input ->
                    while (read < limit) {
                        val want = minOf(buf.size.toLong(), limit - read).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                    }
                }
            }
            out.finish()
            sink = null
        } catch (e: Exception) {
            sink?.abort()
            AudioStore.discard(context, pending)
            markFailed(dir, journal)
            return FinalizeResult.Failed(if (isNoSpace(e)) FailureKind.STORAGE_FULL else FailureKind.ENCODE_FAILED)
        }

        // Verify on the written file, not on what we think we wrote.
        val measuredUs = PcmDecoder.measureDurationUs(context, pending.uri)
        val fileMs = measuredUs?.let { it / 1000 } ?: 0L
        val verify = when {
            measuredUs == null -> VerifyStatus.UNVERIFIED
            LengthCheck.matches(format, journal.sampleRate, capturedMs, fileMs) -> VerifyStatus.MATCH
            else -> VerifyStatus.MISMATCH
        }
        AudioStore.publish(context, pending)
        val size = AudioStore.size(context, pending.uri)

        val id = journal.id
        val now = System.currentTimeMillis()
        val marks = journal.marks.mapNotNull { m ->
            MarkMath.clamp(TimeRange(m.startMs, m.endMs), capturedMs)?.let { r ->
                MarkEntity(m.id, id, m.tMs.coerceAtMost(capturedMs), r.startMs, r.endMs, createdAt = m.wallTime)
            }
        }
        val notes = journal.notes.map { NoteEntity(it.id, id, it.tMs.coerceAtMost(capturedMs), it.text, it.wallTime) }
        val events = journal.events.map {
            HealthEventEntity(
                recordingId = id,
                type = HealthType.entries.firstOrNull { t -> t.name == it.type } ?: HealthType.LOW_INPUT,
                startMs = it.startMs.coerceAtMost(capturedMs),
                endMs = it.endMs?.coerceAtMost(capturedMs),
                wallTime = it.wallTime,
                wallDurationMs = it.wallDurationMs,
                detail = it.detail,
            )
        }.toMutableList()
        if (recovered) {
            events += HealthEventEntity(
                recordingId = id, type = HealthType.UNFINISHED_SESSION, startMs = capturedMs, endMs = capturedMs,
                wallTime = journal.updatedAt, detail = "confirmed=${journal.confirmedFrames * 1000 / journal.sampleRate}",
            )
        }
        val entity = RecordingEntity(
            id = id,
            title = defaultTitle(context, preset, journal.startedAt),
            createdAt = journal.startedAt,
            capturedMs = capturedMs,
            fileMs = fileMs,
            format = format.name,
            preset = preset.name,
            sampleRate = journal.sampleRate,
            channels = journal.channels,
            uri = pending.uri.toString(),
            displayPath = pending.displayPath,
            sizeBytes = size,
            verifyStatus = verify,
            inputInterruptions = events.count { it.type == HealthType.INPUT_SILENCED },
            metadataSource = if (recovered) MetadataSource.RECOVERED else MetadataSource.APP,
            inputDevice = journal.inputDevice,
        )
        try {
            AppGraph.db.dao().insertFull(entity, marks, notes, events)
        } catch (e: Exception) {
            // The audio is safely published; keep the session so metadata can be retried.
            markFailed(dir, journal)
            return FinalizeResult.Failed(FailureKind.ENCODE_FAILED)
        }
        Waveforms.adoptRecordedPeaks(context, id, File(dir, Journal.PEAKS_NAME))
        dir.deleteRecursively()
        AppGraph.settings.countSuccessfulSave()
        return FinalizeResult.Saved(id)
    }

    private fun markFailed(dir: File, journal: Journal) {
        try { journal.copy(state = Journal.STATE_FAILED).writeAtomically(dir) } catch (_: IOException) { }
    }

    private fun isNoSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space") == true }

    fun defaultTitle(context: Context, preset: Preset, at: Long): String {
        val label = context.getString(
            when (preset) {
                Preset.VOICE -> R.string.preset_voice
                Preset.MEETING -> R.string.preset_meeting
                Preset.MUSIC -> R.string.preset_music
            }
        )
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd Hm")
        return "$label · " + SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
    }

    /** Sessions left behind by a crash, kill or reboot (not the one being recorded now). */
    fun findUnfinished(context: Context): List<Pair<File, Journal>> {
        val active = RecorderState.state.value.takeIf { it.isActive }?.sessionId
        return sessionsRoot(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name != active }
            .mapNotNull { dir -> Journal.read(dir)?.let { dir to it } }
            .sortedByDescending { it.second.startedAt }
    }

    /** Frames found on disk for a leftover session (may exceed the last confirmed point). */
    fun framesFound(dir: File, journal: Journal): Long {
        val bpf = journal.channels * 2
        return journal.chunks.sumOf { framesOnDisk(File(dir, it.name), bpf) }
    }
}
