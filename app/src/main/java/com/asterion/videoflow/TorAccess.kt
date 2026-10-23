package com.asterion.videoflow

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Locale

data class TorOptions(val enabled:Boolean=false,val socksPort:Int=9050) {
    fun validPort():Boolean=socksPort in 1..65535
    val proxyUrl:String get()="socks5h://127.0.0.1:"+socksPort
}
data class TorConnectionReport(
    val verified:Boolean,
    val detectedPort:Int?,
    val code:String,
    val message:String
)

/** Tor access is explicit, local-proxy only and fails closed for .onion services.
 * Network diagnostics must run on a worker thread, not Android main thread. */
object TorAccess {
    private val onionV3=Regex("^[a-z2-7]{56}\\.onion$",RegexOption.IGNORE_CASE)
    fun isOnion(url:String):Boolean {
        val host=runCatching{URI(url).host?.lowercase(Locale.ROOT)}.getOrNull()?:return false
        return host.endsWith(".onion")
    }
    fun isV3Onion(url:String):Boolean {
        val host=runCatching{URI(url).host}.getOrNull()?:return false
        return onionV3.matches(host)
    }
    fun settings(context:Context):TorOptions {
        val pref=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        return TorOptions(pref.getBoolean("tor_enabled",false),
            pref.getInt("tor_socks_port",9050))
    }
    fun portReachable(port:Int):Boolean {
        if(port !in 1..65535)return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),1600) }
            true
        }.getOrDefault(false)
    }
    fun check(options:TorOptions) {
        require(options.enabled){"Tor is disabled. Enable it in Discover."}
        require(options.validPort()){"Invalid SOCKS port"}
        require(portReachable(options.socksPort)) {
            "No local SOCKS proxy on 127.0.0.1:"+options.socksPort+
            ". In Orbot, enable Power User Mode, connect, and check the SOCKS port in its settings."
        }
    }
    private fun proxy(port:Int)=Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))
    /** Actually attempts Tor's verified-network endpoint over SOCKS5.
     * It reports "verified" only when the Tor Project endpoint explicitly returns IsTor=true. */
    fun diagnose(preferredPort:Int):TorConnectionReport {
        val candidates=listOf(preferredPort,9050,9150).distinct().filter{it in 1..65535}
        val active=candidates.filter{portReachable(it)}
        if(active.isEmpty())return TorConnectionReport(
            false,null,"NO_PROXY",
            "No Orbot SOCKS proxy found on "+candidates.joinToString(", ")+
            ". Start Orbot, turn on Power User Mode, connect to Tor, then retry. "+
            "Check that SOCKS is enabled in Orbot settings."
        )
        val notes=ArrayList<String>()
        for(port in active) {
            try {
                val body=Jsoup.connect("https://check.torproject.org/api/ip")
                    .proxy(proxy(port)).followRedirects(false).timeout(16000)
                    .ignoreContentType(true).maxBodySize(100000).execute().body()
                if(Regex(""""IsTor"\s*:\s*true""",RegexOption.IGNORE_CASE).containsMatchIn(body)){
                    return TorConnectionReport(true,port,"CONNECTED",
                        "Tor circuit confirmed on local SOCKS port "+port+". Onion searches are available.")
                }
                if(Regex(""""IsTor"\s*:\s*false""",RegexOption.IGNORE_CASE).containsMatchIn(body)){
                    notes.add("Port "+port+" responds, but Tor Project says this connection is NOT using Tor.")
                } else notes.add("Port "+port+" responds, but Tor verification returned no confirmation.")
            } catch(t:Exception) {
                notes.add("Port "+port+" is open, but Tor verification failed: "+
                    (t.message?.take(130)?:t.javaClass.simpleName))
            }
        }
        return TorConnectionReport(false,active.firstOrNull(),"UNVERIFIED",
            notes.joinToString(" ")+" In Orbot, check that Tor says Connected; retry or review bridges/network settings.")
    }
    fun fetch(address:String, options:TorOptions):Document {
        check(options)
        var current=address
        repeat(4) {
            val uri=URI(current)
            require(uri.scheme=="https"||uri.scheme=="http"){"Only HTTP(S) Tor links are supported"}
            if(isOnion(current))require(isV3Onion(current)){"Only 56-character Tor v3 onion addresses are supported"}
            val response=Jsoup.connect(current)
                .proxy(proxy(options.socksPort)).followRedirects(false)
                .userAgent("Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                .timeout(18000).maxBodySize(1800000).execute()
            if(response.statusCode() in listOf(301,302,303,307,308)) {
                val target=response.header("Location")?:error("Tor redirect without destination")
                current=URI(current).resolve(target).toASCIIString()
            } else return response.parse()
        }
        error("Excessive Tor redirects")
    }
}
