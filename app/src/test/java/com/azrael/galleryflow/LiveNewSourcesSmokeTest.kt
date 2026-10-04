package com.azrael.galleryflow

import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNewSourcesSmokeTest {
    @Test
    fun fourKGirlCurrentPostExposesDirectImages() {
        val http = HttpClient()
        val adapter = FourKGirlAdapter(http)
        val ref = GalleryRef(
            source = SourceId.FOUR_K_GIRL,
            stableId = "potato-godzilla-mai-shiranui-casual-4kgirl-25p",
            entityStableId = "site:all",
            canonicalUrl = "https://www.4kgirl.com/potato-godzilla-mai-shiranui-casual-4kgirl-25p/",
            title = "live"
        )
        val media = adapter.fetchGallery(ref).second
        assertTrue("4KGirl exposed too few direct assets: " + media.size, media.size >= 10)
        val first = media.first { it.kind == MediaKind.IMAGE }
        http.open(first.url, first.referer).use { response ->
            assertTrue("4KGirl first file is not an image: " + response.contentType, response.contentType.orEmpty().startsWith("image/"))
        }
    }

    @Test
    fun everiaCurrentCosplayPostExposesDirectImages() {
        val http = HttpClient()
        val adapter = EveriaAdapter(http)
        val ref = GalleryRef(
            source = SourceId.EVERIA,
            stableId = "2026:10:02:cosplay-nnian",
            entityStableId = "site:all",
            canonicalUrl = "https://everia.club/2026/10/02/cosplay-%E5%B9%B4%E5%B9%B4nnian-%E9%9D%A2%E5%85%B7/",
            title = "live"
        )
        val media = adapter.fetchGallery(ref).second
        assertTrue("Everia exposed too few direct assets: " + media.size, media.size >= 10)
        val first = media.first { it.kind == MediaKind.IMAGE }
        http.open(first.url, first.referer).use { response ->
            assertTrue("Everia first file is not an image: " + response.contentType, response.contentType.orEmpty().startsWith("image/"))
        }
    }
}
