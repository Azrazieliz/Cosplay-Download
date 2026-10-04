package com.azrael.galleryflow

import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSiteSmokeTest {
    @Test
    fun kiutakuCurrentGalleryExposesAndServesImages() {
        val http = HttpClient()
        val adapter = KiutakuAdapter(http)
        val ref = GalleryRef(
            source = SourceId.KIUTAKU,
            stableId = "7331",
            entityStableId = "site:all",
            canonicalUrl = "https://kiutaku.com/7331",
            title = "live-smoke"
        )

        val (_, media) = adapter.fetchGallery(ref)
        assertTrue("Kiutaku exposed no media", media.isNotEmpty())

        val first = media.first()
        http.open(first.url, first.referer).use { response ->
            val type = response.contentType.orEmpty()
            val probe = ByteArray(64)
            val read = response.input.read(probe)
            assertTrue("Kiutaku first image returned no bytes; type=" + type + " url=" + first.url, read > 0)
            assertTrue("Kiutaku first media was not an image; type=" + type + " url=" + first.url, type.startsWith("image/"))
        }
    }

    @Test
    fun cosplayTeleVideoPostAddsSupplementWhenVideoNotEmbedded() {
        val http = HttpClient()
        val adapter = CosplayTeleAdapter(http)
        val ref = GalleryRef(
            source = SourceId.COSPLAYTELE,
            stableId = "fleurdelys-4",
            entityStableId = "site:all",
            canonicalUrl = "https://cosplaytele.com/fleurdelys-4/",
            title = "Pyoncos cosplay Fleurdelys - 68 photos and 1 video"
        )

        val (_, media) = adapter.fetchGallery(ref)
        assertTrue("CosplayTele exposed no media", media.isNotEmpty())
        assertTrue(
            "CosplayTele video post exposed neither a direct video nor a video-only archive supplement: " +
                media.joinToString { it.kind.name + ":" + (it.provider ?: "-") + ":" + it.url },
            media.any { it.kind == MediaKind.VIDEO } ||
                media.any { it.kind == MediaKind.ARCHIVE && it.archiveVideosOnly }
        )
    }
}
