package com.azrael.galleryflow

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
        if (!referer.isNullOrBlank()) connection.referrer(referer)
        val response = connection.execute()
        if (response.statusCode() !in 200..299) {
            throw HttpStatusException(response.statusCode(), "HTTP " + response.statusCode() + " for " + url)
        }
        return response.parse().also { it.setBaseUri(response.url().toString()) }
    }

    fun resolveDownloadUrl(url: String, referer: String? = null, provider: String? = null): String {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val normalizedProvider = provider?.lowercase(Locale.ROOT).orEmpty()
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrDefault("")
        if (path.endsWith(".zip") || path.endsWith(".rar") || path.endsWith(".7z") ||
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mov")
        ) return url

        if (normalizedProvider == "mediafire" || "mediafire.com" in host) {
            val doc = document(url, referer)
            val direct = doc.selectFirst("a#downloadButton[href],a.input[href],a[aria-label*=download][href]")
                ?.absUrl("href")?.takeIf { it.isNotBlank() }
                ?: doc.select("a[href]").mapNotNull { a ->
                    val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val h = runCatching { URI(href).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                    if (h != host && !href.contains("/folder/", true)) href else null
                }.firstOrNull()
            if (!direct.isNullOrBlank()) return direct
            throw InteractiveProviderRequiredException("MediaFire", doc.baseUri())
        }

        if (host == "m.4khd.com" || host.endsWith(".4khd.com") || normalizedProvider == "terabox" || "terabox" in host) {
            val doc = document(url, referer)
            val finalUrl = doc.baseUri().takeIf { it.isNotBlank() } ?: url
            val finalHost = runCatching { URI(finalUrl).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
            val directFromHtml = URL_REGEX.findAll(doc.html()).map { it.value.replace("&amp;", "&") }
                .firstOrNull { candidate ->
                    val lower = candidate.lowercase(Locale.ROOT)
                    (lower.endsWith(".zip") || lower.contains(".zip?")) && !lower.contains("javascript:")
                }
            if (!directFromHtml.isNullOrBlank()) return directFromHtml
            if ("terabox" in finalHost || normalizedProvider == "terabox" || host == "m.4khd.com") {
                throw InteractiveProviderRequiredException("TeraBox", finalUrl)
            }
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
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw HttpStatusException(code, "HTTP " + code + " for " + url)
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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36 GalleryFlow/0.2"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }
}
