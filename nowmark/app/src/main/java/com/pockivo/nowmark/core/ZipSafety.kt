package com.pockivo.nowmark.core

/** Defends ZIP import against path traversal and oversized archives. */
object ZipSafety {
    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL_UNCOMPRESSED: Long = 8L * 1024 * 1024 * 1024 // 8 GiB
    const val MAX_MANIFEST_BYTES: Long = 64L * 1024 * 1024

    /**
     * Returns a normalized relative path, or null when the entry name could escape the
     * destination (absolute paths, drive letters, "..", backslashes, NUL).
     */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.contains('\u0000') || raw.contains('\\')) return null
        if (raw.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(raw)) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
