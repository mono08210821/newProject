package com.pockivo.nowmark.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.net.toUri
import com.pockivo.nowmark.core.AudioFormatKind
import java.io.File

/**
 * Where finished audio lives.
 *  - Android 12+: public MediaStore "Recordings/Nowmark" (kept after uninstall).
 *  - Android 10–11: public MediaStore "Music/Nowmark" (Recordings is not a valid audio dir there).
 *  - Android 8–9: app-specific external storage (removed on uninstall), because writing to
 *    public folders would require the broad storage permission.
 */
object AudioStore {
    val relativeDir: String
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "${Environment.DIRECTORY_RECORDINGS}/Nowmark"
            else -> "${Environment.DIRECTORY_MUSIC}/Nowmark"
        }

    val isPublic: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    class Pending(val uri: Uri, val pfd: ParcelFileDescriptor, val displayPath: String, val file: File?)

    /** Creates a not-yet-visible output (IS_PENDING) opened read-write. */
    fun create(context: Context, displayName: String, format: AudioFormatKind): Pending {
        val name = "$displayName.${format.extension}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw java.io.IOException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw java.io.IOException("open failed")
            return Pending(uri, pfd, "$relativeDir/$name", null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Nowmark").apply { mkdirs() }
            var f = File(dir, name)
            var i = 1
            while (f.exists()) { f = File(dir, "$displayName ($i).${format.extension}"); i++ }
            val pfd = ParcelFileDescriptor.open(
                f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            return Pending(f.toUri(), pfd, "Android/data/${context.packageName}/files/Music/Nowmark/${f.name}", f)
        }
    }

    /** Makes the file visible to other apps once it is complete and verified. */
    fun publish(context: Context, p: Pending) {
        try { p.pfd.close() } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && p.file == null) {
            val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(p.uri, v, null, null)
        }
    }

    fun discard(context: Context, p: Pending) {
        try { p.pfd.close() } catch (_: Exception) { }
        delete(context, p.uri)
    }

    /** Returns true when deleted. On Android 11+ a file we no longer own throws RecoverableSecurityException. */
    fun delete(context: Context, uri: Uri): Boolean = if (uri.scheme == "file") {
        File(uri.path!!).delete()
    } else {
        context.contentResolver.delete(uri, null, null) > 0
    }

    fun size(context: Context, uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
    } catch (_: Exception) {
        -1
    }

    fun exists(context: Context, uri: Uri): Boolean = size(context, uri) >= 0
}
