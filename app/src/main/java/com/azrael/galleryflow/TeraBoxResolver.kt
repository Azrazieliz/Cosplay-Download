package com.azrael.galleryflow

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class TeraBoxResolver(private val http: HttpClient) {
    fun resolvePublicShare(url: String, referer: String? = null): String {
        val landing = http.textResponse(url, referer)
        val html = unescape(landing.body)

        extractDirect(html)?.let { return it }

        val jsToken = extractJsToken(html)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share token could not be resolved automatically."
            )
        val shortUrl = extractShortUrl(landing.finalUrl, html)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share id could not be resolved automatically."
            )

        val files = listFiles(
            sharePage = landing.finalUrl,
            jsToken = jsToken,
            shortUrl = shortUrl,
            directory = null,
            depth = 0
        )
        if (files.isEmpty()) {
            throw InteractiveProviderRequiredException(
                "TeraBox",
                landing.finalUrl,
                "TeraBox share exposed no downloadable files."
            )
        }

        val preferred = files.firstOrNull { isArchive(it.name) }
            ?: files.firstOrNull { isMedia(it.name) }
            ?: files.maxByOrNull { it.size }

        val direct = preferred?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (!direct.isNullOrBlank()) return direct

        throw InteractiveProviderRequiredException(
            "TeraBox",
            landing.finalUrl,
            "TeraBox returned file metadata but no direct download URL."
        )
    }

    private fun listFiles(
        sharePage: String,
        jsToken: String,
        shortUrl: String,
        directory: String?,
        depth: Int
    ): List<RemoteFile> {
        if (depth > 4) return emptyList()
        val query = buildString {
            append("app_id=250528")
            append("&web=1")
            append("&channel=dubox")
            append("&clienttype=0")
            append("&page=1")
            append("&num=100")
            append("&by=name")
            append("&order=asc")
            append("&jsToken=").append(enc(jsToken))
            append("&shorturl=").append(enc(shortUrl))
            if (directory.isNullOrBlank()) {
                append("&root=1")
            } else {
                append("&dir=").append(enc(directory))
            }
        }

        val hosts = linkedSetOf(
            apiBase(sharePage),
            "https://www.terabox.com",
            "https://www.terabox.app",
            "https://www.1024tera.com"
        )

        var lastError: String? = null
        for (base in hosts) {
            val response = try {
                http.textResponse("$base/share/list?$query", sharePage, "application/json,text/plain,*/*")
            } catch (t: Throwable) {
                lastError = t.message
                continue
            }

            val json = try {
                JSONObject(response.body)
            } catch (_: Throwable) {
                lastError = "Invalid TeraBox share-list response"
                continue
            }
            val errno = json.optInt("errno", 0)
            if (errno != 0) {
                lastError = json.optString("errmsg").takeIf { it.isNotBlank() }
                    ?: "TeraBox errno $errno"
                continue
            }

            val list = json.optJSONArray("list") ?: JSONArray()
            val files = mutableListOf<RemoteFile>()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val isDir = item.optInt("isdir", 0) == 1 || item.optString("isdir") == "1"
                if (isDir) {
                    val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
                    files += listFiles(response.finalUrl, jsToken, shortUrl, path, depth + 1)
                    continue
                }
                val name = item.optString("server_filename").ifBlank { "file-$i" }
                val dlink = item.optString("dlink")
                val size = when (val value = item.opt("size")) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (dlink.isNotBlank()) files += RemoteFile(name, dlink, size)
            }
            if (files.isNotEmpty()) return files
        }

        if (!lastError.isNullOrBlank()) {
            throw InteractiveProviderRequiredException(
                "TeraBox",
                sharePage,
                "TeraBox public-share API could not be resolved: $lastError"
            )
        }
        return emptyList()
    }

    private fun extractJsToken(html: String): String? {
        val patterns = listOf(
            Regex("""fn%28%22([^%"]+)%22%29""", RegexOption.IGNORE_CASE),
            Regex("""window\.jsToken\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["']jsToken["']\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .map { decodePercent(it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractShortUrl(finalUrl: String, html: String): String? {
        val uri = runCatching { URI(finalUrl) }.getOrNull()
        queryValue(uri?.rawQuery, "surl")?.let { return it }

        uri?.path?.let { path ->
            Regex("""/s/([^/?#]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        }

        val patterns = listOf(
            Regex("""[?&]surl=([^&"'<>]+)""", RegexOption.IGNORE_CASE),
            Regex("""["']shorturl["']\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""/s/([A-Za-z0-9_-]+)""")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .map { decodePercent(it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractDirect(html: String): String? {
        val patterns = listOf(
            Regex("""["'](?:dlink|downloadUrl|download_url|directUrl|direct_url)["']\s*[:=]\s*["'](https?://[^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'<>\s]+(?:\.zip|\.rar|\.7z|\.mp4|\.webm)(?:\?[^"'<>\s]*)?""", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { regex ->
                val match = regex.find(html) ?: return@mapNotNull null
                if (match.groupValues.size > 1) match.groupValues[1] else match.value
            }
            .map(::unescape)
            .firstOrNull { it.startsWith("http") }
    }

    private fun apiBase(url: String): String {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return when {
            host.endsWith("terabox.app") -> "https://www.terabox.app"
            host.endsWith("1024tera.com") -> "https://www.1024tera.com"
            else -> "https://www.terabox.com"
        }
    }

    private fun queryValue(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').firstNotNullOfOrNull { pair ->
            val p = pair.split('=', limit = 2)
            if (p.firstOrNull() == name) decodePercent(p.getOrElse(1) { "" }) else null
        }
    }

    private fun isArchive(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".mp4", ".webm", ".mov")
            .any(lower::endsWith)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodePercent(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&quot;", """)

    private data class RemoteFile(
        val name: String,
        val url: String,
        val size: Long
    )
}
