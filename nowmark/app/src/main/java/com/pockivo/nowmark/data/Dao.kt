package com.pockivo.nowmark.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class RecordingRow(
    val id: String,
    val title: String,
    val createdAt: Long,
    val capturedMs: Long,
    val fileMs: Long,
    val folder: String?,
    val tags: String,
    val verifyStatus: VerifyStatus,
    val inputInterruptions: Int,
    val markCount: Int,
    val deletedAt: Long?,
) {
    val durationMs: Long get() = if (fileMs > 0) fileMs else capturedMs
}

/** A mark joined with its recording, for the review queue. */
data class ReviewRow(
    val id: String,
    val recordingId: String,
    val tMs: Long,
    val startMs: Long,
    val endMs: Long,
    val category: MarkCategory,
    val memo: String,
    val reviewed: Boolean,
    val done: Boolean,
    val recordingTitle: String,
    val recordingCreatedAt: Long,
    val recordingUri: String,
    val recordingMs: Long,
)

@Dao
interface NowmarkDao {
    @Query(
        """SELECT r.id, r.title, r.createdAt, r.capturedMs, r.fileMs, r.folder, r.tags, r.verifyStatus,
           r.inputInterruptions, r.deletedAt,
           (SELECT COUNT(*) FROM marks m WHERE m.recordingId = r.id) AS markCount
           FROM recordings r WHERE r.deletedAt IS NULL
           AND (:q = '' OR r.title LIKE '%' || :q || '%'
                OR EXISTS (SELECT 1 FROM marks m WHERE m.recordingId = r.id AND m.memo LIKE '%' || :q || '%')
                OR EXISTS (SELECT 1 FROM notes n WHERE n.recordingId = r.id AND n.text LIKE '%' || :q || '%'))
           ORDER BY r.createdAt DESC"""
    )
    fun observeLibrary(q: String): Flow<List<RecordingRow>>

    @Query(
        """SELECT r.id, r.title, r.createdAt, r.capturedMs, r.fileMs, r.folder, r.tags, r.verifyStatus,
           r.inputInterruptions, r.deletedAt,
           (SELECT COUNT(*) FROM marks m WHERE m.recordingId = r.id) AS markCount
           FROM recordings r WHERE r.deletedAt IS NOT NULL ORDER BY r.deletedAt DESC"""
    )
    fun observeTrash(): Flow<List<RecordingRow>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeRecording(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun recording(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun allRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE deletedAt IS NULL")
    suspend fun activeRecordings(): List<RecordingEntity>

    @Query("SELECT DISTINCT folder FROM recordings WHERE folder IS NOT NULL AND folder != '' AND deletedAt IS NULL ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("SELECT tags FROM recordings WHERE tags != '' AND deletedAt IS NULL")
    fun observeTagBlobs(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecording(r: RecordingEntity)

    @Update
    suspend fun updateRecording(r: RecordingEntity)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE recordings SET folder = :folder, tags = :tags WHERE id = :id")
    suspend fun setFolderTags(id: String, folder: String?, tags: String)

    @Query("UPDATE recordings SET deletedAt = :at WHERE id = :id")
    suspend fun setDeletedAt(id: String, at: Long?)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: String)

    @Query("SELECT * FROM marks WHERE recordingId = :id ORDER BY tMs")
    fun observeMarks(id: String): Flow<List<MarkEntity>>

    @Query("SELECT * FROM marks WHERE recordingId = :id ORDER BY tMs")
    suspend fun marks(id: String): List<MarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarks(m: List<MarkEntity>)

    @Update
    suspend fun updateMark(m: MarkEntity)

    @Query("DELETE FROM marks WHERE id = :id")
    suspend fun deleteMark(id: String)

    @Query("SELECT * FROM notes WHERE recordingId = :id ORDER BY tMs")
    fun observeNotes(id: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE recordingId = :id ORDER BY tMs")
    suspend fun notes(id: String): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(n: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("SELECT * FROM health_events WHERE recordingId = :id ORDER BY startMs")
    fun observeEvents(id: String): Flow<List<HealthEventEntity>>

    @Query("SELECT * FROM health_events WHERE recordingId = :id ORDER BY startMs")
    suspend fun events(id: String): List<HealthEventEntity>

    @Insert
    suspend fun insertEvents(e: List<HealthEventEntity>)

    @Query(
        """SELECT m.id, m.recordingId, m.tMs, m.startMs, m.endMs, m.category, m.memo, m.reviewed, m.done,
           r.title AS recordingTitle, r.createdAt AS recordingCreatedAt, r.uri AS recordingUri,
           CASE WHEN r.fileMs > 0 THEN r.fileMs ELSE r.capturedMs END AS recordingMs
           FROM marks m JOIN recordings r ON r.id = m.recordingId
           WHERE r.deletedAt IS NULL
           ORDER BY r.createdAt DESC, m.startMs ASC"""
    )
    fun observeReview(): Flow<List<ReviewRow>>

    @Transaction
    suspend fun insertFull(
        r: RecordingEntity, marks: List<MarkEntity>, notes: List<NoteEntity>, events: List<HealthEventEntity>,
    ) {
        insertRecording(r)
        if (marks.isNotEmpty()) upsertMarks(marks)
        if (notes.isNotEmpty()) upsertNotes(notes)
        if (events.isNotEmpty()) insertEvents(events)
    }
}
