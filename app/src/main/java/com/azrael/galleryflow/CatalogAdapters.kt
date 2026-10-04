package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private fun normalizeInputUrl(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun absoluteUrl(element: Element, attr: String): String =
    element.absUrl(attr).takeIf { it.isNotBlank() }
        ?: runCatching {
            URI(element.ownerDocument()?.baseUri().orEmpty()).resolve(element.attr(attr)).toString()
        }.getOrDefault(element.attr(attr))

private fun normalizedUrl(url: String): String = runCatching {
    val uri = URI(url)
    URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, null).toString()
}.getOrDefault(url.substringBefore('#'))

private fun shaText(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun imageMime(url: String): String? {
    val p = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
    return when {
        p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
        p.endsWith(".png") -> "image/png"
        p.endsWith(".webp") -> "image/webp"
        p.endsWith(".gif") -> "image/gif"
        p.endsWith(".avif") -> "image/avif"
        else -> null
    }
}

private fun titleOf(doc: Document, fallback: String): String =
    doc.selectFirst("h1,h2,h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
        ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        ?: doc.title().trim().takeIf { it.isNotBlank() }
        ?: fallback

class FourKhdAdapter(private val http: HttpClient) : SourceAdapter {
    override val source = SourceId.FOUR_K_HD
    override val enabled = true
    override val statusLabel = "Enabled • whole-site • TeraBox archives"

    override fun defaultEntities(): List<SourceEntity> = listOf(
        SourceEntity(source, "site:cosplay", "4KHD — cosplay", "https://www.4khd.com/pages/cosplay", "site"),
        SourceEntity(source, "site:album", "4KHD — album", "https://www.4khd.com/pages/album", "site")
    )

    override fun matches(url: String): Boolean = runCatching {
        val host = URI(normalizeInputUrl(url)).host?.lowercase(Locale.ROOT).orEmpty()
        host == "4khd.com" || host.endsWith(".4khd.com")
    }.getOrDefault(false)

    override fun resolveEntity(inputUrl: String): SourceEntity {
        val uri = URI(normalizeInputUrl(inputUrl))
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "4khd.com" && !host.endsWith(".4khd.com")) throw AdapterException("Not a 4KHD URL")
        val path = uri.path.trimEnd('/')
        if (path == "/pages/album") return defaultEntities()[1]
        if (path == "/pages/cosplay" || path.isBlank()) return defaultEntities()[0]
        if (path.startsWith("/content/") && path.endsWith(".html")) {
            val stable = path.removePrefix("/content/").removeSuffix(".html")
            return SourceEntity(source, "gallery:$stable", "4KHD gallery", "https://www.4khd.com$path", "gallery_only")
        }
        return defaultEntities()[0]
    }

    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = sequence {
        if (entity.kind == "gallery_only") {
            val doc = http.document(entity.canonicalUrl)
            val id = URI(entity.canonicalUrl).path.removePrefix("/content/").removeSuffix(".html")
            yield(GalleryRef(source, id, entity.stableId, entity.canonicalUrl, titleOf(doc, id)))
            return@sequence
        }

        val visited = linkedSetOf<String>()
        val seen = linkedSetOf<String>()
        var next: String? = entity.canonicalUrl
        while (!next.isNullOrBlank() && visited.add(next)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val doc = http.document(next)
            for (a in doc.select("a[href*=/content/]")) {
                val href = absoluteUrl(a, "href")
                val uri = runCatching { URI(href) }.getOrNull() ?: continue
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                if (host != "4khd.com" && host != "www.4khd.com") continue
                val path = uri.path.trimEnd('/')
                if (!path.startsWith("/content/") || !path.endsWith(".html")) continue
                val id = path.removePrefix("/content/").removeSuffix(".html")
                if (!seen.add(id)) continue
                yield(GalleryRef(source, id, entity.stableId, "https://www.4khd.com$path", a.text().trim().ifBlank { id }))
            }
            next = nextListing(doc, entity.canonicalUrl, visited)
        }
    }

    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> {
        val doc = http.document(gallery.canonicalUrl)
        val title = titleOf(doc, gallery.title)
        val meta = GalleryMeta(
            source, gallery.stableId, gallery.entityStableId, gallery.canonicalUrl,
            title, emptyList(), gallery.publishedAt, listOf(gallery.canonicalUrl)
        )
        val links = doc.select("a[href]").mapNotNull { a ->
            val href = absoluteUrl(a, "href")
            val text = a.text().lowercase(Locale.ROOT)
            val host = runCatching { URI(href).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
            if ("terabox" in text || "terabox" in host || host == "m.4khd.com") href else null
        }.distinct()
        if (links.isEmpty()) throw AdapterException("4KHD gallery exposed no TeraBox archive link.")
        val chosen = links.first()
        val media = MediaRef(
            source = source,
            stableId = shaText("archive:$chosen").take(24),
            galleryStableId = gallery.stableId,
            index = 0,
            url = chosen,
            normalizedUrl = normalizedUrl(chosen),
            referer = gallery.canonicalUrl,
            mimeHint = "application/zip",
            kind = MediaKind.ARCHIVE,
            provider = "TeraBox",
            archivePassword = "4KHD"
        )
        return meta to listOf(media)
    }

    private fun nextListing(doc: Document, baseUrl: String, visited: Set<String>): String? {
        doc.selectFirst("a[rel=next]")?.let {
            val href = absoluteUrl(it, "href")
            if (href !in visited) return href
        }
        val base = URI(baseUrl)
        return doc.select("a[href]").mapNotNull { a ->
            val href = absoluteUrl(a, "href")
            if (href in visited) return@mapNotNull null
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("4khd.com", "www.4khd.com")) return@mapNotNull null
            if (uri.path.trimEnd('/') != base.path.trimEnd('/')) return@mapNotNull null
            val page = Regex("(?:^|&)(?:query-\\d+-page|page)=(\\d+)").find(uri.query.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().replace(",", "").toIntOrNull()
            if (page == null) null else page to href
        }.minByOrNull { it.first }?.second
    }
}

class BuonDuaAdapter(private val http: HttpClient) : SourceAdapter {
    override val source = SourceId.BUONDUA
    override val enabled = true
    override val statusLabel = "Enabled • whole-site • direct CDN with TeraBox preference"

    override fun defaultEntities(): List<SourceEntity> = listOf(
        SourceEntity(source, "site:all", "BuonDua — entire site", "https://buondua.net/albums", "site")
    )

    override fun matches(url: String): Boolean = runCatching {
        val host = URI(normalizeInputUrl(url)).host?.lowercase(Locale.ROOT).orEmpty()
        host == "buondua.net" || host.endsWith(".buondua.net")
    }.getOrDefault(false)

    override fun resolveEntity(inputUrl: String): SourceEntity {
        val uri = URI(normalizeInputUrl(inputUrl))
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "buondua.net" && !host.endsWith(".buondua.net")) throw AdapterException("Not a BuonDua URL")
        val path = uri.path.trimEnd('/')
        if (path.startsWith("/albums/") && path != "/albums") {
            val id = path.substringAfterLast('/')
            return SourceEntity(source, "gallery:$id", "BuonDua gallery", "https://buondua.net$path", "gallery_only")
        }
        return defaultEntities().first()
    }

    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = sequence {
        if (entity.kind == "gallery_only") {
            val doc = http.document(entity.canonicalUrl)
            val id = URI(entity.canonicalUrl).path.substringAfterLast('/')
            yield(GalleryRef(source, id, entity.stableId, entity.canonicalUrl, titleOf(doc, id), published(doc)))
            return@sequence
        }

        val visited = linkedSetOf<String>()
        val seen = linkedSetOf<String>()
        var next: String? = entity.canonicalUrl
        while (!next.isNullOrBlank() && visited.add(next)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val doc = http.document(next)
            for (a in doc.select("a[href*=/albums/]")) {
                val href = absoluteUrl(a, "href")
                val uri = runCatching { URI(href) }.getOrNull() ?: continue
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                if (host != "buondua.net" && host != "www.buondua.net") continue
                val path = uri.path.trimEnd('/')
                if (!path.startsWith("/albums/") || path == "/albums") continue
                val id = path.substringAfterLast('/')
                if (!seen.add(id)) continue
                yield(GalleryRef(source, id, entity.stableId, "https://buondua.net$path", a.text().trim().ifBlank { id }))
            }
            next = nextListing(doc, entity.canonicalUrl, visited)
        }
    }

    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> {
        val docs = linkedMapOf<String, Document>()
        val first = http.document(gallery.canonicalUrl)
        docs[gallery.canonicalUrl] = first

        val pages = first.select("a[href]").map { absoluteUrl(it, "href") }
            .filter { isAlbumPage(gallery.canonicalUrl, it) }
            .distinct()
            .sortedBy { pageNumber(it) }
        for (url in pages) if (url !in docs) docs[url] = http.document(url, gallery.canonicalUrl)

        val tags = first.select("a[href*=/tags/]").map { it.text().trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct()
        val meta = GalleryMeta(
            source, gallery.stableId, gallery.entityStableId, gallery.canonicalUrl,
            titleOf(first, gallery.title), tags, published(first) ?: gallery.publishedAt, docs.keys.toList()
        )

        val external = docs.values.flatMap { pageDoc ->
            pageDoc.select("a[href]").mapNotNull { a ->
                val href = absoluteUrl(a, "href")
                val host = runCatching { URI(href).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                if ("terabox" in host || a.text().contains("terabox", true)) href else null
            }
        }.distinct()

        if (external.isNotEmpty()) {
            val chosen = external.first()
            return meta to listOf(
                MediaRef(
                    source, shaText("archive:$chosen").take(24), gallery.stableId, 0,
                    chosen, normalizedUrl(chosen), gallery.canonicalUrl, "application/zip",
                    MediaKind.ARCHIVE, "TeraBox", null
                )
            )
        }

        val prefix = "/photos/" + gallery.stableId + "/"
        val media = linkedMapOf<String, MediaRef>()
        var index = 0
        for ((pageUrl, pageDoc) in docs) {
            for (img in pageDoc.select("img")) {
                val candidates = listOf("data-original", "data-src", "data-lazy-src", "src")
                    .mapNotNull { attr -> img.attr(attr).trim().takeIf { it.isNotBlank() } }
                val src = candidates.firstOrNull() ?: continue
                val url = runCatching { URI(pageDoc.baseUri()).resolve(src).toString() }.getOrDefault(src)
                val uri = runCatching { URI(url) }.getOrNull() ?: continue
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                if (host != "cdn.buondua.net" || !uri.path.startsWith(prefix)) continue
                val norm = normalizedUrl(url)
                if (norm in media) continue
                media[norm] = MediaRef(
                    source, shaText(norm).take(24), gallery.stableId, index++,
                    url, norm, pageUrl, imageMime(url), MediaKind.IMAGE
                )
            }
        }
        if (media.isEmpty()) throw AdapterException("BuonDua album exposed neither a TeraBox archive nor gallery CDN media.")
        return meta to media.values.toList()
    }

    private fun nextListing(doc: Document, baseUrl: String, visited: Set<String>): String? {
        doc.selectFirst("a[rel=next],a:matchesOwn((?i)^Next)")?.let {
            val href = absoluteUrl(it, "href")
            if (href !in visited) return href
        }
        val basePath = URI(baseUrl).path.trimEnd('/')
        return doc.select("a[href]").mapNotNull { a ->
            val href = absoluteUrl(a, "href")
            if (href in visited) return@mapNotNull null
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("buondua.net", "www.buondua.net")) return@mapNotNull null
            if (uri.path.trimEnd('/') != basePath) return@mapNotNull null
            val page = Regex("(?:^|&)page=(\\d+)").find(uri.query.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?: a.text().trim().replace(",", "").toIntOrNull()
            if (page == null) null else page to href
        }.minByOrNull { it.first }?.second
    }

    private fun isAlbumPage(base: String, candidate: String): Boolean {
        val b = URI(base)
        val c = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (c.host?.lowercase(Locale.ROOT) !in setOf("buondua.net", "www.buondua.net")) return false
        return c.path.trimEnd('/') == b.path.trimEnd('/') && c.query?.contains("page=") == true
    }

    private fun pageNumber(url: String): Int =
        Regex("(?:^|&)page=(\\d+)").find(URI(url).query.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun published(doc: Document): String? =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }
            ?: Regex("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}")
                .find(doc.body().text())?.value
}

class CosplayTeleAdapter(private val http: HttpClient) : SourceAdapter {
    override val source = SourceId.COSPLAYTELE
    override val enabled = true
    override val statusLabel = "Enabled • whole-site • MediaFire/SoraFolder/Gofile archives"

    override fun defaultEntities(): List<SourceEntity> = listOf(
        SourceEntity(source, "site:all", "CosplayTele — entire site", "https://cosplaytele.com/", "site")
    )

    override fun matches(url: String): Boolean = runCatching {
        val host = URI(normalizeInputUrl(url)).host?.lowercase(Locale.ROOT).orEmpty()
        host == "cosplaytele.com" || host.endsWith(".cosplaytele.com")
    }.getOrDefault(false)

    override fun resolveEntity(inputUrl: String): SourceEntity {
        val uri = URI(normalizeInputUrl(inputUrl))
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "cosplaytele.com" && !host.endsWith(".cosplaytele.com")) throw AdapterException("Not a CosplayTele URL")
        val path = uri.path.trim('/')
        if (path.isNotBlank() && '/' !in path && path !in NON_POST_SLUGS) {
            return SourceEntity(source, "gallery:$path", "CosplayTele gallery", "https://cosplaytele.com/$path/", "gallery_only")
        }
        return defaultEntities().first()
    }

    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = sequence {
        if (entity.kind == "gallery_only") {
            val doc = http.document(entity.canonicalUrl)
            val id = URI(entity.canonicalUrl).path.trim('/').substringBefore('/')
            yield(GalleryRef(source, id, entity.stableId, entity.canonicalUrl, titleOf(doc, id), published(doc)))
            return@sequence
        }

        val visited = linkedSetOf<String>()
        val seen = linkedSetOf<String>()
        var next: String? = entity.canonicalUrl
        while (!next.isNullOrBlank() && visited.add(next)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val doc = http.document(next)
            val anchors = doc.select("article a[href],h2 a[href],h3 a[href],h4 a[href],h5 a[href],.post-title a[href],.entry-title a[href]")
            for (a in anchors) {
                val text = a.text().trim()
                val href = absoluteUrl(a, "href")
                val uri = runCatching { URI(href) }.getOrNull() ?: continue
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                if (host != "cosplaytele.com" && host != "www.cosplaytele.com") continue
                val slug = uri.path.trim('/')
                if (slug.isBlank() || '/' in slug || slug in NON_POST_SLUGS) continue
                if (!seen.add(slug)) continue
                yield(GalleryRef(source, slug, entity.stableId, "https://cosplaytele.com/$slug/", text.ifBlank { slug }))
            }
            next = nextListing(doc, visited)
        }
    }

    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> {
        val doc = http.document(gallery.canonicalUrl)
        val title = titleOf(doc, gallery.title)
        val tags = doc.select("a[rel=tag],a[href*=/category/],a[href*=/tag/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val meta = GalleryMeta(
            source, gallery.stableId, gallery.entityStableId, gallery.canonicalUrl,
            title, tags, published(doc) ?: gallery.publishedAt, listOf(gallery.canonicalUrl)
        )

        val candidates = providerLinks(doc)
        val chosen = candidates.sortedBy { providerPriority(it.second) }.firstOrNull()
            ?: throw AdapterException("CosplayTele post exposed no supported MediaFire/SoraFolder/Gofile/Telegram download link.")
        val provider = chosen.second
        val password = extractPassword(doc)
        val media = MediaRef(
            source = source,
            stableId = shaText("archive:" + chosen.first).take(24),
            galleryStableId = gallery.stableId,
            index = 0,
            url = chosen.first,
            normalizedUrl = normalizedUrl(chosen.first),
            referer = gallery.canonicalUrl,
            mimeHint = "application/zip",
            kind = MediaKind.ARCHIVE,
            provider = provider,
            archivePassword = password
        )
        return meta to listOf(media)
    }

    private fun nextListing(doc: Document, visited: Set<String>): String? {
        doc.selectFirst("a[rel=next],a.next,a.next.page-numbers")?.let {
            val href = absoluteUrl(it, "href")
            if (href !in visited) return href
        }
        return doc.select("a[href]").mapNotNull { a ->
            val href = absoluteUrl(a, "href")
            if (href in visited) return@mapNotNull null
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("cosplaytele.com", "www.cosplaytele.com")) return@mapNotNull null
            val page = Regex("^/page/(\\d+)/?$").matchEntire(uri.path)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            page to href
        }.minByOrNull { it.first }?.second
    }

    private fun providerLinks(doc: Document): List<Pair<String, String>> {
        val found = linkedMapOf<String, String>()
        fun add(raw: String) {
            val url = raw.replace("&amp;", "&").trim(' ', '\'', '"')
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
            val provider = when {
                "mediafire.com" in host -> "MediaFire"
                "sorafolder.com" in host -> "SoraFolder"
                "gofile.io" in host -> "Gofile"
                host == "t.me" || host.endsWith(".t.me") -> "Telegram"
                else -> return
            }
            found.putIfAbsent(url, provider)
        }

        for (a in doc.select("a")) {
            listOf("href", "data-url", "data-href", "data-download").forEach { attr ->
                a.attr(attr).takeIf { it.isNotBlank() }?.let(::add)
            }
            URL_REGEX.findAll(a.attr("onclick")).forEach { add(it.value) }
        }
        URL_REGEX.findAll(doc.html()).forEach { add(it.value) }
        return found.map { it.key to it.value }
    }

    private fun providerPriority(provider: String): Int = when (provider) {
        "MediaFire" -> 0
        "SoraFolder" -> 1
        "Gofile" -> 2
        "Telegram" -> 3
        else -> 9
    }

    private fun extractPassword(doc: Document): String? {
        val input = doc.selectFirst("input[value][name*=pass],input[value][id*=pass]")
            ?.attr("value")?.trim()?.takeIf { it.isNotBlank() }
        if (input != null) return input
        return Regex("(?i)(?:Unzip|Extract(?:ing)?)\\s*Password\\s*[:：]\\s*([A-Za-z0-9_.@#-]{2,40})")
            .find(doc.body().text())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun published(doc: Document): String? =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }
            ?: Regex("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}")
                .find(doc.body().text())?.value

    companion object {
        private val NON_POST_SLUGS = setOf(
            "privacy-policy","dmca","about","contact","category","tag","author","wp-admin","wp-login.php",
            "explore","top-search","best-cosplayer","video-cosplay","cosplay-nude","cosplay-ero"
        )
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
