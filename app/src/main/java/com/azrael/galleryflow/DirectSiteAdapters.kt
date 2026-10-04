package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private abstract class DirectGalleryAdapter(
    protected val http: HttpClient,
    override val source: SourceId,
    private val host: String,
    private val rootUrl: String,
    private val entityName: String,
    override val statusLabel: String
) : SourceAdapter {
    override val enabled: Boolean = true

    override fun matches(url: String): Boolean = runCatching {
        val parsed = URI(normalizeInput(url))
        val candidate = parsed.host?.lowercase(Locale.ROOT).orEmpty()
        candidate == host || candidate == "www.$host" || candidate.endsWith(".$host")
    }.getOrDefault(false)

    override fun defaultEntities(): List<SourceEntity> = listOf(
        SourceEntity(source, "site:all", "$entityName — entire site", rootUrl, "site")
    )

    override fun resolveEntity(inputUrl: String): SourceEntity = defaultEntities().first()

    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = sequence {
        val seenPages = linkedSetOf<String>()
        val seenPosts = linkedSetOf<String>()
        var next: String? = entity.canonicalUrl

        while (!next.isNullOrBlank() && seenPages.add(next)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val doc = http.document(next)
            for (anchor in listingAnchors(doc)) {
                val href = absolute(anchor, "href")
                val uri = runCatching { URI(href) }.getOrNull() ?: continue
                if (!isOwnHost(uri.host.orEmpty())) continue
                if (!isPostPath(uri.path)) continue

                val canonical = canonicalPostUrl(uri)
                val id = stablePostId(canonical)
                if (!seenPosts.add(id)) continue
                val title = anchor.text().trim()
                    .ifBlank { anchor.attr("title").trim() }
                    .ifBlank { id }
                yield(
                    GalleryRef(
                        source = source,
                        stableId = id,
                        entityStableId = entity.stableId,
                        canonicalUrl = canonical,
                        title = title
                    )
                )
            }
            next = nextListingPage(doc, seenPages)
        }
    }

    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> {
        val docs = linkedMapOf<String, Document>()
        val first = http.document(gallery.canonicalUrl)
        docs[gallery.canonicalUrl] = first

        for (page in postPages(first, gallery.canonicalUrl)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            if (page !in docs) docs[page] = http.document(page, gallery.canonicalUrl)
        }

        val title = first.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: first.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: gallery.title

        val tags = first.select("a[rel=tag],a[href*=/tag/],a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val publishedAt = first.selectFirst("time[datetime]")?.attr("datetime")
            ?.takeIf { it.isNotBlank() }

        val assets = docs.flatMap { (pageUrl, pageDoc) ->
            directPageAssets(pageDoc, pageUrl)
        }.distinctBy { normalizeMediaUrl(it.url) }

        if (assets.isEmpty()) {
            throw AdapterException("$entityName gallery ${gallery.stableId} exposed no direct media.")
        }

        val media = assets.mapIndexed { index, asset ->
            val norm = normalizeMediaUrl(asset.url)
            MediaRef(
                source = source,
                stableId = sha256(source.wireName + ":" + asset.kind.name + ":" + norm).take(24),
                galleryStableId = gallery.stableId,
                index = index,
                url = asset.url,
                normalizedUrl = norm,
                referer = asset.referer,
                mimeHint = asset.mime,
                kind = asset.kind
            )
        }

        return GalleryMeta(
            source = source,
            stableId = gallery.stableId,
            entityStableId = gallery.entityStableId,
            canonicalUrl = gallery.canonicalUrl,
            title = title,
            tags = tags,
            publishedAt = publishedAt,
            pageUrls = docs.keys.toList()
        ) to media
    }

    protected open fun listingAnchors(doc: Document): List<Element> =
        doc.select(
            "article h2 a[href],article h3 a[href],.entry-title a[href],.post-title a[href]," +
                "h2.entry-title a[href],h3.entry-title a[href],main h2 a[href],main h3 a[href]"
        )

    protected abstract fun isPostPath(path: String): Boolean

    private fun postPages(first: Document, baseUrl: String): List<String> {
        val base = URI(baseUrl)
        val basePath = base.path.trimEnd('/')
        return first.select("a[href]").mapNotNull { a ->
            val href = absolute(a, "href")
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            if (!isOwnHost(uri.host.orEmpty())) return@mapNotNull null

            val path = uri.path.trimEnd('/')
            val page = when {
                path.startsWith("$basePath/") ->
                    path.removePrefix("$basePath/").trim('/').toIntOrNull()
                path == basePath ->
                    Regex("(?:^|&)(?:page|paged)=(\\d+)")
                        .find(uri.query.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                else -> null
            }
            if (page == null || page <= 1) null else page to canonicalUrl(uri)
        }.distinctBy { it.second }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun nextListingPage(doc: Document, visited: Set<String>): String? {
        doc.selectFirst("a[rel=next],a.next,a.next.page-numbers")?.let { a ->
            val href = absolute(a, "href")
            if (href !in visited && runCatching { isOwnHost(URI(href).host.orEmpty()) }.getOrDefault(false)) {
                return href
            }
        }

        val current = pageNumber(doc.baseUri())
        return doc.select("a[href]").mapNotNull { a ->
            val href = absolute(a, "href")
            if (href in visited) return@mapNotNull null
            val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            if (!isOwnHost(uri.host.orEmpty())) return@mapNotNull null
            val page = Regex("/page/(\\d+)/?$").find(uri.path)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (page <= current) null else page to href
        }.minByOrNull { it.first }?.second
    }

    private fun pageNumber(url: String): Int =
        runCatching {
            Regex("/page/(\\d+)/?$").find(URI(url).path)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }.getOrDefault(1)

    private fun stablePostId(url: String): String {
        val uri = URI(url)
        return uri.path.trim('/').replace('/', ':').takeIf { it.isNotBlank() }
            ?: sha256(url).take(20)
    }

    private fun canonicalPostUrl(uri: URI): String = canonicalUrl(uri)

    private fun canonicalUrl(uri: URI): String {
        val scheme = uri.scheme ?: "https"
        val normalizedHost = when (uri.host?.lowercase(Locale.ROOT)) {
            host -> host
            "www.$host" -> "www.$host"
            else -> uri.host
        }
        val path = if (uri.path.endsWith("/")) uri.path else uri.path + "/"
        return URI(scheme, null, normalizedHost, -1, path, uri.query, null).toString()
    }

    private fun isOwnHost(candidate: String): Boolean {
        val h = candidate.lowercase(Locale.ROOT)
        return h == host || h == "www.$host" || h.endsWith(".$host")
    }

    private fun absolute(element: Element, attr: String): String =
        element.absUrl(attr).takeIf { it.isNotBlank() }
            ?: runCatching {
                URI(element.ownerDocument()?.baseUri().orEmpty()).resolve(element.attr(attr)).toString()
            }.getOrDefault(element.attr(attr))

    private fun normalizeMediaUrl(url: String): String = runCatching {
        val uri = URI(url)
        URI(
            uri.scheme?.lowercase(Locale.ROOT),
            uri.userInfo,
            uri.host?.lowercase(Locale.ROOT),
            uri.port,
            uri.path,
            uri.query,
            null
        ).toString()
    }.getOrDefault(url.substringBefore('#'))

    private fun normalizeInput(value: String): String =
        value.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class FourKGirlAdapter(http: HttpClient) : DirectGalleryAdapter(
    http = http,
    source = SourceId.FOUR_K_GIRL,
    host = "4kgirl.com",
    rootUrl = "https://www.4kgirl.com/",
    entityName = "4KGirl",
    statusLabel = "Direct multi-page cosplay sets"
) {
    private val reserved = setOf(
        "page", "category", "tag", "author", "search", "sitemaps",
        "wp-admin", "wp-content", "feed", "comments"
    )

    override fun isPostPath(path: String): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return parts.size == 1 && parts[0].lowercase(Locale.ROOT) !in reserved
    }
}

class EveriaAdapter(http: HttpClient) : DirectGalleryAdapter(
    http = http,
    source = SourceId.EVERIA,
    host = "everia.club",
    rootUrl = "https://everia.club/cosplay-gallery/",
    entityName = "Everia",
    statusLabel = "Direct cosplay galleries • paginated originals"
) {
    private val postPath = Regex("^/\\d{4}/\\d{2}/\\d{2}/[^/]+/?$")

    override fun isPostPath(path: String): Boolean = postPath.matches(path)
}
