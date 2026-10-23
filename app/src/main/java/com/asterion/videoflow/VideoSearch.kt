package com.asterion.videoflow

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Searches the open web, not merely the sites bundled in the extractor catalog.
 *  Results are candidate pages; the downloader decides whether extraction works. */
data class DiscoveryMatch(
    val title:String,
    val url:String,
    val origin:String,
    val type:String
)

object VideoSearch {
    private const val USER_AGENT="Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    private val ignores=setOf("google.com","google.fr","bing.com","duckduckgo.com","yahoo.com","search.yahoo.com",
        "brave.com","search.brave.com","facebook.com","pinterest.com","t.co")
    private val extensions=Regex("\\.(mp4|webm|mov|m3u8|mpd)([?#]|$)",RegexOption.IGNORE_CASE)
    private val videoPath=Regex("(^|/)(video|videos|watch|clip|clips|post|posts|reel|shorts|scene|scenes|v|embed|player)([/?.-]|$)",RegexOption.IGNORE_CASE)
    private val known=Regex("(view_video\\.php|viewkey=|/video[-_]?\\d+|/\\d{5,}[/-])",RegexOption.IGNORE_CASE)
    private fun looksVideo(url:String):Boolean = extensions.containsMatchIn(url)||
        videoPath.containsMatchIn(url)||known.containsMatchIn(url)||
        url.contains("youtu.be/") || url.contains("/watch?v=")

    /** Avoid treating arbitrary web search pages as video results. */
    private fun allow(raw:String):String? {
        val u=WebDiscovery.normalize(raw) ?: return null
        val host=runCatching{URI(u).host.lowercase(Locale.ROOT)}.getOrNull() ?: return null
        if(ignores.any{host==it||host.endsWith(".$it")}) return null
        return u
    }
    private fun resolveLink(link:String):String {
        val uri=runCatching{URI(link)}.getOrNull() ?: return link
        val query=uri.rawQuery.orEmpty().split('&').firstOrNull{it.startsWith("uddg=")||it.startsWith("url=")||it.startsWith("u=")||it.startsWith("q=")}
        if((uri.host.orEmpty().contains("duckduckgo")||uri.host.orEmpty().contains("google")||
                uri.host.orEmpty().contains("bing")||uri.host.orEmpty().contains("yahoo"))&&query!=null) {
            val value=query.substringAfter('=')
            val unwrapped=runCatching{URLDecoder.decode(value,"UTF-8")}.getOrDefault(link)
            if(unwrapped.startsWith("http"))return unwrapped
        }
        return link
    }
    private fun open(url:String):Document=Jsoup.connect(url)
        .userAgent(USER_AGENT).timeout(13000)
        .followRedirects(true).maxBodySize(1600_000).get()

    private data class Engine(val name:String,val url:(String)->String,val selector:String)
    private val engines=listOf(
        Engine("Bing",{q->"https://www.bing.com/search?q="+URLEncoder.encode(q,"UTF-8")+"&count=30"},"li.b_algo h2 a[href], li.b_algo a[href], .b_title a[href]"),
        Engine("DuckDuckGo",{q->"https://html.duckduckgo.com/html/?q="+URLEncoder.encode(q,"UTF-8")},".result__a[href], .result__url[href]"),
        Engine("Google",{q->"https://www.google.com/search?q="+URLEncoder.encode(q,"UTF-8")+"&num=30"},"a[href]"),
        Engine("Yahoo",{q->"https://search.yahoo.com/search?p="+URLEncoder.encode(q,"UTF-8")},".algo h3 a[href], .algo a[href]")
    )

    fun search(query:String, limit:Int=40):List<DiscoveryMatch> {
        val phrase=query.trim().take(170)
        if(phrase.isBlank())return emptyList()
        if(WebDiscovery.isUrl(phrase))return listOf(DiscoveryMatch(phrase,phrase,"URL","direct"))
        val results=linkedMapOf<String,DiscoveryMatch>()
        fun push(raw:String,title:String,engine:String,relaxed:Boolean=false) {
            val url=allow(resolveLink(raw)) ?: return
            if(!relaxed&&!looksVideo(url)) return
            val host=runCatching{URI(url).host}.getOrDefault("website")
            val name=title.trim().replace(Regex("\\s+")," ").take(170).ifBlank{host}
            val type=if(looksVideo(url))"video page" else "possible video"
            results.putIfAbsent(url,DiscoveryMatch(name,url,host,engine+" · "+type))
        }
        // Probe all engines: do not abort when the first one produces only a couple of matches.
        for(engine in engines) {
            val page=runCatching{open(engine.url(phrase+" video"))}.getOrNull() ?: continue
            for(anchor in page.select(engine.selector).take(90)) {
                push(anchor.attr("abs:href"),anchor.text().ifBlank{anchor.attr("title")},engine.name)
                if(results.size>=limit) break
            }
            if(results.size>=limit)break
        }
        // Unusual sources may use unrecognizable routes. Include clearly tagged lower-confidence
        // candidates returned by independent engines instead of silently presenting no results.
        if(results.size<6) {
            for(engine in engines.take(3)) {
                val page=runCatching{open(engine.url(phrase+" full video"))}.getOrNull() ?: continue
                for(anchor in page.select(engine.selector).take(45)) {
                    val title=anchor.text()
                    val hint=title.contains("video",true)||title.contains("watch",true)||
                        title.contains("full",true)||title.contains("clip",true)
                    if(hint)push(anchor.attr("abs:href"),title,engine.name,true)
                    if(results.size>=limit)break
                }
                if(results.size>=limit)break
            }
        }
        // Rank direct video URLs before unverified page matches.
        return results.values.sortedWith(compareByDescending<DiscoveryMatch>{
            if(it.type.contains("video page"))2 else 0
        }.thenBy{it.origin}).take(limit.coerceIn(1,80))
    }
}