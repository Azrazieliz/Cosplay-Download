package com.asterion.videoflow

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.jsoup.nodes.Document

/** Voluntary indexed and user-supplied Tor source discovery. No exhaustive dark-web index.
 * All page requests are proxied; all candidates must have page-level video and adult evidence.
 * No source is automatically downloaded during search. */
object TorDiscovery {
    private data class Candidate(val title:String,val url:String,val source:String)
    private val adultTags=Regex("""\b(porn|porno|xxx|nsfw|hardcore|hentai|explicit|erotic|adult\s*video|sex\s*video|onlyfans)\b""",
        RegexOption.IGNORE_CASE)
    private fun onionLink(value:String,base:String):String? {
        var candidate=runCatching{URI(base).resolve(value.trim()).toString()}.getOrNull()?:return null
        if(!TorAccess.isOnion(candidate)){
            val parsed=runCatching{URI(candidate)}.getOrNull()?:return null
            val redirect=parsed.rawQuery.orEmpty().split('&').firstOrNull {
                it.startsWith("redirect=")||it.startsWith("url=")||it.startsWith("target=")
            }
            if(redirect!=null){
                candidate=runCatching{URLDecoder.decode(redirect.substringAfter('='),"UTF-8")}
                    .getOrNull()?:return null
            }
        }
        if(!TorAccess.isV3Onion(candidate))return null
        return WebDiscovery.normalize(candidate)
    }
    private fun matchesEarly(query:String,title:String):Boolean {
        val tokens=SearchRelevance.contentTerms(query)
        val hay=SearchRelevance.canonical(title).split(' ').toSet()
        return tokens.all {
            it in hay || it+"s" in hay ||
                (it.endsWith("s")&&it.dropLast(1) in hay) ||
                (it=="foot"&&"feet" in hay) || (it=="feet"&&"foot" in hay)
        }
    }
    private fun candidates(page:Document,base:String,query:String,label:String,cap:Int):List<Candidate> {
        val discovered=linkedMapOf<String,Candidate>()
        for(anchor in page.select("a[href]").take(400)){
            val title=anchor.attr("title").ifBlank{anchor.text()}
                .ifBlank{anchor.selectFirst("img[alt]")?.attr("alt").orEmpty()}.take(180)
            if(title.isBlank()||!matchesEarly(query,title))continue
            val destination=onionLink(anchor.attr("href"),base)?:continue
            discovered.putIfAbsent(destination,Candidate(title,destination,label))
            if(discovered.size>=cap)break
        }
        return discovered.values.toList()
    }
    private fun inspected(query:String,candidate:Candidate,tor:TorOptions):DiscoveryMatch? {
        val doc=runCatching{TorAccess.fetch(candidate.url,tor)}.getOrNull()?:return null
        val title=doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank{doc.title()}.ifBlank{candidate.title}
        val description=doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()+
            " "+doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val keywords=doc.selectFirst("meta[name=keywords]")?.attr("content").orEmpty()
        val video=doc.select("video[src],video source[src],meta[property=og:video],meta[property=og:video:url]").isNotEmpty() ||
            doc.select("script[type=application/ld+json]").any{it.data().contains("VideoObject",true)} ||
            doc.selectFirst("meta[property=og:type]")?.attr("content").orEmpty().contains("video",true)
        val adult=adultTags.containsMatchIn(title+" "+description+" "+keywords)
        val decision=SearchRelevance.evaluate(query,
            SearchRelevance.Evidence(title,description,candidate.url,video,adult))
        if(!decision.accept)return null
        return DiscoveryMatch(title.take(170),candidate.url,
            URI(candidate.url).host.orEmpty(),"TOR • ONION VIDEO",decision.score,
            "Onion page metadata verified via Tor; availability and consent cannot be confirmed")
    }
    fun search(query:String,tor:TorOptions,knownSources:List<String>,
               onProgress:((String)->Unit)?=null):List<DiscoveryMatch>{
        TorAccess.check(tor) // Strictly abort if SOCKS is absent; no clearnet fallback.
        // Ahmia documents that it filters sexually related searches. Do not rely on
        // an index that cannot return the user's requested media, or bypass its filters.
        val sources=knownSources.filter{TorAccess.isV3Onion(it)}.distinct()
        require(sources.isNotEmpty()){
            "Tor is reachable, but no onion video pages are configured. Ahmia filters adult searches. Add a known .onion video site in Sites."
        }
        val collected=linkedMapOf<String,Candidate>()
        onProgress?.invoke("Searching "+sources.size+" saved onion sources through Tor…")
        var reachable=0
        for(link in sources.take(12)) {
            val page=runCatching{TorAccess.fetch(link,tor)}.getOrNull()?:continue
            reachable++
            for(c in candidates(page,link,query,"Saved onion source",12))
                collected.putIfAbsent(c.url,c)
        }
        if(reachable==0)throw IllegalStateException(
            "No configured onion sources responded through Orbot. Check their addresses in Tor Browser."
        )
        val output=ArrayList<DiscoveryMatch>()
        for(candidate in collected.values.take(10)) {
            onProgress?.invoke("Verifying onion video pages through Tor…")
            val confirmed=inspected(query,candidate,tor)?:continue
            output.add(confirmed)
            if(output.size>=8)break
        }
        return output.sortedByDescending{it.score}
    }
}
