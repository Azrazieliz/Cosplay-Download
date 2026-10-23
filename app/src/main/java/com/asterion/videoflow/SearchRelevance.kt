package com.asterion.videoflow

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Ranking and inclusion policy: a URL is not a video just because it is a search hit.
 * Relevance is AND-based and adult evidence is mandatory, regardless of host.
 * Pure Kotlin: unit-test without Android or network.
 */
object SearchRelevance {
    data class Evidence(
        val title:String,
        val snippet:String,
        val url:String,
        val pageVideo:Boolean=false,
        val pageAdult:Boolean=false
    )
    data class Decision(val accept:Boolean,val reason:String,val score:Int)

    private val generic=setOf("porn","porno","pornographic","xxx","adult","nsfw",
        "full","video","videos","movie","movies","scene","scenes","watch","hd","free")
    private val filler=setOf("the","a","an","and","or","with","of","in","on","for","to","is")
    private val adultWords=Regex("""\b(porn|porno|xxx|nsfw|hardcore|hentai|erotic|uncensored|explicit|adult\s*video|sex\s*video|onlyfans|manyvids)\b""",RegexOption.IGNORE_CASE)
    private val mediaExt=Regex("""\.(mp4|m3u8|webm|mov|mpd)([?#]|$)""",RegexOption.IGNORE_CASE)
    private val videoRoute=Regex("""(/(video|videos|watch|clip|clips|scene|scenes|embed|player|shorts|reel|reels|v)/[^/?#]+|view_video\.php\?[^#]*viewkey=|/watch\?v=[\w-]+|youtu\.be/[\w-]+|/video[-_.]?\w+|/hd-porn/[^/?#]+|/\d{5,}(?:/|$))""",RegexOption.IGNORE_CASE)
    private val urlNoise=setOf("google.com","bing.com","duckduckgo.com","yahoo.com","pinterest.com",
        "facebook.com","twitter.com","instagram.com")
    fun canonical(s:String):String=Normalizer.normalize(s.lowercase(Locale.ROOT),Normalizer.Form.NFKD)
        .replace(Regex("""\p{Mn}+"""),"")
        .replace(Regex("""[^a-z0-9]+""")," ").trim().replace(Regex("""\s+""")," ")

    /** Exact query terms and singular/plural aliases only. Never match substrings of unrelated words. */
    fun requestedTerms(query:String):List<String> = canonical(query).split(' ')
        .filter{it.length>1 && it !in filler && it !in generic}
        .distinct()

    private fun matches(term:String,textWords:Set<String>):Boolean {
        if(term in textWords)return true
        val aliases=when(term){
            "foot"->listOf("feet")
            "feet"->listOf("foot")
            "woman"->listOf("women")
            "women"->listOf("woman")
            else->emptyList()
        }
        if(aliases.any{it in textWords})return true
        if(term.endsWith("s")&&term.dropLast(1) in textWords)return true
        if((term+"s") in textWords)return true
        return false
    }

    fun isVideoUrl(url:String):Boolean{
        val u=runCatching{URI(url)}.getOrNull()?:return false
        val path=(u.rawPath.orEmpty()+(if(u.rawQuery!=null)"?"+u.rawQuery else ""))
        val host=u.host?.lowercase(Locale.ROOT)?:return false
        return mediaExt.containsMatchIn(path) || videoRoute.containsMatchIn(path) ||
            (host=="youtu.be"&&u.path.length>3)
    }
    fun adultHost(url:String):Boolean{
        val host=runCatching{URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")}.getOrNull()?:return false
        return AdultSiteCatalog.hosts.any{host==it||host.endsWith("."+it)}
    }
    fun evaluate(query:String, evidence:Evidence):Decision{
        val terms=requestedTerms(query)
        if(terms.isEmpty())return Decision(false,"Enter descriptive keywords beyond 'porn video'",0)
        val host=runCatching{URI(evidence.url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")}.getOrNull()
            ?:return Decision(false,"Invalid source",0)
        if(urlNoise.any{host==it||host.endsWith("."+it)})return Decision(false,"Not a video destination",0)
        val sourceAdult=adultHost(evidence.url)
        val titleWords=canonical(evidence.title).split(' ').filter{it.isNotBlank()}.toSet()
        val snippetWords=canonical(evidence.snippet).split(' ').filter{it.isNotBlank()}.toSet()
        if(terms.none{matches(it,titleWords)})
            return Decision(false,"The video title does not contain a requested keyword",0)
        if(terms.any{!matches(it,titleWords)&&!matches(it,snippetWords)})
            return Decision(false,"Missing one or more requested keywords",0)
        val video=isVideoUrl(evidence.url)||evidence.pageVideo
        if(!video)return Decision(false,"Not a confirmed video page",0)
        // Unknown domains, including YouTube, cannot be treated as adult solely because a
        // search engine returned them for a porn query. Require actual adult page evidence.
        val adult=sourceAdult||evidence.pageAdult||
            (adultWords.containsMatchIn(evidence.title)&&evidence.pageVideo)
        if(!adult)return Decision(false,"No evidence of adult video content",0)
        val exactTitleCount=terms.count{matches(it,titleWords)}
        val score=40+20*exactTitleCount+
            (if(sourceAdult)14 else 0)+(if(evidence.pageVideo)14 else 0)+
            (if(evidence.pageAdult)12 else 0)
        return Decision(true,"All keywords matched; adult video evidence found",score)
    }
}