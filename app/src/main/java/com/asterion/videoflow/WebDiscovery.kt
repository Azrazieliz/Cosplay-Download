package com.asterion.videoflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class VideoHit(val title:String, val url:String)

/** Exact-page discovery only. No keyword search, indexing, Tor, or source expansion. */
object WebDiscovery {
    private val videoPaths = listOf(
        Regex("view_video\\.php\\?[^#]*viewkey=",RegexOption.IGNORE_CASE),
        Regex("/videos?/[^/?#]+",RegexOption.IGNORE_CASE),
        Regex("/watch(?:/[^/?#]+|\\?v=[^&#]+)",RegexOption.IGNORE_CASE),
        Regex("/shorts/[^/?#]+",RegexOption.IGNORE_CASE),
        Regex("/(?:post|clip|v)/[^/?#]+",RegexOption.IGNORE_CASE)
    )
    private val mediaFiles=Regex("\\.(mp4|webm|mov|m3u8|mpd)(?:[?#]|$)",RegexOption.IGNORE_CASE)
    private fun privateHost(host:String):Boolean {
        val h=host.lowercase()
        return h=="localhost"||h.endsWith(".local")||h.endsWith(".onion")||
            h=="127.0.0.1"||h=="0.0.0.0"||h=="::1"||h.startsWith("127.")||
            h.startsWith("192.168.")||h.startsWith("10.")||h.startsWith("169.254.")||
            (h.startsWith("172.") && h.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
    }
    fun isUrl(address:String):Boolean = try {
        val u=URI(address.trim())
        u.scheme in listOf("https","http") && !u.host.isNullOrBlank() && !privateHost(u.host) &&
            u.userInfo==null
    } catch(_:Exception){false}
    fun normalize(address:String):String?=try{
        if(!isUrl(address))null
        else {
            val u=URI(address.trim())
            // Preserve complete source URL, including signed media query params and host.
            u.toASCIIString().substringBefore('#')
        }
    } catch(_:Exception){null}
    fun isVideoPage(address:String):Boolean =
        mediaFiles.containsMatchIn(address) || videoPaths.any { it.containsMatchIn(address) }
    private fun open(url:String):Document=Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        .timeout(18000).followRedirects(true).maxBodySize(2500000).get()
    fun findOnPage(address:String,limit:Int=15):List<VideoHit> {
        require(isUrl(address)){"Invalid public HTTP(S) page"}
        val root=open(address)
        val initialHost=URI(address).host.lowercase().removePrefix("www.")
        val found=linkedMapOf<String,VideoHit>()
        fun add(raw:String,title:String){
            val url=runCatching{URI(root.location()).resolve(raw).toString()}.getOrNull()?:return
            val normalized=normalize(url)?:return
            val host=URI(normalized).host.lowercase().removePrefix("www.")
            if(host!=initialHost&&!host.endsWith(".$initialHost"))return
            if(!isVideoPage(normalized))return
            found.putIfAbsent(normalized,VideoHit(title.trim().ifEmpty{"Video"}.take(180),normalized))
        }
        if(isVideoPage(root.location()))add(root.location(),root.title())
        root.select("a[href]").forEach {a->
            add(a.attr("abs:href"),a.attr("title").ifBlank{a.text()})
        }
        return found.values.take(limit.coerceIn(1,50))
    }
    fun directMedia(address:String):String? {
        require(isUrl(address)){"Invalid public HTTP(S) URL"}
        if(mediaFiles.containsMatchIn(address))return address
        val page=open(address)
        for(node in page.select("video[src],video source[src],source[src],meta[property=og:video],meta[property=og:video:url],meta[itemprop=contentUrl]")){
            val source=node.attr("abs:src").ifBlank{node.attr("content")}
            val resolved=runCatching{URI(page.location()).resolve(source).toString()}.getOrNull()?:continue
            if(isUrl(resolved)&&mediaFiles.containsMatchIn(resolved))return resolved
        }
        return null
    }
}
