package com.asterion.videoflow

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
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
        val tokens=SearchRelevance.requestedTerms(query)
        val hay=SearchRelevance.canonical(title).split(' ').toSet()
        return tokens.isNotEmpty() && tokens.all {
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
        val collected=linkedMapOf<String,Candidate>()
        onProgress?.invoke("Searching Ahmia's Tor index…")
        // Ahmia implements its own safety filtering, which we do not bypass.
        val index="https://ahmia.fi/search/?q="+URLEncoder.encode(query,"UTF-8")
        val indexPage=runCatching{TorAccess.fetch(index,tor)}.getOrNull()
        if(indexPage!=null) for(c in candidates(indexPage,index,query,"Ahmia",8)){
            collected.putIfAbsent(c.url,c)
        }
        onProgress?.invoke("Checking your configured onion sources…")
        for(link in knownSources.filter{TorAccess.isV3Onion(it)}.distinct().take(5)) {
            val page=runCatching{TorAccess.fetch(link,tor)}.getOrNull()?:continue
            for(c in candidates(page,link,query,"Saved onion source",8))
                candidates.putIfAbsent(c.url,c)
        }
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
