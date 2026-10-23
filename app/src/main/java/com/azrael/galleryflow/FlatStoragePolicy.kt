package com.azrael.galleryflow

/** All image pages use one common physical directory, never gallery subfolders.
 * Stable IDs prevent collisions between galleries and sources, including updates. */
object FlatStoragePolicy {
    fun filename(media: MediaRef, extension: String): String {
        fun clean(value: String, max: Int): String =
            value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(max).ifBlank { "id" }
        val source = clean(media.source.wireName, 20)
        val gallery = clean(media.galleryStableId, 40)
        val item = clean(media.stableId, 40)
        val page = (media.index + 1).toString().padStart(4, '0')
        return "${source}_${gallery}_p${page}_${item}.${extension}"
    }
}
