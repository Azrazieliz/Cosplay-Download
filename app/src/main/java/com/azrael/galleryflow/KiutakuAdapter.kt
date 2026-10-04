package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

class KiutakuAdapter(private val http: HttpClient) : SourceAdapter {
    override val source = SourceId.KIUTAKU
    override val enabled = true
    override val statusLabel = "Enabled • direct images • whole-site crawl"

    override fun defaultEntities(): List<SourceEntity> = listOf(
        SourceEntity(source, "site:all", "Kiutaku — entire site", "https://kiutaku.com/", "site")
    )

    override fun matches(url: String): Boolean = runCatching {
        val host = URI(normalizeInput(url)).host?.lowercase(Locale.ROOT).orEmpty()
        host == "kiutaku.com" || host.endsWith(".kiutaku.com")
    }.getOrDefault(false)

    override fun resolveEntity(inputUrl: String): SourceEntity {
        val uri = URI(normalizeInput(inputUrl))
        requireHost(uri)
        val path = uri.path.trimEnd('/')
        if (path.isBlank()) return defaultEntities().first()

        TAG_PATH.matchEntire(path)?.let { match ->
            val id = match.groupValues[1]
            val canonical = "https://kiutaku.com/tag/" + id
            val doc = http.document(canonical)
            val name = doc.selectFirst("h1")?.text()
                ?.replace(Regex("^Tag:\\s*", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Kiutaku tag " + id
            return SourceEntity(source, "tag:" + id, name, canonical, "tag")
        }

        GALLERY_PATH.matchEntire(path)?.let { match ->
            val galleryId = match.groupValues[1]
            val canonical = "https://kiutaku.com/" + galleryId
            val doc = http.document(canonical)
            val cosplayer = extractCosplayer(doc)
            if (!cosplayer.isNullOrBlank()) {
                val tagLink = doc.select("a[href]").firstOrNull { element ->
                    val href = absolute(element, "href")
                    val candidatePath = runCatching { URI(href).path.trimEnd('/') }.getOrDefault("")
                    TAG_PATH.matches(candidatePath) && element.text().trim().equals(cosplayer, ignoreCase = true)
                }
                if (tagLink != null) return resolveEntity(absolute(tagLink, "href"))
            }
            val title = extractTitle(doc).ifBlank { "Kiutaku gallery " + galleryId }
            return SourceEntity(source, "gallery:" + galleryId, cosplayer ?: title, canonical, "gallery_only")
        }

        return defaultEntities().first()
    }

    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = sequence {
        if (entity.kind == "gallery_only") {
            val id = GALLERY_PATH.matchEntire(URI(entity.canonicalUrl).path.trimEnd('/'))
                ?.groupValues?.get(1) ?: throw AdapterException("Invalid Kiutaku gallery-only entity URL")
            val doc = http.document(entity.canonicalUrl)
            yield(GalleryRef(source, id, entity.stableId, entity.canonicalUrl, extractTitle(doc), extractPublishedAt(doc)))
            return@sequence
        }

        val visitedPages = linkedSetOf<String>()
        val seenGalleries = linkedSetOf<String>()
        var next: String? = entity.canonicalUrl
        while (!next.isNullOrBlank() && visitedPages.add(next)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val doc = http.document(next)
            val listingAnchors = doc.select(".items-row .item-thumb a[href]").ifEmpty { doc.select("a[href]") }
            for (anchor in listingAnchors) {
                val href = absolute(anchor, "href")
                val match = GALLERY_PATH.matchEntire(runCatching { URI(href).path.trimEnd('/') }.getOrDefault(""))
                    ?: continue
                val id = match.groupValues[1]
                if (!seenGalleries.add(id)) continue
                val title = anchor.text().trim().ifBlank { "Kiutaku " + id }
                yield(GalleryRef(source, id, entity.stableId, "https://kiutaku.com/" + id, title))
            }
            next = nextListingPage(doc, entity.canonicalUrl, visitedPages)
        }
    }

    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> {
        val docs = linkedMapOf<String, Document>()
        val first = http.document(gallery.canonicalUrl)
        docs[gallery.canonicalUrl] = first

        val pages = first.select("a[href]")
            .map { absolute(it, "href") }
            .filter { isGalleryPaginationUrl(gallery.canonicalUrl, it) }
            .distinct()
            .sortedBy { paginationNumber(gallery.canonicalUrl, it) }

        for (url in pages) if (url !in docs) docs[url] = http.document(url, gallery.canonicalUrl)

        val meta = GalleryMeta(
            source = source,
            stableId = gallery.stableId,
            entityStableId = gallery.entityStableId,
            canonicalUrl = gallery.canonicalUrl,
            title = extractTitle(first).ifBlank { gallery.title },
            tags = first.select("a[href*=/tag/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
            publishedAt = extractPublishedAt(first) ?: gallery.publishedAt,
            pageUrls = docs.keys.toList()
        )

        val media = linkedMapOf<String, MediaRef>()
        var index = 0
        for ((pageUrl, doc) in docs) {
            for (candidate in imageCandidates(doc)) {
                val normalized = normalizeMediaUrl(candidate)
                if (normalized in media) continue
                media[normalized] = MediaRef(
                    source = source,
                    stableId = sha256Text(normalized).take(24),
                    galleryStableId = gallery.stableId,
                    index = index++,
                    url = candidate,
                    normalizedUrl = normalized,
                    referer = pageUrl,
                    mimeHint = mimeFromPath(candidate),
                    kind = MediaKind.IMAGE
                )
            }
        }
        if (media.isEmpty()) throw AdapterException("Kiutaku gallery " + gallery.stableId + " exposed no downloadable gallery images.")
        return meta to media.values.toList()
    }

    internal fun imageCandidates(doc: Document): List<String> {
        val out = linkedSetOf<String>()

        // Current Kiutaku/Xiutaku/BuonDua-style gallery markup keeps the album
        // media specifically inside .article-fulltext. Prefer that exact scope.
        val scoped = doc.select(".article-fulltext img")
        val elements = if (scoped.isNotEmpty()) scoped else doc.select("article img,main img")

        for (img in elements) {
            val raw = bestImageAttribute(img) ?: continue
            val candidate = runCatching { URI(doc.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
            if (isGalleryImage(img, candidate)) out += candidate
        }

        // Some mirrors wrap the displayed image in a link to the original.
        for (img in elements) {
            val anchor = img.parents().firstOrNull { it.tagName() == "a" && it.hasAttr("href") } ?: continue
            val href = absolute(anchor, "href")
            val path = runCatching { URI(href).path.lowercase(Locale.ROOT) }.getOrDefault("")
            if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) out += href
        }

        return out.toList()
    }

    private fun bestImageAttribute(img: Element): String? {
        for (attr in listOf("data-original", "data-full", "data-src", "data-lazy-src")) {
            img.attr(attr).trim().takeIf { it.isNotBlank() }?.let { return it }
        }
        img.attr("srcset").trim().takeIf { it.isNotBlank() }?.let { srcset ->
            val candidates = srcset.split(',').mapNotNull { item ->
                val bits = item.trim().split(Regex("\\s+"))
                val url = bits.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val score = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                score to url
            }
            candidates.maxByOrNull { it.first }?.second?.let { return it }
        }
        return img.attr("src").trim().takeIf { it.isNotBlank() }
    }

    private fun isGalleryImage(img: Element, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val lower = url.lowercase(Locale.ROOT)
        if (listOf("logo","icon","avatar","emoji","ads","banner","favicon","pixel").any { it in lower }) return false
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrDefault(lower)
        val imageLike = IMAGE_EXTENSIONS.any { path.endsWith(it) } ||
            img.attr("alt").contains("photo", ignoreCase = true) ||
            img.classNames().any { it.contains("image", ignoreCase = true) }
        if (!imageLike) return false
        val width = img.attr("width").toIntOrNull()
        val height = img.attr("height").toIntOrNull()
        return !(width != null && height != null && width < 200 && height < 200)
    }

    private fun nextListingPage(doc: Document, entityUrl: String, visited: Set<String>): String? {
        doc.selectFirst("a[rel=next]")?.let {
            val href = absolute(it, "href")
            if (href !in visited) return href
        }

        val base = URI(entityUrl)
        val entityPath = base.path.trimEnd('/')
        val currentPage = Regex("(?:^|&)(?:page|paged)=(\\d+)")
            .find(runCatching { URI(doc.baseUri()).query.orEmpty() }.getOrDefault(""))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val candidates = doc.select(".pagination-list a[href],a[href]").mapNotNull { anchor ->
            val href = absolute(anchor, "href")
            if (href in visited) return@mapNotNull null
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.lowercase(Locale.ROOT)
            if (host != "kiutaku.com" && host != "www.kiutaku.com") return@mapNotNull null
            if (uri.path.trimEnd('/') != entityPath) return@mapNotNull null
            val page = Regex("(?:^|&)(?:page|paged)=(\\d+)").find(uri.query.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: anchor.text().trim().toIntOrNull()
            if (page == null || page <= currentPage) null else page to href
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun isGalleryPaginationUrl(base: String, candidate: String): Boolean {
        if (candidate == base || candidate.startsWith("javascript:")) return false
        val baseUri = URI(base)
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (uri.host?.lowercase() != baseUri.host?.lowercase()) return false
        val basePath = baseUri.path.trimEnd('/')
        val path = uri.path.trimEnd('/')
        if (path.startsWith(basePath + "/")) return path.removePrefix(basePath + "/").toIntOrNull() != null
        return path == basePath && uri.query?.contains("page=") == true
    }

    private fun paginationNumber(base: String, candidate: String): Int {
        val basePath = URI(base).path.trimEnd('/')
        val uri = URI(candidate)
        val suffix = uri.path.trimEnd('/').removePrefix(basePath).trim('/')
        return suffix.toIntOrNull()
            ?: Regex("(?:^|&)page=(\\d+)").find(uri.query.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun extractTitle(doc: Document): String =
        doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().substringBefore(" - Mitaku").trim()

    private fun extractCosplayer(doc: Document): String? =
        Regex("Cosplayer:\\s*([^#|]+?)(?=\\s+#|$)", RegexOption.IGNORE_CASE)
            .find(doc.body().text())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.select("a[href*=/tag/]").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun extractPublishedAt(doc: Document): String? =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }
            ?: Regex("\\b\\d{2}-\\d{2}-\\d{4}\\b").find(doc.body().text())?.value

    private fun absolute(element: Element, attr: String): String =
        element.absUrl(attr).takeIf { it.isNotBlank() }
            ?: runCatching { URI(element.ownerDocument()?.baseUri().orEmpty()).resolve(element.attr(attr)).toString() }
                .getOrDefault(element.attr(attr))

    private fun normalizeMediaUrl(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, null, null).toString()
    }.getOrDefault(url.substringBefore('?').substringBefore('#'))

    private fun mimeFromPath(url: String): String? {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault(url.lowercase())
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".avif") -> "image/avif"
            else -> null
        }
    }

    private fun normalizeInput(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://" + trimmed
    }

    private fun requireHost(uri: URI) {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host != "kiutaku.com" && !host.endsWith(".kiutaku.com")) throw AdapterException("Not a Kiutaku URL")
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private val TAG_PATH = Regex("/tag/(\\d+)")
        private val GALLERY_PATH = Regex("/(\\d+)")
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
    }
}
