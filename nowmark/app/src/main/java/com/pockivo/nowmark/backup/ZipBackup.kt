package com.pockivo.nowmark.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.audio.PcmDecoder
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.core.ZipSafety
import com.pockivo.nowmark.data.HealthEventEntity
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.data.MarkCategory
import com.pockivo.nowmark.data.MarkEntity
import com.pockivo.nowmark.data.MetadataSource
import com.pockivo.nowmark.data.NoteEntity
import com.pockivo.nowmark.data.RecordingEntity
import com.pockivo.nowmark.data.VerifyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable project bundle: manifest.json (versioned schema) + audio/<id>.<ext>. */
object ZipBackup {
    const val SCHEMA_VERSION = 1
    private const val MANIFEST = "manifest.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    data class Manifest(
        val schemaVersion: Int = SCHEMA_VERSION,
        val app: String = "Nowmark",
        val exportedAt: Long,
        val recordings: List<MRecording>,
    )

    @Serializable
    data class MRecording(
        val id: String,
        val title: String,
        val createdAt: Long,
        val capturedMs: Long,
        val fileMs: Long,
        val format: String,
        val preset: String,
        val sampleRate: Int,
        val channels: Int,
        val folder: String? = null,
        val tags: List<String> = emptyList(),
        val verifyStatus: String,
        val inputInterruptions: Int = 0,
        val parentId: String? = null,
        val inputDevice: String? = null,
        val audioPath: String,
        val marks: List<MMark> = emptyList(),
        val notes: List<MNote> = emptyList(),
        val events: List<MEvent> = emptyList(),
        /** Reserved for photo attachments (2nd update). */
        val attachments: List<String> = emptyList(),
    )

    @Serializable
    data class MMark(
        val id: String, val tMs: Long, val startMs: Long, val endMs: Long, val category: String,
        val memo: String, val reviewed: Boolean, val done: Boolean, val createdAt: Long,
    )

    @Serializable
    data class MNote(val id: String, val tMs: Long, val text: String, val createdAt: Long)

    @Serializable
    data class MEvent(
        val type: String, val startMs: Long, val endMs: Long?, val wallTime: Long,
        val wallDurationMs: Long, val detail: String,
    )

    data class ExportResult(val recordings: Int, val missingAudio: Int)

    data class ImportResult(
        val imported: Int = 0,
        val duplicates: Int = 0,
        val failed: Int = 0,
        val error: ImportError? = null,
    )

    enum class ImportError { NOT_A_BACKUP, NEWER_VERSION, CORRUPT, TOO_LARGE, STORAGE }

    suspend fun export(context: Context, target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val dao = AppGraph.db.dao()
        val recs = dao.activeRecordings()
        var missing = 0
        val entries = recs.map { r ->
            val ext = AudioFormatKind.from(r.format).extension
            MRecording(
                id = r.id, title = r.title, createdAt = r.createdAt, capturedMs = r.capturedMs, fileMs = r.fileMs,
                format = r.format, preset = r.preset, sampleRate = r.sampleRate, channels = r.channels,
                folder = r.folder, tags = r.tagList, verifyStatus = r.verifyStatus.name,
                inputInterruptions = r.inputInterruptions, parentId = r.parentId, inputDevice = r.inputDevice,
                audioPath = "audio/${r.id}.$ext",
                marks = dao.marks(r.id).map {
                    MMark(it.id, it.tMs, it.startMs, it.endMs, it.category.name, it.memo, it.reviewed, it.done, it.createdAt)
                },
                notes = dao.notes(r.id).map { MNote(it.id, it.tMs, it.text, it.createdAt) },
                events = dao.events(r.id).map {
                    MEvent(it.type.name, it.startMs, it.endMs, it.wallTime, it.wallDurationMs, it.detail)
                },
            )
        }
        val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("cannot open target")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest.serializer(), Manifest(exportedAt = System.currentTimeMillis(), recordings = entries)).toByteArray())
            zip.closeEntry()
            recs.zip(entries).forEach { (r, m) ->
                val input = try { context.contentResolver.openInputStream(Uri.parse(r.uri)) } catch (e: Exception) { null }
                if (input == null) { missing++; return@forEach }
                input.use {
                    zip.putNextEntry(ZipEntry(m.audioPath))
                    it.copyTo(zip, 256 * 1024)
                    zip.closeEntry()
                }
            }
        }
        ExportResult(recs.size, missing)
    }

    /** Two passes: validate and read the manifest, then copy audio of new recordings. */
    suspend fun import(context: Context, source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val manifest = try {
            readManifest(context, source)
        } catch (e: ImportException) {
            return@withContext ImportResult(error = e.kind)
        } catch (e: Exception) {
            return@withContext ImportResult(error = ImportError.CORRUPT)
        } ?: return@withContext ImportResult(error = ImportError.NOT_A_BACKUP)
        if (manifest.schemaVersion > SCHEMA_VERSION) return@withContext ImportResult(error = ImportError.NEWER_VERSION)

        val dao = AppGraph.db.dao()
        val existing = dao.allRecordings().map { it.id }.toSet()
        val wanted = manifest.recordings
            .filter { it.id !in existing }
            .mapNotNull { r -> ZipSafety.safeName(r.audioPath)?.let { it to r } }
            .toMap()
        val duplicates = manifest.recordings.count { it.id in existing }
        var imported = 0
        var failed = 0
        var total = 0L
        try {
            openZip(context, source).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = ZipSafety.safeName(e.name) ?: continue
                    val r = wanted[name] ?: continue
                    val kind = AudioFormatKind.from(r.format)
                    val pending = try {
                        AudioStore.create(context, sanitize(r.title), kind)
                    } catch (ex: Exception) { failed++; continue }
                    try {
                        FileOutputStream(pending.pfd.fileDescriptor).use { out ->
                            total += copyLimited(zip, out, ZipSafety.MAX_TOTAL_UNCOMPRESSED - total)
                        }
                    } catch (ex: Exception) {
                        AudioStore.discard(context, pending)
                        if (ex is ImportException) throw ex
                        failed++
                        continue
                    }
                    val measured = PcmDecoder.measureDurationUs(context, pending.uri)
                    if (measured == null) {
                        AudioStore.discard(context, pending)
                        failed++
                        continue
                    }
                    AudioStore.publish(context, pending)
                    insert(r, pending.uri.toString(), pending.displayPath, AudioStore.size(context, pending.uri), measured / 1000)
                    imported++
                }
            }
        } catch (e: ImportException) {
            return@withContext ImportResult(imported, duplicates, failed, e.kind)
        } catch (e: Exception) {
            return@withContext ImportResult(imported, duplicates, failed, ImportError.CORRUPT)
        }
        failed += wanted.size - imported - failed
        ImportResult(imported, duplicates, failed.coerceAtLeast(0))
    }

    private class ImportException(val kind: ImportError) : Exception()

    private fun openZip(context: Context, uri: Uri): ZipInputStream =
        ZipInputStream((context.contentResolver.openInputStream(uri) ?: throw IOException("open")).buffered())

    private fun readManifest(context: Context, uri: Uri): Manifest? {
        var count = 0
        openZip(context, uri).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > ZipSafety.MAX_ENTRIES) throw ImportException(ImportError.TOO_LARGE)
                if (ZipSafety.safeName(e.name) == MANIFEST) {
                    val buf = ByteArrayOutputStream()
                    copyLimited(zip, buf, ZipSafety.MAX_MANIFEST_BYTES)
                    return json.decodeFromString(Manifest.serializer(), buf.toString(Charsets.UTF_8.name()))
                }
            }
        }
        return null
    }

    /** Copies at most [limit] bytes; more than that means a zip bomb or a wrong file. */
    private fun copyLimited(input: InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(256 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > limit) throw ImportException(ImportError.TOO_LARGE)
            out.write(buf, 0, r)
        }
        return n
    }

    private suspend fun insert(r: MRecording, uri: String, path: String, size: Long, fileMs: Long) {
        val entity = RecordingEntity(
            id = r.id, title = r.title, createdAt = r.createdAt, capturedMs = r.capturedMs, fileMs = fileMs,
            format = r.format, preset = r.preset, sampleRate = r.sampleRate, channels = r.channels, uri = uri,
            displayPath = path, sizeBytes = size, folder = r.folder, tags = r.tags.joinToString("\n"),
            verifyStatus = VerifyStatus.entries.firstOrNull { it.name == r.verifyStatus } ?: VerifyStatus.UNVERIFIED,
            inputInterruptions = r.inputInterruptions, parentId = r.parentId, metadataSource = MetadataSource.ZIP,
            inputDevice = r.inputDevice,
        )
        AppGraph.db.dao().insertFull(
            entity,
            r.marks.map {
                MarkEntity(
                    it.id, r.id, it.tMs, it.startMs, it.endMs,
                    MarkCategory.entries.firstOrNull { c -> c.name == it.category } ?: MarkCategory.IMPORTANT,
                    it.memo, it.reviewed, it.done, it.createdAt,
                )
            },
            r.notes.map { NoteEntity(it.id, r.id, it.tMs, it.text, it.createdAt) },
            r.events.map {
                HealthEventEntity(
                    recordingId = r.id, type = HealthType.entries.firstOrNull { t -> t.name == it.type } ?: HealthType.LOW_INPUT,
                    startMs = it.startMs, endMs = it.endMs, wallTime = it.wallTime, wallDurationMs = it.wallDurationMs, detail = it.detail,
                )
            },
        )
    }

    /** Imports plain audio files. Their marks/memos cannot be restored and are not claimed to be. */
    suspend fun importAudioFiles(context: Context, uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        for (src in uris) {
            val name = displayName(context, src) ?: "Audio"
            val kind = if (name.endsWith(".wav", true)) AudioFormatKind.WAV else AudioFormatKind.M4A
            val probe = PcmDecoder.probe(context, src)
            if (probe == null || (!name.endsWith(".wav", true) && !name.endsWith(".m4a", true) && !name.endsWith(".mp4", true) && !name.endsWith(".aac", true))) {
                // Other formats would need transcoding; keep this simple and honest.
                failed++
                continue
            }
            val pending = try { AudioStore.create(context, sanitize(name.substringBeforeLast('.')), kind) } catch (e: Exception) { failed++; continue }
            try {
                context.contentResolver.openInputStream(src)!!.use { input ->
                    FileOutputStream(pending.pfd.fileDescriptor).use { input.copyTo(it, 256 * 1024) }
                }
            } catch (e: Exception) {
                AudioStore.discard(context, pending); failed++; continue
            }
            val ms = PcmDecoder.measureDurationUs(context, pending.uri)?.div(1000) ?: 0L
            AudioStore.publish(context, pending)
            val (fmt, _) = probe
            AppGraph.db.dao().insertRecording(
                RecordingEntity(
                    id = UUID.randomUUID().toString(), title = name.substringBeforeLast('.'),
                    createdAt = System.currentTimeMillis(), capturedMs = ms, fileMs = ms, format = kind.name,
                    preset = "MEETING", sampleRate = fmt.sampleRate, channels = fmt.channels,
                    uri = pending.uri.toString(), displayPath = pending.displayPath,
                    sizeBytes = AudioStore.size(context, pending.uri), verifyStatus = VerifyStatus.UNVERIFIED,
                    metadataSource = MetadataSource.AUDIO_ONLY,
                )
            )
            ok++
        }
        ImportResult(imported = ok, failed = failed)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sanitize(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(80).ifBlank { "Nowmark" }
}
