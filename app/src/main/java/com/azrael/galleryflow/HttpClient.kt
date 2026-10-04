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
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (!referer.isNullOrBlank()) connection.referrer(referer)
        webCookies(url)?.let { connection.header("Cookie", it) }
        val response = connection.execute()
        if (response.statusCode() !in 200..299) {
            throw HttpStatusException(response.statusCode(), "HTTP " + response.statusCode() + " for " + url)
        }
        persistResponseCookies(response.url().toString(), response.headers("Set-Cookie"))
        return response.parse().also { it.setBaseUri(response.url().toString()) }
    }

    fun resolveDownloadUrl(url: String, referer: String? = null, provider: String? = null): String =
        resolveDownloadUrlInternal(url, referer, provider, 0)

    private fun resolveDownloadUrlInternal(
        url: String,
        referer: String?,
        provider: String?,
        depth: Int
    ): String {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        if (depth > 4) throw InteractiveProviderRequiredException(provider ?: "Archive provider", url)

        val normalizedProvider = provider?.lowercase(Locale.ROOT).orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri?.path?.lowercase(Locale.ROOT).orEmpty()

        if (isDirectFilePath(path)) return url

        if (normalizedProvider == "mediafire" || "mediafire.com" in host) {
            val doc = document(url, referer)
            val direct = doc.selectFirst("a#downloadButton[href],a.input[href],a[aria-label*=download][href]")
                ?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
                ?: doc.select("a[href]").mapNotNull { a ->
                    a.absUrl("href").takeIf { isUsefulDirectUrl(it) }
                }.firstOrNull()
            if (!direct.isNullOrBlank()) return direct
            throw InteractiveProviderRequiredException("MediaFire", doc.baseUri())
        }

        if (host == "m.4khd.com" || host.endsWith(".4khd.com")) {
            val doc = document(url, referer)
            val redirect = extractRedirect(doc)
            if (!redirect.isNullOrBlank() && redirect != url) {
                val redirectedProvider = if ("terabox" in hostOf(redirect)) "TeraBox" else provider
                return resolveDownloadUrlInternal(redirect, url, redirectedProvider, depth + 1)
            }
            throw InteractiveProviderRequiredException("4KHD/TeraBox", doc.baseUri().ifBlank { url })
        }

        if (normalizedProvider == "terabox" || "terabox" in host) {
            val doc = document(url, referer)
            val pageUrl = doc.baseUri().ifBlank { url }
            val direct = extractTeraBoxDirect(doc)
            if (!direct.isNullOrBlank()) return direct
            throw InteractiveProviderRequiredException("TeraBox", pageUrl)
        }

        if (normalizedProvider in setOf("sorafolder", "gofile", "telegram") ||
            "sorafolder.com" in host || "gofile.io" in host || host == "t.me"
        ) {
            val name = when {
                normalizedProvider.isNotBlank() -> provider ?: "Provider"
                "sorafolder.com" in host -> "SoraFolder"
                "gofile.io" in host -> "Gofile"
                else -> "Telegram"
            }
            throw InteractiveProviderRequiredException(name, url)
        }

        return url
    }

    private fun extractRedirect(doc: Document): String? {
        doc.selectFirst("meta[http-equiv=refresh],meta[http-equiv=Refresh]")?.attr("content")?.let { content ->
            Regex("(?i)url\\s*=\\s*['\"]?([^'\";]+)").find(content)?.groupValues?.get(1)?.trim()?.let { raw ->
                return runCatching { URI(doc.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
            }
        }

        doc.select("a[href]").mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .firstOrNull { candidate ->
                val h = hostOf(candidate)
                ("terabox" in h || isDirectFilePath(runCatching { URI(candidate).path.lowercase(Locale.ROOT) }.getOrDefault("")))
            }?.let { return it }

        val html = unescapeHtmlJs(doc.html())
        val scripted = listOf(
            Regex("(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)location\\.replace\\(\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)[\"'](?:url|target|redirect|location)[\"']\\s*:\\s*[\"'](https?://[^\"']+)")
        )
        scripted.forEach { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return URL_REGEX.findAll(html).map { it.value }
            .firstOrNull { candidate -> "terabox" in hostOf(candidate) }
    }

    private fun extractTeraBoxDirect(doc: Document): String? {
        doc.select(
            "a[download][href],a[href*=download],a[href$=.zip],a[href*=.zip?],a[href$=.mp4],a[href*=.mp4?]"
        ).mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .firstOrNull { isUsefulDirectUrl(it) }
            ?.let { return it }

        val html = unescapeHtmlJs(doc.html())
        val keyed = listOf(
            Regex("(?i)[\"'](?:dlink|downloadUrl|download_url|directUrl|direct_url)[\"']\\s*[:=]\\s*[\"'](https?://[^\"']+)"),
            Regex("(?i)(https?://[^\"'<>\\s]+(?:\\.zip|\\.mp4|\\.webm)(?:\\?[^\"'<>\\s]*)?)")
        )
        keyed.forEach { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { isUsefulDirectUrl(it) }?.let { return it }
        }
        return URL_REGEX.findAll(html).map { it.value }
            .firstOrNull { isUsefulDirectUrl(it) && ("download" in it.lowercase(Locale.ROOT) || isDirectFilePath(runCatching { URI(it).path.lowercase(Locale.ROOT) }.getOrDefault(""))) }
    }

    private fun unescapeHtmlJs(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&quot;", "\"")

    private fun isUsefulDirectUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        val h = hostOf(value)
        if (h.endsWith("mediafire.com") && "/folder/" in value) return false
        val p = runCatching { URI(value).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return isDirectFilePath(p) ||
            "download" in value.lowercase(Locale.ROOT) ||
            h.startsWith("download.") ||
            h.startsWith("d.")
    }

    private fun isDirectFilePath(path: String): Boolean =
        listOf(".zip", ".rar", ".7z", ".mp4", ".webm", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
            .any { path.endsWith(it) }

    private fun hostOf(value: String): String =
        runCatching { URI(value).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    fun open(url: String, referer: String? = null, cookie: String? = null): OpenResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val conn = URL(url).openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            val sessionCookie = cookie?.takeIf { it.isNotBlank() } ?: webCookies(url)
            if (!sessionCookie.isNullOrBlank()) conn.setRequestProperty("Cookie", sessionCookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw HttpStatusException(code, "HTTP " + code + " for " + url)
            }
            conn.headerFields["Set-Cookie"]?.let { persistResponseCookies(conn.url.toString(), it) }
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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36 GalleryFlow/0.2.1"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
