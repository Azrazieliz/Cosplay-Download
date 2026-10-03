package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

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
            throw HttpStatusException(response.statusCode(), "HTTP ${response.statusCode()} for $url")
        }
        return response.parse().also { it.setBaseUri(response.url().toString()) }
    }

    fun open(url: String, referer: String? = null): OpenResponse {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val conn = URL(url).openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                NetworkRequestRegistry.unregister(conn)
                throw HttpStatusException(code, "HTTP $code for $url")
            }
            return OpenResponse(
                connection = conn,
                input = BufferedInputStream(conn.inputStream),
                contentType = conn.contentType?.substringBefore(';'),
                contentLength = conn.contentLengthLong
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
        val contentLength: Long
    ) : AutoCloseable {
        override fun close() {
            runCatching { input.close() }
            NetworkRequestRegistry.unregister(connection)
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36 GalleryFlow/0.1"
    }
}
