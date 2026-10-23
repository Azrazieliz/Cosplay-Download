package com.asterion.videoflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

data class VideoHit(val title: String, val url: String)

object WebDiscovery {
    private val videoPages = listOf(
        Regex("view_video\\.php\\?[^#]*viewkey=", RegexOption.IGNORE_CASE),
        Regex("/videos?/[^/?#]+", RegexOption.IGNORE_CASE),
        Regex("/watch/[^/?#]+", RegexOption.IGNORE_CASE),
        Regex("/watch\\?v=[^&#]+", RegexOption.IGNORE_CASE),
        Regex("/shorts/[^/?#]+", RegexOption.IGNORE_CASE),
        Regex("youtu\\.be/[^/?#]+", RegexOption.IGNORE_CASE),
        Regex("/(?:video|post|clip|watch|v)/[^/?#]+", RegexOption.IGNORE_CASE)
    )
    private val mediaTypes = Regex("\\.(?:mp4|webm|mov|m3u8|mpd)(?:[?#]|$)", RegexOption.IGNORE_CASE)
    fun isUrl(s: String): Boolean = try {
        val uri=URI(s.trim())
        (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() &&
            uri.host != "localhost" && !uri.host.startsWith("127.") && !uri.host.startsWith("192.168.") &&
            !uri.host.startsWith("10.") && !uri.host.startsWith("172.16.")
    } catch (_: Exception) { false }
    /** Preserve the original HTTPS origin and hostname. Some sites reject stripped www
     *  or redirected hosts with 502/403, even when the resulting path is the same. */
    fun normalize(address:String):String?=try {
        val uri=URI(address.trim())
        if(!isUrl(address))null
        else {
            val cleanQuery=uri.rawQuery?.split('&')?.filterNot {
                it.startsWith("utm_",true)||it.startsWith("fbclid=",true)||
                    it.startsWith("gclid=",true)
            }?.joinToString("&")?.takeIf{it.isNotBlank()}
            buildString {
                append(uri.scheme.lowercase()).append("://").append(uri.rawAuthority)
                append(uri.rawPath.orEmpty().ifBlank{"/"})
                if(!cleanQuery.isNullOrBlank())append("?").append(cleanQuery)
            }
        }
    } catch(_:Exception){null}
    fun isVideoPage(url: String): Boolean {
        return mediaTypes.containsMatchIn(url) || videoPages.any { it.containsMatchIn(url) } ||
               url.contains("viewkey=",true)
    }
    private fun open(url: String): Document = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        .referrer("https://www.google.com/")
        .timeout(16000).followRedirects(true).maxBodySize(3*1024*1024).get()
    fun findOnPage(address: String, limit: Int = 12): List<VideoHit> {
        if(!isUrl(address)) return emptyList()
        val root=open(address)
        val host=URI(address).host.lowercase().removePrefix("www.")
        val out=linkedMapOf<String,VideoHit>()
        fun collect(raw: String, title: String) {
            val resolved=runCatching { URI(root.location()).resolve(raw).toString() }.getOrNull() ?: return
            val url=normalize(resolved) ?: return
            val nextHost=URI(url).host.removePrefix("www.")
            if(nextHost!=host && !nextHost.endsWith(".$host")) return
            if(!isVideoPage(url)) return
            out.putIfAbsent(url,VideoHit(title.trim().ifBlank { "Video" }.take(180),url))
        }
        if(isVideoPage(root.location())) collect(root.location(), root.title())
        root.select("a[href]").forEach {
            collect(it.attr("abs:href"),it.attr("title").ifBlank { it.text() })
        }
        root.select("link[rel=canonical],meta[property=og:url]").forEach {
            collect(it.attr("href").ifBlank { it.attr("content") },root.title())
        }
        return out.values.take(limit.coerceIn(1,50))
    }
    fun directMedia(address: String): String? {
        if(!isUrl(address)) throw IllegalArgumentException("Unsupported address")
        if(mediaTypes.containsMatchIn(address)) return address
        val doc=open(address)
        val fields = doc.select("video[src],video source[src],source[src],meta[property=og:video],meta[property=og:video:url],meta[itemprop=contentUrl],meta[name=twitter:player:stream]")
        for(node in fields) {
            val value=node.attr("abs:src").ifBlank { node.attr("content") }
            val resolved=runCatching { URI(address).resolve(value).toString() }.getOrNull() ?: continue
            if(isUrl(resolved) && mediaTypes.containsMatchIn(resolved)) return resolved
        }
        // Read only explicit media URLs from script metadata; do not evaluate page JavaScript.
        val rx=Regex("""https?[^"'\\\\\s]{5,450}?\\.(?:mp4|m3u8|webm)(?:\\?[^"'\\\\\s<]{0,150})?""",RegexOption.IGNORE_CASE)
        val content=doc.select("script[type=application/ld+json],script[type=application/json]").joinToString("\\n") { it.data() }
        val first=rx.find(content)?.value?.replace("\\u0026","&")?.replace("\\/","/")
        return first?.takeIf { isUrl(it) }
    }
    fun search(query: String, limit: Int=15): List<VideoHit> =
        VideoSearch.search(query,limit).filter { it.type.contains("video page") }
            .map { VideoHit(it.title,it.url) }
    fun discover(watch: Watch): List<VideoHit> =
        if(watch.address.startsWith("search:")) search(watch.address.removePrefix("search:"),15)
        else findOnPage(watch.address, 15)
}

