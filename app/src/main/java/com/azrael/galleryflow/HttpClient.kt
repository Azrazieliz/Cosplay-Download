package com.azrael.galleryflow

import android.webkit.CookieManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.Locale

object NetworkRequestRegistry {
    private val active = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun register(conn: HttpURLConnection) { active += conn }
    fun unregister(conn: HttpURLConnection) { active -= conn }

    fun cancelAll() {
        val copy = synchronized(active) { active.toList() }
        copy.forEach { runCatching { it.disconnect() } }
    }
}

class HttpClient {
    fun document(url: String, referer: String? = null): Document {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val connection = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(30_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        if (!referer.isNullOrBlank()) connection.referrer(referer)
        webCookies(url)?.let { connection.header("Cookie", it) }

        val response = connection.execute()
        persistResponseCookies(response.url().toString(), response.headers("Set-Cookie"))

        if (response.statusCode() !in 200..299) {
            throw HttpStatusException(
                response.statusCode(),
                "HTTP " + response.statusCode() + " for " + url
            )
        }

        return response.parse().also { it.setBaseUri(response.url().toString()) }
    }

    fun resolveDownloadUrl(
        url: String,
        referer: String? = null,
        provider: String? = null,
        nameHint: String? = null
    ): ResolvedDownload {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val normalizedProvider = provider?.lowercase(Locale.ROOT).orEmpty()
        val host = hostOf(url)
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrDefault("")

        if (isDirectFilePath(path)) {
            return ResolvedDownload(url, referer, webCookies(url))
        }

        if (normalizedProvider == "mediafire" || "mediafire.com" in host) {
            return resolveMediaFire(url, referer)
        }

        if (host == "m.4khd.com" || host.endsWith(".4khd.com")) {
            val target = resolve4KhdShortLink(url, referer)
            if (target == url) {
                throw InteractiveProviderRequiredException(
                    "4KHD",
                    url,
                    "4KHD short link did not expose its archive target."
                )
            }
            val inferred = when {
                "terabox" in hostOf(target) || "1024terabox" in hostOf(target) || "4funbox" in hostOf(target) -> "TeraBox"
                "mediafire.com" in hostOf(target) -> "MediaFire"
                "gofile.io" in hostOf(target) -> "Gofile"
                else -> provider
            }
            return resolveDownloadUrl(target, url, inferred, nameHint)
        }

        if (
            normalizedProvider == "terabox" ||
            "terabox" in host ||
            "1024terabox" in host ||
            "4funbox" in host
        ) {
            val resolved = TeraBoxResolver().resolve(url, nameHint)
            return ResolvedDownload(
                resolved.url,
                resolved.referer,
                resolved.cookie,
                resolved.filename
            )
        }

        if (normalizedProvider == "gofile" || "gofile.io" in host) {
            val resolved = GofileResolver().resolve(url, nameHint)
            return ResolvedDownload(
                resolved.url,
                resolved.referer,
                resolved.cookie,
                resolved.filename
            )
        }

        if (normalizedProvider == "sorafolder" || "sorafolder.com" in host) {
            return resolveSoraFolder(url, referer)
        }

        if (normalizedProvider == "telegram" || host == "t.me" || host.endsWith(".t.me")) {
            throw InteractiveProviderRequiredException(
                "Telegram",
                url,
                "Telegram mirror requires its own session; another archive mirror should be used."
            )
        }

        return ResolvedDownload(url, referer, webCookies(url))
    }

    private fun resolveMediaFire(url: String, referer: String?): ResolvedDownload {
        val doc = document(url, referer)
        val direct = doc.selectFirst(
            "a#downloadButton[href],a.input[href],a[aria-label*=download][href],a.download_link[href]"
        )?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
            ?: doc.select("a[href]").mapNotNull { a ->
                val candidate = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                candidate.takeIf { isUsefulDirectUrl(it) }
            }.firstOrNull()

        if (!direct.isNullOrBlank()) {
            return ResolvedDownload(
                direct,
                doc.baseUri().ifBlank { url },
                webCookies(direct),
                filenameFromUrl(direct)
            )
        }

        throw InteractiveProviderRequiredException(
            "MediaFire",
            doc.baseUri().ifBlank { url },
            "MediaFire page did not expose a direct file."
        )
    }

    private fun resolveSoraFolder(url: String, referer: String?): ResolvedDownload {
        val doc = document(url, referer)
        val candidates = linkedSetOf<String>()

        doc.select("a[href],button[data-url],button[data-href],[data-download]").forEach { element ->
            listOf("href", "data-url", "data-href", "data-download").forEach { attr ->
                val raw = element.attr(attr).trim()
                if (raw.isNotBlank()) {
                    val absolute = runCatching { URI(doc.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
                    if (absolute.startsWith("http")) candidates += absolute
                }
            }
        }

        val html = unescapeHtmlJs(doc.html())
        URL_REGEX.findAll(html).forEach { candidates += it.value }

        val direct = candidates.firstOrNull { candidate ->
            val candidateHost = hostOf(candidate)
            candidate != url &&
                candidateHost != "sorafolder.com" &&
                isUsefulDirectUrl(candidate)
        }

        if (!direct.isNullOrBlank()) {
            return ResolvedDownload(
                direct,
                doc.baseUri().ifBlank { url },
                webCookies(direct),
                filenameFromUrl(direct)
            )
        }

        throw InteractiveProviderRequiredException(
            "SoraFolder",
            doc.baseUri().ifBlank { url },
            "SoraFolder did not expose a direct downloadable file."
        )
    }

    private fun resolve4KhdShortLink(url: String, referer: String?): String {
        fun inspect(target: String, ref: String?): String? {
            val doc = runCatching { document(target, ref) }.getOrNull() ?: return null
            val final = doc.baseUri().ifBlank { target }
            if (isProviderTarget(final)) return final

            doc.selectFirst("#custom_button[href],a#custom_button[href],a[href*=terabox]")?.let { button ->
                val href = button.absUrl("href").ifBlank {
                    runCatching { URI(final).resolve(button.attr("href")).toString() }
                        .getOrDefault(button.attr("href"))
                }
                if (href.startsWith("http")) return href
            }

            return extractRedirect(doc)?.takeIf { it.startsWith("http") }
        }

        inspect(url, referer)?.let { return it }

        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val code = uri.path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return url

        val attempts = listOf(
            "https://m.4khd.com/link/" + code,
            "https://m.4khd.com/link/" + code + "/",
            "https://m.4khd.com/vip/index.php?code=" + code
        )
        for (candidate in attempts) {
            inspect(candidate, url)?.let { return it }
        }

        return url
    }

    private fun isProviderTarget(value: String): Boolean {
        val host = hostOf(value)
        return "terabox" in host ||
            "1024terabox" in host ||
            "4funbox" in host ||
            "mediafire.com" in host ||
            "gofile.io" in host ||
            "sorafolder.com" in host
    }

    private fun extractRedirect(doc: Document): String? {
        doc.selectFirst("meta[http-equiv=refresh],meta[http-equiv=Refresh]")
            ?.attr("content")
            ?.let { content ->
                Regex("(?i)url\\s*=\\s*['\"]?([^'\";]+)")
                    .find(content)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.let { raw ->
                        return runCatching { URI(doc.baseUri()).resolve(raw).toString() }
                            .getOrDefault(raw)
                    }
            }

        doc.select("a[href]").mapNotNull {
            it.absUrl("href").takeIf(String::isNotBlank)
        }.firstOrNull(::isProviderTarget)?.let { return it }

        val html = unescapeHtmlJs(doc.html())
        val scripted = listOf(
            Regex("(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)location\\.replace\\(\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)[\"'](?:url|target|redirect|location)[\"']\\s*:\\s*[\"'](https?://[^\"']+)")
        )
        scripted.forEach { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)?.let { return it }
        }

        return URL_REGEX.findAll(html)
            .map { it.value }
            .firstOrNull(::isProviderTarget)
    }

    private fun unescapeHtmlJs(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&quot;", "\"")

    private fun isUsefulDirectUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        val host = hostOf(value)
        if (host.endsWith("mediafire.com") && "/folder/" in value) return false
        val path = runCatching { URI(value).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return isDirectFilePath(path) ||
            "download" in value.lowercase(Locale.ROOT) ||
            host.startsWith("download.") ||
            host.startsWith("d.")
    }

    private fun isDirectFilePath(path: String): Boolean =
        listOf(
            ".zip", ".rar", ".7z", ".mp4", ".webm", ".mov", ".mkv",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"
        ).any { path.endsWith(it) }

    private fun filenameFromUrl(value: String): String? =
        runCatching { URI(value).path.substringAfterLast('/').takeIf { it.contains('.') } }.getOrNull()

    private fun hostOf(value: String): String =
        runCatching { URI(value).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    fun open(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): OpenResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()

        val conn = URL(url).openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)

        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)

            val sessionCookie = cookie?.takeIf { it.isNotBlank() } ?: webCookies(url)
            if (!sessionCookie.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", sessionCookie)
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val error = "HTTP " + code + " for " + url
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw HttpStatusException(code, error)
            }

            conn.headerFields["Set-Cookie"]?.let {
                persistResponseCookies(conn.url.toString(), it)
            }

            return OpenResponse(
                connection = conn,
                input = BufferedInputStream(conn.inputStream),
                contentType = conn.contentType?.substringBefore(';'),
                contentLength = conn.contentLengthLong,
                contentDisposition = conn.getHeaderField("Content-Disposition"),
                finalUrl = conn.url.toString()
            )
        } catch (t: Throwable) {
            NetworkRequestRegistry.unregister(conn)
            runCatching { conn.disconnect() }
            throw t
        }
    }

    private fun webCookies(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun persistResponseCookies(url: String, headers: List<String>) {
        if (headers.isEmpty()) return
        runCatching {
            val manager = CookieManager.getInstance()
            headers.forEach { header -> manager.setCookie(url, header) }
            manager.flush()
        }
    }

    data class OpenResponse(
        val connection: HttpURLConnection,
        val input: BufferedInputStream,
        val contentType: String?,
        val contentLength: Long,
        val contentDisposition: String?,
        val finalUrl: String
    ) : AutoCloseable {
        override fun close() {
            runCatching { input.close() }
            NetworkRequestRegistry.unregister(connection)
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 GalleryFlow/0.3"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
