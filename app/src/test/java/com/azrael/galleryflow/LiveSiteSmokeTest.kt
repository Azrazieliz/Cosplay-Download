package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSiteSmokeTest {
    @Test
    fun kiutakuCurrentGalleryExposesAndServesImages() {
        val http = HttpClient()
        val url = "https://kiutaku.com/7331"
        val doc = http.document(url)
        val diagnostic = diagnostic(doc)
        val adapter = KiutakuAdapter(http)
        val ref = GalleryRef(
            source = SourceId.KIUTAKU,
            stableId = "7331",
            entityStableId = "site:all",
            canonicalUrl = url,
            title = "live-smoke"
        )

        val media = try {
            adapter.fetchGallery(ref).second
        } catch (t: Throwable) {
            throw AssertionError("Kiutaku fetch failed: " + (t.message ?: t.javaClass.name) + " | " + diagnostic, t)
        }
        assertTrue("Kiutaku exposed no media | " + diagnostic, media.isNotEmpty())

        val first = media.first()
        try {
            http.open(first.url, first.referer).use { response ->
                val type = response.contentType.orEmpty()
                val probe = ByteArray(64)
                val read = response.input.read(probe)
                assertTrue("Kiutaku image returned no bytes; type=" + type + " url=" + first.url, read > 0)
                assertTrue("Kiutaku media was not an image; type=" + type + " url=" + first.url, type.startsWith("image/"))
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "Kiutaku media request failed: " + (t.message ?: t.javaClass.name) +
                    " url=" + first.url + " referer=" + first.referer,
                t
            )
        }
    }

    @Test
    fun cosplayTeleVideoArchiveCanResolveToARealFile() {
        val http = HttpClient()
        val adapter = CosplayTeleAdapter(http)
        val ref = GalleryRef(
            source = SourceId.COSPLAYTELE,
            stableId = "fleurdelys-4",
            entityStableId = "site:all",
            canonicalUrl = "https://cosplaytele.com/fleurdelys-4/",
            title = "Pyoncos cosplay Fleurdelys - 68 photos and 1 video"
        )

        val media = adapter.fetchGallery(ref).second
        val supplement = media.firstOrNull { it.kind == MediaKind.ARCHIVE && it.archiveVideosOnly }
        if (media.any { it.kind == MediaKind.VIDEO }) return

        assertTrue(
            "CosplayTele video post has no direct video and no archive supplement: " +
                media.joinToString { it.kind.name + ":" + (it.provider ?: "-") + ":" + it.url },
            supplement != null
        )

        val archive = requireNotNull(supplement)
        val resolved = try {
            http.resolveDownloadUrl(archive.url, archive.referer, archive.provider)
        } catch (t: Throwable) {
            val providerDoc = runCatching { http.document(archive.url, archive.referer) }.getOrNull()
            val providerDiag = if (providerDoc == null) {
                " provider-page-unavailable"
            } else {
                val buttons = providerDoc.select("button,a[href],[data-url],[data-download],[onclick]")
                    .filter { it.text().contains("download", true) || it.outerHtml().contains("download", true) }
                    .take(8)
                    .joinToString(" || ") { it.outerHtml().replace("\n", " ").take(600) }
                val scripts = providerDoc.select("script").mapNotNull { script ->
                    val src = script.attr("src").takeIf { it.isNotBlank() }
                    val body = script.data().takeIf { it.contains("download", true) || it.contains("timer", true) || it.contains("countdown", true) }
                    when {
                        src != null -> "src=" + src
                        body != null -> "inline=" + body.replace("\n", " ").take(1000)
                        else -> null
                    }
                }.take(12).joinToString(" || ")
                " providerBase=" + providerDoc.baseUri() + " buttons=" + buttons + " scripts=" + scripts
            }
            throw AssertionError(
                "CosplayTele " + archive.provider + " archive could not resolve: " +
                    (t.message ?: t.javaClass.name) + " url=" + archive.url + providerDiag,
                t
            )
        }

        try {
            http.open(resolved, archive.url).use { response ->
                val probe = ByteArray(64)
                val read = response.input.read(probe)
                assertTrue(
                    "Resolved CosplayTele archive returned no bytes; provider=" + archive.provider +
                        " resolved=" + resolved + " type=" + response.contentType,
                    read > 0
                )
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "Resolved CosplayTele archive could not be opened: " + (t.message ?: t.javaClass.name) +
                    " provider=" + archive.provider + " resolved=" + resolved,
                t
            )
        }
    }

    private fun diagnostic(doc: Document): String {
        val samples = doc.select(".article-fulltext img").take(4).joinToString(" || ") {
            it.outerHtml().replace("\n", " ").take(900)
        }
        return "base=" + doc.baseUri() +
            " imgs=" + doc.select("img").size +
            " articleFulltextImgs=" + doc.select(".article-fulltext img").size +
            " mitakuImageLinks=" + doc.select("a[href*=mitaku.net]").size +
            " imageAnchors=" + doc.select("a[href$=.jpg],a[href$=.jpeg],a[href$=.png],a[href$=.webp]").size +
            " samples=" + samples
    }
}
