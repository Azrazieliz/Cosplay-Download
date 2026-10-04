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
    data class TextResponse(
        val body: String,
        val finalUrl: String,
        val contentType: String?
    )

    fun textResponse(
        url: String,
        referer: String? = null,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    ): TextResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val connection = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(30_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Accept", accept)

        if (!referer.isNullOrBlank()) connection.referrer(referer)
        webCookies(url)?.let { connection.header("Cookie", it) }

        val response = connection.execute()
        if (response.statusCode() !in 200..299) {
            throw HttpStatusException(response.statusCode(), "HTTP " + response.statusCode() + " for " + url)
        }
        val finalUrl = response.url().toString()
        persistResponseCookies(finalUrl, response.headers("Set-Cookie"))
        return TextResponse(
            body = response.body(),
            finalUrl = finalUrl,
            contentType = response.contentType()
        )
    }

    fun document(url: String, referer: String? = null): Document {
        val response = textResponse(url, referer)
        return Jsoup.parse(response.body, response.finalUrl)
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
        if (depth > 6) {
            throw InteractiveProviderRequiredException(
                provider ?: "Archive provider",
                url,
                "Too many provider redirects."
            )
        }

        val normalizedProvider = provider?.lowercase(Locale.ROOT).orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri?.path?.lowercase(Locale.ROOT).orEmpty()

        if (isDirectFilePath(path)) return url

        if (normalizedProvider == "mediafire" || "mediafire.com" in host) {
            val page = textResponse(url, referer)
            val doc = Jsoup.parse(page.body, page.finalUrl)
            val direct = doc.selectFirst(
                "a#downloadButton[href],a.input[href],a[aria-label*=download][href],a[download][href]"
            )?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
                ?: doc.select("a[href]").mapNotNull { a ->
                    a.absUrl("href").takeIf { isUsefulDirectUrl(it) }
                }.firstOrNull()
                ?: extractDirectFromText(page.body)

            if (!direct.isNullOrBlank()) return direct
            throw InteractiveProviderRequiredException(
                "MediaFire",
                page.finalUrl,
                "MediaFire did not expose a direct file URL."
            )
        }

        if (host == "m.4khd.com" || host.endsWith(".4khd.com")) {
            val page = textResponse(url, referer)
            val doc = Jsoup.parse(page.body, page.finalUrl)
            val bridgeTarget =
                doc.selectFirst("#custom_button[href],a[href*=terabox],a[href*=1024tera],a[href*=teraboxapp]")
                    ?.absUrl("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: extractTeraBoxUrl(page.body)
                    ?: extractRedirect(doc)

            if (!bridgeTarget.isNullOrBlank() && bridgeTarget != url) {
                return resolveDownloadUrlInternal(
                    bridgeTarget,
                    page.finalUrl,
                    if (isTeraBoxHost(hostOf(bridgeTarget))) "TeraBox" else provider,
                    depth + 1
                )
            }

            val finalHost = hostOf(page.finalUrl)
            if (isTeraBoxHost(finalHost)) {
                return TeraBoxResolver(this).resolvePublicShare(page.finalUrl, referer)
            }

            throw InteractiveProviderRequiredException(
                "4KHD/TeraBox",
                page.finalUrl,
                "4KHD bridge did not expose its TeraBox share URL."
            )
        }

        if (normalizedProvider == "terabox" || isTeraBoxHost(host)) {
            return TeraBoxResolver(this).resolvePublicShare(url, referer)
        }

        if (normalizedProvider == "sorafolder" || "sorafolder.com" in host) {
            val page = textResponse(url, referer)
            val doc = Jsoup.parse(page.body, page.finalUrl)
            val direct = doc.selectFirst(
                "a[download][href],a#download[href],a.download[href],a[href*=download]"
            )?.absUrl("href")?.takeIf { isUsefulDirectUrl(it) }
                ?: doc.select("[data-url],[data-download],[data-href]").mapNotNull { element ->
                    listOf("data-url", "data-download", "data-href")
                        .firstNotNullOfOrNull { attr ->
                            element.attr(attr).takeIf { it.startsWith("http") && isUsefulDirectUrl(it) }
                        }
                }.firstOrNull()
                ?: extractDirectFromText(page.body)

            if (!direct.isNullOrBlank() && hostOf(direct) != host) return direct
            if (!direct.isNullOrBlank() && isDirectFilePath(runCatching { URI(direct).path.lowercase(Locale.ROOT) }.getOrDefault(""))) {
                return direct
            }
            throw InteractiveProviderRequiredException(
                "SoraFolder",
                page.finalUrl,
                "SoraFolder requires its timed browser download for this file."
            )
        }

        if (normalizedProvider == "gofile" || "gofile.io" in host) {
            throw InteractiveProviderRequiredException(
                "Gofile",
                url,
                "Gofile link requires provider resolution."
            )
        }

        if (normalizedProvider == "telegram" || host == "t.me" || host.endsWith(".t.me")) {
            throw InteractiveProviderRequiredException(
                "Telegram",
                url,
                "Telegram is a mirror/navigation provider, not a direct archive URL."
            )
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
                isTeraBoxHost(h) ||
                    isDirectFilePath(runCatching { URI(candidate).path.lowercase(Locale.ROOT) }.getOrDefault(""))
            }?.let { return it }

        val html = unescapeHtmlJs(doc.html())
        val scripted = listOf(
            Regex("(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)location\\.replace\\(\\s*['\"](https?://[^'\"]+)"),
            Regex("(?i)[\"'](?:url|target|redirect|location)[\"']\\s*:\\s*[\"'](https?://[^\"']+)")
        )
        for (regex in scripted) {
            regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun extractTeraBoxUrl(text: String): String? {
        val html = unescapeHtmlJs(text)
        return URL_REGEX.findAll(html)
            .map { it.value.trimEnd(')', ']', '}', ',', ';') }
            .firstOrNull { isTeraBoxHost(hostOf(it)) }
    }

    private fun extractDirectFromText(text: String): String? {
        val html = unescapeHtmlJs(text)
        val keyed = listOf(
            Regex("(?i)[\"'](?:dlink|downloadUrl|download_url|directUrl|direct_url|fileUrl|file_url)[\"']\\s*[:=]\\s*[\"'](https?://[^\"']+)"),
            Regex("(?i)(https?://[^\"'<>\\s]+(?:\\.zip|\\.rar|\\.7z|\\.mp4|\\.webm|\\.mov)(?:\\?[^\"'<>\\s]*)?)")
        )
        for (regex in keyed) {
            val match = regex.find(html) ?: continue
            val candidate = if (match.groupValues.size > 1) match.groupValues[1] else match.value
            if (isUsefulDirectUrl(candidate)) return candidate
        }
        return null
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
        listOf(
            ".zip", ".rar", ".7z",
            ".mp4", ".webm", ".mov", ".m3u8",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"
        ).any { path.endsWith(it) }

    private fun isTeraBoxHost(host: String): Boolean =
        host.contains("terabox") ||
            host.endsWith("1024tera.com") ||
            host.endsWith("4funbox.com") ||
            host.endsWith("nephobox.com") ||
            host.endsWith("mirrobox.com") ||
            host.endsWith("momerybox.com")

    private fun hostOf(value: String): String =
        runCatching { URI(value).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    fun open(url: String, referer: String? = null, cookie: String? = null): OpenResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val conn = URL(url).openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Pragma", "no-cache")
            if (!referer.isNullOrBlank()) {
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Sec-Fetch-Mode", "no-cors")
                conn.setRequestProperty("Sec-Fetch-Site", if (sameSite(url, referer)) "same-site" else "cross-site")
            }
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

    private fun sameSite(url: String, referer: String): Boolean {
        val a = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val b = runCatching { URI(referer).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        if (a.isBlank() || b.isBlank()) return false
        fun base(host: String): String {
            val parts = host.split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        }
        return base(a) == base(b)
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
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
