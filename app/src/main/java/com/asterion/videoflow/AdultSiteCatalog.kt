package com.asterion.videoflow

import java.net.URI

/** Catalog entries are discovery candidates, NOT claims that every site can be downloaded. */
data class VideoSite(val host: String, val label: String, val extractor: String = "best-effort") {
    fun matches(url: String): Boolean = runCatching {
        val value = URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        value == host || value.endsWith(".$host")
    }.getOrDefault(false)
}

object AdultSiteCatalog {
    val sites = listOf(
        VideoSite("pornhub.com","Pornhub","dedicated"),
        VideoSite("notfans.com","NotFans","generic-tested"),
        VideoSite("archivebate.com","ArchiveBate"),
        VideoSite("freepornvideos.xxx","FreePornVideos","generic-tested"),
        VideoSite("xvideos.com","XVideos","dedicated"),
        VideoSite("xnxx.com","XNXX","dedicated"),
        VideoSite("xhamster.com","xHamster","dedicated"),
        VideoSite("spankbang.com","SpankBang","dedicated"),
        VideoSite("redtube.com","RedTube","dedicated"),
        VideoSite("youporn.com","YouPorn","dedicated"),
        VideoSite("beeg.com","Beeg","dedicated"),
        VideoSite("eporner.com","Eporner","dedicated"),
        VideoSite("tnaflix.com","TNAFlix","dedicated"),
        VideoSite("motherless.com","Motherless","dedicated"),
        VideoSite("4tube.com","4Tube","dedicated"),
        VideoSite("pornerbros.com","PornerBros","dedicated"),
        VideoSite("thumbzilla.com","Thumbzilla","dedicated"),
        VideoSite("tube8.com","Tube8","compatibility-unknown"),
        VideoSite("youjizz.com","YouJizz","dedicated"),
        VideoSite("pornhd.com","PornHD","dedicated"),
        VideoSite("pornbox.com","Pornbox","dedicated"),
        VideoSite("pornflip.com","PornFlip","dedicated"),
        VideoSite("empflix.com","EMPFlix","dedicated"),
        VideoSite("porntrex.com","PornTrex","compatibility-unknown"),
        VideoSite("txxx.com","TXXX","compatibility-unknown"),
        VideoSite("hqporner.com","HQPorner","compatibility-unknown"),
        VideoSite("sunporno.com","SunPorno","compatibility-unknown"),
        VideoSite("vporn.com","VPorn","compatibility-unknown"),
        VideoSite("pornhat.com","PornHat","compatibility-unknown"),
        VideoSite("upornia.com","UPornia","compatibility-unknown"),
        VideoSite("hdzog.com","HDZog","compatibility-unknown"),
        VideoSite("hclips.com","HClips","compatibility-unknown"),
        VideoSite("hotmovs.com","HotMovs","compatibility-unknown"),
        VideoSite("gotporn.com","GotPorn","compatibility-unknown"),
        VideoSite("porn.com","Porn.com","compatibility-unknown"),
        VideoSite("manyvids.com","ManyVids","dedicated"),
        VideoSite("thisvid.com","ThisVid","dedicated"),
        VideoSite("erome.com","Erome","dedicated"),
        VideoSite("heavy-r.com","Heavy-R","compatibility-unknown"),
        VideoSite("mofosex.com","Mofosex","dedicated"),
        VideoSite("xozilla.com","Xozilla","compatibility-unknown"),
        VideoSite("porn300.com","Porn300","compatibility-unknown"),
        VideoSite("anysex.com","AnySex","compatibility-unknown"),
        VideoSite("fullporner.com","FullPorner","compatibility-unknown"),
        VideoSite("pornwhite.com","PornWhite","compatibility-unknown")
    )
    val hosts: Set<String> = sites.map { it.host }.toSet()
    fun match(url: String): VideoSite? = sites.firstOrNull { it.matches(url) }
    fun label(host: String): String = sites.firstOrNull { it.host == host }?.label ?: host
    fun siteQuery(host: String, performer: String): String =
        "site:$host \"$performer\" video"
    fun isAllowedHost(host: String): Boolean = sites.any { it.host == host } ||
        (host.isNotBlank() && !host.contains('/') && !host.contains(' '))
}