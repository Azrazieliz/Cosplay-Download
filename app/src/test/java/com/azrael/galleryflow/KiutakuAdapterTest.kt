package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KiutakuAdapterTest {
    @Test
    fun imageCandidatesUseCurrentArticleFulltextMarkupWithoutRejectingUploadsPath() {
        val html = """
            <html><body>
              <div class="header"><img src="/assets/logo.png" width="80" height="80" alt="logo"></div>
              <div class="article-fulltext">
                <img loading="lazy"
                     src="https://mitaku.net/wp-content/uploads/2026/09/Messie-Huang-Chiori-1.jpg"
                     width="1024" height="683" alt="Messie Huang - Chiori - Mitaku photo 1-0">
                <img loading="lazy"
                     src="https://mitaku.net/wp-content/uploads/2026/09/Messie-Huang-Chiori-2.jpg"
                     width="1024" height="683" alt="Messie Huang - Chiori - Mitaku photo 1-1">
                <img loading="lazy"
                     src="https://mitaku.net/wp-content/uploads/2026/09/Messie-Huang-Chiori-3.jpg"
                     width="1024" height="683" alt="Messie Huang - Chiori - Mitaku photo 1-2">
              </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://kiutaku.com/7331")
        val adapter = KiutakuAdapter(HttpClient())
        val urls = adapter.imageCandidates(doc)

        assertEquals(3, urls.size)
        assertTrue(urls.all { it.contains("mitaku.net/wp-content/uploads/") })
        assertTrue(urls.none { it.contains("logo.png") })
    }
}
