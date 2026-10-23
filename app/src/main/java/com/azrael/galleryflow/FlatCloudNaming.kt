package com.azrael.galleryflow

/** A single cloud folder for ALL sources and albums. Media identities are part of
 * the filename to avoid overwriting "0001.jpg" from other albums. */
object FlatCloudNaming {
    fun fileName(source: String, mediaId: String, sha256: String, originalName: String): String {
        val safeSource = safe(source).take(22)
        val safeId = safe(mediaId).take(44)
        val hash = sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        require(hash.length == 64) { "A full SHA-256 is required for collision-safe naming" }
        val originalExt = originalName.substringAfterLast('.', "").lowercase()
        val extension = if (originalExt.matches(Regex("[a-z0-9]{1,6}"))) originalExt else "bin"
        return "kyora_${safeSource}_${safeId}_${hash.take(24)}.$extension"
    }

    private fun safe(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .trim('_').ifBlank { "unknown" }

    fun path(directory: String, filename: String): String =
        directory.trimEnd('/') + "/" + filename
}
