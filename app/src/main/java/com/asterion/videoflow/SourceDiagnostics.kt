package com.asterion.videoflow

import java.net.URI
import java.util.Locale

/** Diagnose extractor responses without interpreting every 502 as a removed video. */
object SourceDiagnostics {
    fun summary(error:String?):String {
        val value=error.orEmpty().lowercase(Locale.ROOT)
        return when {
            "http error 502" in value || "bad gateway" in value ->
                "The website returned a temporary gateway error (502). The video may still exist."
            "http error 503" in value || "service unavailable" in value ->
                "The video host is temporarily unavailable (503)."
            "http error 403" in value || "forbidden" in value ->
                "This website rejected the request (403). Authentication or browser verification may be required."
            "http error 429" in value || "too many requests" in value ->
                "The website is limiting requests (429). Avoid repeated retries for now."
            "http error 404" in value -> "The video page was not found (404); it may have moved or been removed."
            "sign in" in value || "login" in value || "authentication" in value ->
                "This video requires a valid signed-in session."
            "unsupported url" in value || "no suitable extractor" in value ->
                "No working extractor is available for this site yet."
            "requested format is not available" in value ->
                "The selected video format is unavailable. Try Best single-file in Settings."
            "failed to download" in value || "unable to download" in value ->
                "The website could not be reached by the extractor. Try the original video page and check site access."
            value.isBlank() -> "Download unsuccessful. Open technical details for more information."
            else -> error.orEmpty().take(185)
        }
    }
    fun retryAdvice(error:String?):String {
        val code=error.orEmpty().lowercase(Locale.ROOT)
        return when {
            "502" in code || "503" in code -> "Try again later, or check whether the video opens in your browser."
            "403" in code || "login" in code -> "If authorized, import fresh cookies in Settings and retry."
            "429" in code -> "Reduce request frequency and try later."
            else -> "Verify the exact video URL, then retry or inspect the detailed error."
        }
    }
    /** Retry an equivalent video-page URL only for sites that publish both host aliases.
     *  This recovers older database entries where VideoFlow wrongly stripped www. */
    fun alternateHosts(link:String):List<String> = runCatching {
        val uri=URI(link)
        if(uri.scheme !in listOf("http","https")||uri.host.isNullOrBlank())return@runCatching emptyList()
        val host=uri.host.lowercase(Locale.ROOT)
        val bare=host.removePrefix("www.")
        val candidate=if(host.startsWith("www."))bare else "www.$bare"
        if(bare in setOf("pornhub.com","freepornvideos.xxx","xvideos.com","xnxx.com",
                "xhamster.com","spankbang.com","redtube.com","youporn.com",
                "beeg.com","eporner.com","notfans.com","archivebate.com")) {
            listOf(URI(uri.scheme,uri.userInfo,candidate,uri.port,uri.path,uri.query,uri.fragment).toString())
        } else emptyList()
    }.getOrDefault(emptyList())
}
