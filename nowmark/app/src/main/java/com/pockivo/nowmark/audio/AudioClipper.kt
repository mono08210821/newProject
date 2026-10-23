package com.pockivo.nowmark.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.data.MetadataSource
import com.pockivo.nowmark.data.NoteEntity
import com.pockivo.nowmark.data.RecordingEntity
import com.pockivo.nowmark.data.VerifyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.util.UUID

/**
 * Builds new files from parts of a recording. The original is never modified: trims are
 * saved as a new recording linked by parentId, shared clips go to a private cache file.
 */
object AudioClipper {

    private fun sinkFor(fd: FileDescriptor, kind: AudioFormatKind, f: PcmFormat): PcmSink = when (kind) {
        AudioFormatKind.M4A -> AacSink(fd, f.sampleRate, f.channels, if (f.channels > 1) 192_000 else 128_000)
        AudioFormatKind.WAV -> WavSink(fd, f.sampleRate, f.channels)
    }

    /** Decodes [ranges] of [src] one after another into [fd]. Returns frames written or null. */
    private fun render(context: Context, src: Uri, ranges: List<TimeRange>, kind: AudioFormatKind, fd: FileDescriptor): Long? {
        var sink: PcmSink? = null
        try {
            for (r in ranges) {
                val ok = PcmDecoder.decode(
                    context, src, r.startMs * 1000, r.endMs * 1000,
                    onFormat = { f -> if (sink == null) sink = sinkFor(fd, kind, f) },
                    onPcm = { b, o, l -> sink!!.write(b, o, l) },
                )
                if (!ok) { sink?.abort(); return null }
            }
            return sink?.finish()
        } catch (e: Exception) {
            sink?.abort()
            return null
        }
    }

    /** Creates a shareable clip of one range; returns a FileProvider URI. */
    suspend fun shareClip(context: Context, rec: RecordingEntity, range: TimeRange): Uri? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only keep the latest shared clip
        val kind = AudioFormatKind.from(rec.format)
        val safeTitle = rec.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val f = File(dir, "$safeTitle ${range.startMs / 1000}-${range.endMs / 1000}.${kind.extension}")
        val pfd = ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        val frames = pfd.use { render(context, Uri.parse(rec.uri), listOf(range), kind, it.fileDescriptor) }
        if (frames == null || frames <= 0) { f.delete(); return@withContext null }
        FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Saves [range] as a new recording; marks and notes inside the range are copied and shifted. */
    suspend fun trimCopy(context: Context, rec: RecordingEntity, range: TimeRange, title: String): String? =
        withContext(Dispatchers.IO) {
            val kind = AudioFormatKind.from(rec.format)
            val name = "Nowmark_" + UUID.randomUUID().toString().take(8)
            val pending = try { AudioStore.create(context, name, kind) } catch (e: Exception) { return@withContext null }
            val frames = render(context, Uri.parse(rec.uri), listOf(range), kind, pending.pfd.fileDescriptor)
            if (frames == null || frames <= 0) {
                AudioStore.discard(context, pending)
                return@withContext null
            }
            val fileMs = PcmDecoder.measureDurationUs(context, pending.uri)?.div(1000) ?: 0L
            AudioStore.publish(context, pending)
            val id = UUID.randomUUID().toString()
            val dao = AppGraph.db.dao()
            val now = System.currentTimeMillis()
            val marks = dao.marks(rec.id).filter { it.tMs in range.startMs..range.endMs }.map {
                it.copy(
                    id = UUID.randomUUID().toString(), recordingId = id, tMs = it.tMs - range.startMs,
                    startMs = (it.startMs - range.startMs).coerceAtLeast(0),
                    endMs = (it.endMs - range.startMs).coerceAtMost(range.lengthMs),
                )
            }
            val notes = dao.notes(rec.id).filter { it.tMs in range.startMs..range.endMs }.map {
                NoteEntity(UUID.randomUUID().toString(), id, it.tMs - range.startMs, it.text, it.createdAt)
            }
            val entity = rec.copy(
                id = id, title = title, createdAt = now, capturedMs = range.lengthMs, fileMs = fileMs,
                uri = pending.uri.toString(), displayPath = pending.displayPath,
                sizeBytes = AudioStore.size(context, pending.uri),
                // A trim is a new file: its length was measured, but input checks belong to the original.
                verifyStatus = if (fileMs > 0) VerifyStatus.MATCH.takeIf {
                    com.pockivo.nowmark.core.LengthCheck.matches(kind, rec.sampleRate, range.lengthMs, fileMs)
                } ?: VerifyStatus.MISMATCH else VerifyStatus.UNVERIFIED,
                inputInterruptions = 0, parentId = rec.id, metadataSource = MetadataSource.APP, deletedAt = null,
            )
            dao.insertFull(entity, marks, notes, emptyList())
            id
        }
}
