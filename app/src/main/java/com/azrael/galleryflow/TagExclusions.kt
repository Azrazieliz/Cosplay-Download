package com.azrael.galleryflow

import java.text.Normalizer
import java.util.Locale

/** Exact tag match. No filename or subtitle/title substring heuristics. */
object TagExclusions {
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun parse(text: String): Set<String> =
        text.lineSequence().map(::normalize).filter(String::isNotEmpty).toSet()

    fun firstBlocked(tags: List<String>, exclusions: String): String? {
        val blocked = parse(exclusions)
        if (blocked.isEmpty()) return null
        return tags.firstOrNull { normalize(it) in blocked }
    }
}
