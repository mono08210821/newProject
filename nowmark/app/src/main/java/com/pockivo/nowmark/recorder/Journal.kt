package com.pockivo.nowmark.recorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * On-disk description of an in-progress recording session. It is rewritten atomically
 * (temp file + fsync + rename) after each confirmed sync of the PCM chunk files, so after
 * a crash the next launch knows which chunks exist and how many frames were confirmed.
 */
@Serializable
data class Journal(
    val version: Int = 1,
    val id: String,
    val preset: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val startedAt: Long,
    val chunks: List<JournalChunk> = emptyList(),
    /** Frames that were fsync'ed to the chunk files before this journal was written. */
    val confirmedFrames: Long = 0,
    val marks: List<JournalMark> = emptyList(),
    val notes: List<JournalNote> = emptyList(),
    val events: List<JournalEvent> = emptyList(),
    val state: String = STATE_RECORDING,
    val inputDevice: String? = null,
    val updatedAt: Long = 0,
) {
    companion object {
        const val STATE_RECORDING = "RECORDING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_FINALIZING = "FINALIZING"
        const val STATE_FAILED = "FAILED"

        const val FILE_NAME = "journal.json"
        const val PEAKS_NAME = "peaks.bin"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun read(dir: File): Journal? = try {
            val f = File(dir, FILE_NAME)
            if (f.exists()) json.decodeFromString(serializer(), f.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    fun writeAtomically(dir: File) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(serializer(), this).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, FILE_NAME))) throw java.io.IOException("journal rename failed")
    }
}

@Serializable
data class JournalChunk(val name: String, val frames: Long)

@Serializable
data class JournalMark(
    val id: String,
    val tMs: Long,
    val startMs: Long,
    val endMs: Long,
    val wallTime: Long,
)

@Serializable
data class JournalNote(val id: String, val tMs: Long, val text: String, val wallTime: Long)

@Serializable
data class JournalEvent(
    val type: String,
    val startMs: Long,
    val endMs: Long? = null,
    val wallTime: Long,
    val wallDurationMs: Long = 0,
    val detail: String = "",
)
