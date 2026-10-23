package com.pockivo.nowmark.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Result of comparing captured audio length with the saved file. */
enum class VerifyStatus { MATCH, MISMATCH, UNVERIFIED }

/** Where a recording's metadata came from. Audio-only imports never claim restored marks. */
enum class MetadataSource { APP, ZIP, AUDIO_ONLY, RECOVERED }

enum class MarkCategory { IMPORTANT, TODO, QUESTION, IDEA }

enum class HealthType {
    /** The system reported our input as silenced (API 29+ AudioRecordingCallback). */
    INPUT_SILENCED,
    /** Input level stayed very low; may just be a quiet room. Not a failure. */
    LOW_INPUT,
    /** The routed input device changed (e.g. Bluetooth disconnected). */
    DEVICE_CHANGED,
    /** A file sync took unusually long. */
    WRITE_DELAY,
    /** Writing to the intermediate file failed. */
    WRITE_FAILED,
    /** AudioRecord.read returned an error. */
    READ_ERROR,
    /** The recording session ended without a normal stop and was recovered later. */
    UNFINISHED_SESSION,
}

@Entity(tableName = "recordings", indices = [Index("deletedAt"), Index("createdAt")])
data class RecordingEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Wall-clock time the recording started. */
    val createdAt: Long,
    /** Length of audio we captured (sum of frames written / sample rate). */
    val capturedMs: Long,
    /** Length measured from the saved file; 0 when not measured. */
    val fileMs: Long,
    val format: String,
    val preset: String,
    val sampleRate: Int,
    val channels: Int,
    /** content:// or file:// URI of the finished audio. */
    val uri: String,
    /** Human readable location, e.g. "Recordings/Nowmark/Weekly sync.m4a". */
    val displayPath: String,
    val sizeBytes: Long,
    val folder: String? = null,
    /** Tags separated by '\n'. */
    val tags: String = "",
    val verifyStatus: VerifyStatus = VerifyStatus.UNVERIFIED,
    val inputInterruptions: Int = 0,
    val parentId: String? = null,
    val metadataSource: MetadataSource = MetadataSource.APP,
    val inputDevice: String? = null,
    val deletedAt: Long? = null,
) {
    val durationMs: Long get() = if (fileMs > 0) fileMs else capturedMs
    val tagList: List<String> get() = tags.split('\n').filter { it.isNotBlank() }
}

@Entity(
    tableName = "marks",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class MarkEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    /** Audio position of the tap. */
    val tMs: Long,
    val startMs: Long,
    val endMs: Long,
    val category: MarkCategory = MarkCategory.IMPORTANT,
    val memo: String = "",
    /** User confirmed they listened to it. */
    val reviewed: Boolean = false,
    /** To-do completed / question resolved. */
    val done: Boolean = false,
    val createdAt: Long,
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val tMs: Long,
    val text: String,
    val createdAt: Long,
)

@Entity(
    tableName = "health_events",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class HealthEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val recordingId: String,
    val type: HealthType,
    /** Audio position where it started. */
    val startMs: Long,
    /** Audio position where it ended; null if it lasted until the end. */
    val endMs: Long?,
    /** Wall-clock time it was observed. */
    val wallTime: Long,
    /** Wall-clock duration (input can be silenced while audio time keeps running). */
    val wallDurationMs: Long = 0,
    val detail: String = "",
)
