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

/** Onion access always uses a local Tor SOCKS service. Never fall back to direct access. */
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
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),1200) }
            true
        }.getOrDefault(false)
    }
    fun check(options:TorOptions) {
        require(options.enabled){"Tor discovery disabled. Enable Tor in Discover settings."}
        require(options.validPort()){"Invalid local SOCKS port"}
        require(portReachable(options.socksPort)) {
            "Tor SOCKS not listening at 127.0.0.1:"+options.socksPort+". Start Orbot and check its SOCKS port."
        }
    }
    fun fetch(address:String, options:TorOptions):Document {
        check(options)
        var current=address
        repeat(4) {
            val uri=URI(current)
            require(uri.scheme=="https"||uri.scheme=="http"){"Only HTTP(S) Tor links are supported"}
            if(isOnion(current))require(isV3Onion(current)){"Only 56-character Tor v3 onion addresses are supported"}
            val proxy=Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",options.socksPort))
            val response=Jsoup.connect(current)
                .proxy(proxy).followRedirects(false)
                .userAgent("Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                .timeout(18000).maxBodySize(1800_000).execute()
            if(response.statusCode() in listOf(301,302,303,307,308)) {
                val target=response.header("Location")?:error("Tor redirect without destination")
                current=URI(current).resolve(target).toASCIIString()
            } else {
                return response.parse()
            }
        }
        error("Excessive Tor redirects")
    }
}
