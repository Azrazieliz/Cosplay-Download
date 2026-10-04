package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KiutakuAdapterTest {
    @Test
    fun imageCandidatesUseCurrentArticleFulltextMarkup() {
        val html = """
            <html><body>
              <div class="header"><img src="/assets/logo.png" width="80" height="80" alt="logo"></div>
              <div class="article-fulltext">
                <a href="https://cdn.example/7333/original-001.jpg">
                  <img src="data:image/gif;base64,placeholder" alt="Example photo 1-0">
                </a>
                <img data-src="https://cdn.example/7333/002.jpg?size=full" alt="Example photo 1-1">
                <img src="https://cdn.example/7333/003.webp" alt="Example photo 1-2">
              </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://kiutaku.com/7333")
        val adapter = KiutakuAdapter(HttpClient())
        val urls = adapter.imageCandidates(doc)

        assertEquals(3, urls.size)
        assertEquals("https://cdn.example/7333/original-001.jpg", urls[0])
        assertTrue(urls[1].contains("002.jpg"))
        assertTrue(urls[2].contains("003.webp"))
        assertTrue(urls.none { it.contains("logo.png") })
    }
}
