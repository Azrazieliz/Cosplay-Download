package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGalleryParserTest {
    @Test
    fun extractsLinkedImagesLazyImagesAndVideosButNotProviderLinks() {
        val html = """
            <html><body>
              <main>
                <a href="https://cdn.example/gallery/full-001.jpg">
                  <img src="https://cdn.example/gallery/thumb-001.jpg" width="900" height="1300">
                </a>
                <img data-original="https://cdn.example/gallery/full-002.webp" width="900" height="1300">
                <video controls>
                  <source src="https://cdn.example/gallery/clip-003.mp4" type="video/mp4">
                </video>
                <a href="https://www.terabox.com/s/example">Download ZIP</a>
                <img src="https://cdn.example/assets/logo.png" width="80" height="80">
              </main>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.4khd.com/content/test.html")
        val media = directPageAssets(doc, "https://www.4khd.com/content/test.html")

        assertTrue(media.any { it.url.endsWith("full-001.jpg") && it.kind == MediaKind.IMAGE })
        assertTrue(media.any { it.url.endsWith("full-002.webp") && it.kind == MediaKind.IMAGE })
        assertTrue(media.any { it.url.endsWith("clip-003.mp4") && it.kind == MediaKind.VIDEO })
        assertTrue(media.none { it.url.contains("terabox") })
        assertTrue(media.none { it.url.contains("logo.png") })
    }

    @Test
    fun prefersLargestSrcsetCandidate() {
        val html = """
            <html><body><article>
              <img src="https://cdn.example/small.jpg"
                   srcset="https://cdn.example/small.jpg 400w, https://cdn.example/large.jpg 1600w"
                   width="800" height="1200">
            </article></body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://cosplaytele.com/example/")
        val media = directPageAssets(doc, "https://cosplaytele.com/example/")

        assertTrue(media.any { it.url.endsWith("large.jpg") })
    }
}
