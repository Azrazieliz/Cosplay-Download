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
    fun normalize(address: String): String? = try {
        val u=URI(address.trim())
        if(!isUrl(address)) throw IllegalArgumentException("Unsupported address")
        val host=u.host.lowercase().removePrefix("www.")
        val query=u.rawQuery?.split('&')?.filterNot {
            it.startsWith("utm_") || it.startsWith("fbclid=") || it.startsWith("gclid=")
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        URI("https", u.userInfo, host, if(u.port == 443 || u.port == 80) -1 else u.port,
            u.path.ifBlank { "/" }, query, null).toASCIIString()
    } catch (_: Exception) { null }
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
    fun search(query: String, limit: Int=15): List<VideoHit> {
        val q=query.trim().take(180)
        if(q.isBlank()) return emptyList()
        if(isUrl(q)) return listOf(VideoHit(q,q))
        val queries=listOf(
            "https://www.google.com/search?q="+URLEncoder.encode(q, "UTF-8")+"&num=30",
            "https://www.bing.com/search?q="+URLEncoder.encode(q,"UTF-8")
        )
        val out=linkedMapOf<String,VideoHit>()
        for(url in queries) {
            try {
                val document=open(url)
                val anchors=document.select("a[href]")
                for(a in anchors) {
                    var link=a.attr("abs:href")
                    val candidate=runCatching { URI(link) }.getOrNull() ?: continue
                    if(candidate.host?.contains("google.")==true && candidate.path=="/url") {
                        link=candidate.rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") }?.substringAfter("q=") ?: link
                        link=java.net.URLDecoder.decode(link,"UTF-8")
                    }
                    val n=normalize(link) ?: continue
                    val domain=URI(n).host
                    if(listOf("google.com","bing.com","youtube.com/redirect","duckduckgo.com").any { domain.endsWith(it) }) continue
                    if(!isVideoPage(n)) continue
                    out.putIfAbsent(n, VideoHit(a.text().ifBlank { "Found video" }.take(180), n))
                    if(out.size >= limit) return out.values.toList()
                }
            } catch (_: Exception) { }
            if(out.isNotEmpty()) break
        }
        return out.values.take(limit)
    }
    fun discover(watch: Watch): List<VideoHit> =
        if(watch.address.startsWith("search:")) search(watch.address.removePrefix("search:"),15)
        else findOnPage(watch.address, 15)
}

