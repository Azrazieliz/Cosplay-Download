package com.asterion.videoflow

import android.content.Context
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import java.io.File
import java.net.URI
import java.util.Locale

data class VideoMetadata(
    val title: String, val durationSeconds: Int?, val searchable: String, val valid: Boolean
)

object PerformerScanner {
    private val excluded = Regex("\\b(trailer|preview|teaser|sample|highlight|shorts|compilation\\s+preview)\\b",RegexOption.IGNORE_CASE)

    /** Probe a public or account-authorized video with the actual embedded yt-dlp engine.
     *  Extraction is metadata-only and never decrypts protected streams. */
    fun probe(context: Context, url: String): VideoMetadata? = runCatching {
        YtDlp.init(context.applicationContext)
        val cookieFile=File(context.filesDir,"cookies.txt")
        val opts=mutableMapOf<String,Any>(
            "quiet" to true, "no_warnings" to true, "skip_download" to true,
            "noplaylist" to true, "socket_timeout" to 12, "retries" to 0
        )
        if(cookieFile.isFile && cookieFile.length()>0) opts["cookiefile"]=cookieFile.absolutePath
        val ydl=Python.getInstance().getModule("yt_dlp").callAttr("YoutubeDL",opts)
        try {
            val info=ydl.callAttr("extract_info",url,false)
            fun field(name:String):String=runCatching {
                info.callAttr("get",name).toString().takeUnless { it=="None" } ?: ""
            }.getOrDefault("")
            val title=field("title").take(180).ifBlank { url.substringAfterLast('/') }
            val searchable=listOf(title,field("description").take(1500),
                field("uploader"),field("artist"),field("cast"),field("tags"),field("categories"))
                .joinToString(" ").lowercase(Locale.ROOT)
            val duration=field("duration").toDoubleOrNull()?.toInt()?.takeIf { it>0 }
            VideoMetadata(title,duration,searchable,field("_type")!="playlist")
        } finally {
            runCatching { ydl.callAttr("close") }
        }
    }.getOrNull()

    private fun matchesModel(name:String, hit:VideoHit, metadata:VideoMetadata):Boolean {
        val pattern=name.lowercase(Locale.ROOT).trim().split(Regex("\\s+"))
        if(pattern.isEmpty())return false
        val words=(metadata.searchable+" "+hit.title.lowercase(Locale.ROOT))
        return pattern.all { part -> part.length>1 && words.contains(part) }
    }

    private fun onDomain(url:String, domain:String):Boolean=runCatching {
        val host=URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
        host==domain || host.endsWith(".$domain")
    }.getOrDefault(false)

    fun scan(context:Context, queue:VideoDb, watches:PerformerDb,
             log:(String)->Unit={}) {
        YtDlp.init(context.applicationContext)
        for(watch in watches.list().filter { it.enabled }) {
            val domains=watch.domains()
            if(domains.isEmpty()) {
                watches.record(watch.id,watch.cursor,"No valid selected sites")
                continue
            }
            var queued=0
            var checked=0
            var rejected=0
            val errors=ArrayList<String>()
            val maxSites=4.coerceAtMost(domains.size)
            for(delta in 0 until maxSites) {
                val site=domains[(watch.cursor+delta)%domains.size]
                log("Finding full videos: ${watch.name} on ${AdultSiteCatalog.label(site)}")
                val found=try {
                    WebDiscovery.search(AdultSiteCatalog.siteQuery(site,watch.name),8)
                        .filter { onDomain(it.url,site) }
                        .distinctBy { WebDiscovery.normalize(it.url) }
                } catch(t:Exception) {
                    errors.add("${site}: ${t.message?.take(70)}")
                    emptyList()
                }
                for(hit in found.take(3)) {
                    checked++
                    val info=probe(context,hit.url)
                    if(info==null || !info.valid) { rejected++; continue }
                    val dur=info.durationSeconds
                    // No preview or unknown-duration videos enter the automatic queue.
                    if(dur==null || dur<watch.minSeconds || excluded.containsMatchIn(info.title)) {
                        rejected++
                        continue
                    }
                    if(!matchesModel(watch.name,hit,info)) { rejected++; continue }
                    if(queue.addVideo(hit.url,"${watch.name} — ${info.title}",watch.id)) queued++
                    if(queued>=6)break
                }
                if(queued>=6)break
            }
            val next=(watch.cursor+maxSites)%domains.size
            val summary="Added $queued full videos; checked $checked; skipped $rejected"+
                (if(errors.isNotEmpty())"; site errors: ${errors.joinToString("; ").take(180)}" else "")
            watches.record(watch.id,next,summary)
            log("${watch.name}: $summary")
        }
    }
}