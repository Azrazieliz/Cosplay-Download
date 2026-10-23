package com.asterion.videoflow

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Dedicated adult-video discovery. Generic search hits are never shown without
 *  a video URL and evidence that ALL requested keywords refer to its content. */
data class DiscoveryMatch(
    val title:String,val url:String,val origin:String,val type:String,
    val score:Int=0,val evidence:String=""
)

object VideoSearch {
    private const val UA="Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    private data class Hit(val title:String,val snippet:String,val link:String,val provider:String)
    private data class Engine(val name:String,val url:(String)->String,val block:String,
                              val anchor:String,val excerpt:String)
    private fun encode(s:String)=URLEncoder.encode(s,"UTF-8")
    private val engines=listOf(
        Engine("DuckDuckGo",{q->"https://html.duckduckgo.com/html/?q="+encode(q)+"&kp=-2"},
            ".result",".result__a",".result__snippet"),
        Engine("Bing",{q->"https://www.bing.com/search?q="+encode(q)+"&count=30&adlt=off"},
            "li.b_algo","h2 a[href]","p, .b_caption"),
        Engine("Google",{q->"https://www.google.com/search?q="+encode(q)+"&num=30&safe=off"},
            "div.MjjYud, div.g","a:has(h3)","div.VwiC3b, .st")
    )
    private fun open(url:String):Document=Jsoup.connect(url).userAgent(UA)
        .timeout(9000).followRedirects(true).maxBodySize(1350_000).get()
    private fun link(raw:String,base:String):String? {
        val full=runCatching{URI(base).resolve(raw).toString()}.getOrNull()?:return null
        val target=runCatching{URI(full)}.getOrNull()?:return null
        val host=target.host.orEmpty().lowercase(Locale.ROOT)
        if(listOf("duckduckgo.com","google.com","bing.com","yahoo.com").any{
            host==it || host.endsWith("."+it)
        }) {
            val param=target.rawQuery.orEmpty().split('&').firstOrNull{
                it.startsWith("uddg=")||it.startsWith("url=")||it.startsWith("q=")
            }
            if(param!=null) {
                val decoded=runCatching{URLDecoder.decode(param.substringAfter('='),"UTF-8")}.getOrDefault("")
                if(WebDiscovery.isUrl(decoded))return WebDiscovery.normalize(decoded)
            }
        }
        return WebDiscovery.normalize(full)
    }
    private fun searchEngine(e:Engine,query:String):List<Hit> {
        val page=open(e.url(query))
        return page.select(e.block).take(34).mapNotNull { node->
            val a=node.selectFirst(e.anchor)?:return@mapNotNull null
            val title=a.text().trim()
            val url=link(a.attr("abs:href").ifBlank{a.attr("href")},page.location())
                ?:return@mapNotNull null
            if(title.length<3)null else Hit(title,node.selectFirst(e.excerpt)?.text().orEmpty().take(350),
                url,e.name)
        }
    }
    private fun firstPartySearch(label:String,url:String):List<Hit> {
        val page=open(url)
        val results=ArrayList<Hit>()
        for(a in page.select("a[href]").take(350)) {
            val target=link(a.attr("href"),page.location())?:continue
            if(!SearchRelevance.isVideoUrl(target))continue
            val title=a.attr("title").ifBlank {
                a.selectFirst("img[alt]")?.attr("alt").orEmpty()
            }.ifBlank{a.text()}.take(170)
            if(title.length>=3)results.add(Hit(title,"",target,label+" site search"))
            if(results.size>=35)break
        }
        return results
    }
    private fun pageDetails(url:String):Triple<String,Boolean,Boolean>? {
        val page=runCatching{open(url)}.getOrNull()?:return null
        val title=page.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank{page.title()}
        val description=page.selectFirst("meta[name=description]")?.attr("content").orEmpty()+
            " "+page.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val keywords=page.selectFirst("meta[name=keywords]")?.attr("content").orEmpty()
        val isVideo=page.select("video[src], video source[src], meta[property=og:video], meta[property=og:video:url]").isNotEmpty()||
            page.select("script[type=application/ld+json]").any{it.data().contains("VideoObject",true)}||
            page.selectFirst("meta[property=og:type]")?.attr("content").orEmpty().contains("video",true)
        val explicit=Regex("""\b(porn|porno|xxx|nsfw|hardcore|hentai|explicit|erotic|onlyfans|adult\s*video|sex\s*video)\b""",RegexOption.IGNORE_CASE)
        val adult=explicit.containsMatchIn(title+" "+description+" "+keywords) ||
            Regex("""porn|xxx|hentai|adultvideo""",RegexOption.IGNORE_CASE)
                .containsMatchIn(URI(url).host.orEmpty())
        return Triple(title+" "+description,isVideo,adult)
    }
    private fun matched(query:String,hit:Hit):DiscoveryMatch? {
        val uri=WebDiscovery.normalize(hit.link)?:return null
        val host=runCatching{URI(uri).host.orEmpty()}.getOrDefault("")
        val tokens=SearchRelevance.requestedTerms(query)
        if(tokens.isEmpty())return null
        // Cheap relevance gate prevents unrelated pages from being fetched or displayed.
        val organic=SearchRelevance.canonical(hit.title+" "+hit.snippet).split(' ').toSet()
        if(tokens.any{it !in organic && it+"s" !in organic &&
                !(it.endsWith("s")&&it.dropLast(1) in organic) &&
                !(it=="foot"&&"feet" in organic) && !(it=="feet"&&"foot" in organic)})return null
        val adultSite=SearchRelevance.adultHost(uri)
        val videoRoute=SearchRelevance.isVideoUrl(uri)
        if(!videoRoute)return null
        var verifiedVideo=false
        var verifiedAdult=false
        var title=hit.title
        // Unknown/unusual hosts (including YouTube) require actual page-level adult AND
        // video evidence; an organic snippet merely containing "porn" is insufficient.
        if(!adultSite) {
            val meta=pageDetails(uri)?:return null
            verifiedVideo=meta.second
            verifiedAdult=meta.third
            if(!verifiedVideo||!verifiedAdult)return null
            // Retain search headline if site metadata is merely a generic branding title.
            if(meta.first.isNotBlank())title=meta.first.take(170)
        }
        val evaluated=SearchRelevance.evaluate(query,SearchRelevance.Evidence(
            title,hit.snippet,uri,verifiedVideo,verifiedAdult))
        if(!evaluated.accept)return null
        return DiscoveryMatch(title.take(170),uri,host,
            if(verifiedVideo)"VERIFIED VIDEO PAGE" else "MATCHED VIDEO PAGE",
            evaluated.score,
            if(verifiedVideo)"Player metadata verified" else "Matched adult video title")
    }
    fun search(query:String,limit:Int=30,onProgress:((String)->Unit)?=null):List<DiscoveryMatch> {
        val phrase=query.trim().take(160)
        if(phrase.isBlank()||WebDiscovery.isUrl(phrase)||
            SearchRelevance.requestedTerms(phrase).isEmpty())return emptyList()
        val found=linkedMapOf<String,DiscoveryMatch>()
        val examined=hashSetOf<String>()
        var checkedUnusual=0
        fun collect(items:List<Hit>) {
            for(item in items) {
                val url=WebDiscovery.normalize(item.link)?:continue
                if(!examined.add(url))continue
                if(!SearchRelevance.adultHost(url)) {
                    if(checkedUnusual>=6)continue
                    checkedUnusual++
                }
                val match=matched(phrase,item)?:continue
                val key=url.removePrefix("https://www.").lowercase(Locale.ROOT)
                val previous=found[key]
                if(previous==null||match.score>previous.score)found[key]=match
            }
        }
        // Parallelize search providers so a blocked index does not stall the whole screen.
        // Threads are short-lived and no site is queried more than once per search.
        val executor=java.util.concurrent.Executors.newFixedThreadPool(6)
        val completion=java.util.concurrent.ExecutorCompletionService<Pair<String,List<Hit>>>(executor)
        val q=phrase+" porn full video"
        val encoded=encode(phrase)
        val jobs=ArrayList<Pair<String,()->List<Hit>>>()
        engines.forEach{engine->
            jobs.add(engine.name to {searchEngine(engine,q)})
        }
        jobs.add("XVideos" to {firstPartySearch("XVideos","https://www.xvideos.com/?k="+encoded)})
        jobs.add("Pornhub" to {firstPartySearch("Pornhub","https://www.pornhub.com/video/search?search="+encoded)})
        jobs.add("Eporner" to {firstPartySearch("Eporner","https://www.eporner.com/search/"+encoded+"/")})
        for((name,fetch) in jobs) {
            completion.submit(java.util.concurrent.Callable {
                name to runCatching {fetch()}.getOrDefault(emptyList())
            })
        }
        onProgress?.invoke("Searching adult video sites and wider web indexes…")
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(26)
        try {
            for(i in jobs.indices) {
                val remaining=deadline-System.nanoTime()
                if(remaining<=0)break
                val future=completion.poll(remaining,java.util.concurrent.TimeUnit.NANOSECONDS)
                    ?:break
                val result=runCatching{future.get()}.getOrNull()?:continue
                onProgress?.invoke("Checking "+result.first+" results for keyword matches…")
                collect(result.second)
                if(found.size>=limit)break
            }
        } finally {
            executor.shutdownNow()
        }
        return found.values.sortedWith(compareByDescending<DiscoveryMatch>{it.score}
            .thenBy{it.origin}).take(limit.coerceIn(1,40))
    }
}
