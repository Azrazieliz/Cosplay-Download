package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KiutakuAdapterTest {
    @Test
    fun imageCandidatesPreferGalleryImagesAndIgnoreIcons() {
        val html = """
            <html><body><main>
              <img src="/assets/logo.png" width="80" height="80" alt="logo">
              <img data-src="https://img.example/7333/001.jpg?size=full" alt="Example photo 1-0">
              <img src="https://img.example/7333/002.webp" alt="Example photo 1-1">
            </main></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://kiutaku.com/7333")
        val adapter = KiutakuAdapter(HttpClient())
        val urls = adapter.imageCandidates(doc)
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("001.jpg"))
        assertTrue(urls[1].contains("002.webp"))
    }
}
