package com.azrael.galleryflow

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Native resolver for ordinary TeraBox public shares. It only uses the public
 * share endpoints used by the TeraBox web client. Private/password/verification
 * protected shares remain inaccessible.
 */
class TeraBoxResolver {
    data class Resolution(
        val url: String,
        val referer: String,
        val cookie: String?,
        val filename: String?
    )

    private data class Tokens(
        var jsToken: String? = null,
        var dpLogId: String? = null,
        var shareId: String? = null,
        var uk: String? = null,
        var sign: String? = null,
        var timestamp: String? = null,
        var sekey: String? = null
    )

    private data class RemoteFile(
        val name: String,
        val path: String,
        val fsId: String?,
        val size: Long,
        val isDir: Boolean,
        val dlink: String?
    )

    private class CookieJar {
        private val values = linkedMapOf<String, String>()

        fun seed(raw: String?) {
            raw.orEmpty().split(';').forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) {
                    val key = part.substring(0, i).trim()
                    val value = part.substring(i + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) values[key] = value
                }
            }
        }

        fun absorb(headers: Map<String?, List<String>>) {
            headers.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
                .forEach { line ->
                    val pair = line.substringBefore(';')
                    val i = pair.indexOf('=')
                    if (i > 0) {
                        val key = pair.substring(0, i).trim()
                        val value = pair.substring(i + 1).trim()
                        if (value.isBlank() || value.equals("deleted", true)) values.remove(key)
                        else values[key] = value
                    }
                }
        }

        fun header(): String? =
            values.entries.joinToString("; ") { it.key + "=" + it.value }.takeIf { it.isNotBlank() }

        fun set(name: String, value: String) {
            if (name.isNotBlank() && value.isNotBlank()) values[name] = value
        }
    }

    fun resolve(rawUrl: String, nameHint: String? = null): Resolution {
        val share = rawUrl.replace("&amp;", "&").trim()
        val surl = surlFrom(share)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                rawUrl,
                "TeraBox share URL could not be resolved."
            )

        var bestFailure: Throwable? = null
        for (base in apiBases(share)) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            try {
                return resolveOnBase(base, share, surl, nameHint)
            } catch (t: Throwable) {
                if (t is SyncCancelledException) throw t
                if (bestFailure == null) bestFailure = t
            }
        }

        val failure = bestFailure
        if (failure is InteractiveProviderRequiredException) throw failure
        throw InteractiveProviderRequiredException(
            "TeraBox",
            share,
            failure?.message ?: "TeraBox did not expose a downloadable file for this public share."
        )
    }

    private fun resolveOnBase(
        baseInput: String,
        shareUrl: String,
        surl: String,
        nameHint: String?
    ): Resolution {
        val jar = CookieJar()
        runCatching { CookieManager.getInstance().getCookie(baseInput) }.getOrNull()?.let(jar::seed)
        val tokens = Tokens()

        var base = baseInput.trimEnd('/')
        runCatching { requestText(base + "/main", jar, base + "/") }.getOrNull()?.let { main ->
            mergeTokens(tokens, extractTokens(main.body))
            originOf(main.finalUrl)?.let { base = it }
        }

        var pageUrl = base + "/sharing/link?surl=" + enc(surl)
        val page = requestText(pageUrl, jar, shareUrl)
        pageUrl = page.finalUrl
        originOf(page.finalUrl)?.let { base = it }
        mergeTokens(tokens, extractTokens(page.body))

        if (tokens.jsToken.isNullOrBlank()) {
            runCatching {
                requestText(base + "/sharing/embed?surl=" + enc(surl), jar, pageUrl)
            }.getOrNull()?.let { mergeTokens(tokens, extractTokens(it.body)) }
        }

        val init = initShare(base, surl, tokens, jar, pageUrl)
        mergeMeta(tokens, init)

        var rootEntries = entries(init)
        if (rootEntries.isEmpty()) {
            rootEntries = listDirectory(base, surl, null, tokens, jar, pageUrl, signed = true)
        }
        if (rootEntries.isEmpty()) {
            rootEntries = listDirectory(base, surl, null, tokens, jar, pageUrl, signed = false)
        }
        if (rootEntries.isEmpty()) {
            throw InteractiveProviderRequiredException(
                "TeraBox",
                pageUrl,
                "TeraBox returned an empty or inaccessible share."
            )
        }

        val files = collectFiles(base, surl, rootEntries, tokens, jar, pageUrl)
        val chosen = chooseFile(files, nameHint)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                pageUrl,
                "TeraBox share contains no downloadable archive/media file."
            )

        val direct = chosen.dlink?.takeIf { it.startsWith("http") }
            ?: acquireDlink(base, chosen, tokens, jar, pageUrl)
            ?: throw InteractiveProviderRequiredException(
                "TeraBox",
                pageUrl,
                "TeraBox listed the file but withheld its download link."
            )

        jar.header()?.let { raw ->
            runCatching {
                val cm = CookieManager.getInstance()
                raw.split(';').forEach { pair -> cm.setCookie(pageUrl, pair.trim()) }
                cm.flush()
            }
        }

        return Resolution(
            direct.replace("&amp;", "&"),
            pageUrl,
            jar.header(),
            chosen.name
        )
    }

    private fun initShare(
        base: String,
        surl: String,
        tokens: Tokens,
        jar: CookieJar,
        referer: String
    ): JSONObject {
        fun call(short: String): JSONObject {
            val params = linkedMapOf(
                "app_id" to APP_ID,
                "web" to "1",
                "channel" to "dubox",
                "clienttype" to "0",
                "root" to "1",
                "scene" to "",
                "shorturl" to short
            )
            tokens.jsToken?.let { params["jsToken"] = it }
            tokens.dpLogId?.let { params["dp-logid"] = it }
            return requestJson(base + "/api/shorturlinfo?" + query(params), jar, referer)
        }

        var result = call("1" + surl)
        if (errno(result) != 0 && errno(result) !in VERIFY_CODES) {
            val bare = runCatching { call(surl) }.getOrNull()
            if (bare != null && errno(bare) == 0) result = bare
        }
        checkApi(result, referer)
        return result
    }

    private fun listDirectory(
        base: String,
        surl: String,
        dir: String?,
        tokens: Tokens,
        jar: CookieJar,
        referer: String,
        signed: Boolean
    ): List<JSONObject> {
        val params = linkedMapOf(
            "app_id" to APP_ID,
            "web" to "1",
            "channel" to "dubox",
            "clienttype" to "0",
            "page" to "1",
            "num" to "100",
            "by" to "name",
            "order" to "asc",
            "shorturl" to surl
        )
        if (dir == null) params["root"] = "1" else params["dir"] = dir

        if (signed) {
            tokens.shareId?.let { params["shareid"] = it }
            tokens.uk?.let { params["uk"] = it }
            tokens.sign?.let { params["sign"] = it }
            tokens.timestamp?.let { params["timestamp"] = it }
            tokens.sekey?.let {
                params["sekey"] = it
                jar.set("TSID", it)
            }
            tokens.jsToken?.let { params["jsToken"] = it }
            tokens.dpLogId?.let { params["dp-logid"] = it }
        }

        val body = requestJson(base + "/share/list?" + query(params), jar, referer)
        if (errno(body) != 0) return emptyList()
        mergeMeta(tokens, body)
        return entries(body)
    }

    private fun collectFiles(
        base: String,
        surl: String,
        root: List<JSONObject>,
        tokens: Tokens,
        jar: CookieJar,
        referer: String
    ): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        val queue = ArrayDeque<Pair<List<JSONObject>, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && out.size < 300) {
            val (batch, depth) = queue.removeFirst()
            for (obj in batch) {
                if (out.size >= 300) break
                val file = toFile(obj) ?: continue

                if (file.isDir) {
                    if (depth < 3) {
                        var children = runCatching {
                            listDirectory(base, surl, file.path, tokens, jar, referer, true)
                        }.getOrDefault(emptyList())
                        if (children.isEmpty()) {
                            children = runCatching {
                                listDirectory(base, surl, file.path, tokens, jar, referer, false)
                            }.getOrDefault(emptyList())
                        }
                        if (children.isNotEmpty()) queue.add(children to (depth + 1))
                    }
                } else {
                    out += file
                }
            }
        }
        return out
    }

    private fun chooseFile(files: List<RemoteFile>, hint: String?): RemoteFile? {
        if (files.isEmpty()) return null
        val hintTokens = tokensForName(hint.orEmpty())
        return files.maxWithOrNull(
            compareBy<RemoteFile> { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val typeScore = when (ext) {
                    "zip", "rar", "7z" -> 10_000
                    "mp4", "webm", "mov", "mkv" -> 5_000
                    else -> 0
                }
                val overlap = if (hintTokens.isEmpty()) 0 else {
                    val fileTokens = tokensForName(file.name)
                    hintTokens.count { it in fileTokens } * 100
                }
                typeScore + overlap
            }.thenBy { it.size }
        )
    }

    private fun acquireDlink(
        base: String,
        file: RemoteFile,
        tokens: Tokens,
        jar: CookieJar,
        referer: String
    ): String? {
        val fsId = file.fsId ?: return null
        val shareId = tokens.shareId ?: return null
        val uk = tokens.uk ?: return null
        val sign = tokens.sign ?: return null
        val timestamp = tokens.timestamp ?: return null

        val params = linkedMapOf(
            "app_id" to APP_ID,
            "web" to "1",
            "channel" to "dubox",
            "clienttype" to "0",
            "uk" to uk,
            "sign" to sign,
            "timestamp" to timestamp,
            "shareid" to shareId,
            "primaryid" to shareId,
            "product" to "share",
            "nozip" to "0",
            "fid_list" to "[" + fsId + "]"
        )
        tokens.jsToken?.let { params["jsToken"] = it }
        tokens.sekey?.let { params["sekey"] = it }

        fun extract(body: JSONObject): String? {
            body.optString("dlink").takeIf { it.startsWith("http") }?.let { return it }
            val list = body.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.optString("dlink")
                        ?.takeIf { it.startsWith("http") }
                        ?.let { return it }
                }
            }
            return null
        }

        runCatching {
            val body = requestJson(base + "/share/download?" + query(params), jar, referer)
            if (errno(body) == 0) extract(body) else null
        }.getOrNull()?.let { return it }

        val host = URI(base).host?.removePrefix("www.")
        for (candidate in listOfNotNull("data.terabox.com", host?.let { "data." + it }).distinct()) {
            runCatching {
                val rest = params.toMutableMap()
                rest["method"] = "locatedownload"
                val body = requestJson(
                    "https://" + candidate + "/rest/2.0/share/download?" + query(rest),
                    jar,
                    referer
                )
                if (errno(body) == 0) extract(body) else null
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun extractTokens(html: String): Tokens {
        val decoded = html
            .replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")

        fun first(vararg regexes: Regex): String? =
            regexes.firstNotNullOfOrNull {
                it.find(decoded)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            }

        val jsRaw = first(
            Regex("""(?i)"jsToken"\s*:\s*"function%20fn%28a%29%7Bwindow\.jsToken%20%3D%20a%7D%3Bfn%28%22([^"\\]+)%22%29"""),
            Regex("""fn\("%28%22([A-Za-z0-9_%+/\-]{16,})%22%29"\)"""),
            Regex("""fn%28%22([A-Za-z0-9_%+/\-]{16,})%22%29"""),
            Regex("""window\.jsToken\s*[:=]\s*["']([^"']{8,})"""),
            Regex("""(?i)jsToken["'\s:=]+([A-Za-z0-9_+\-]{8,})""")
        )

        val js = jsRaw
            ?.let(::decode)
            ?.trim('"', '\'', ' ')
            ?.takeIf { it.length >= 8 }

        return Tokens(
            jsToken = js,
            dpLogId = first(
                Regex("""dp-logid[^0-9]{0,8}(\d{6,})"""),
                Regex("""(?i)logid["'\s:=]+(\d{6,})""")
            ),
            shareId = first(Regex("""(?i)["']?share_?id["']?\s*[:=]\s*["']?(\d{4,})""")),
            uk = first(Regex("""(?i)["']?uk["']?\s*[:=]\s*["']?(\d{4,})""")),
            sign = first(Regex("""(?i)["']?sign["']?\s*[:=]\s*["']([A-Za-z0-9+/=_\-]{4,})""")),
            timestamp = first(Regex("""(?i)["']?timestamp["']?\s*[:=]\s*["']?(\d{4,})""")),
            sekey = first(Regex("""(?i)["']?randsk["']?\s*[:=]\s*["']([^"']{2,})"""))?.let(::decode)
        )
    }

    private fun mergeTokens(target: Tokens, extra: Tokens) {
        if (!extra.jsToken.isNullOrBlank()) target.jsToken = extra.jsToken
        if (!extra.dpLogId.isNullOrBlank()) target.dpLogId = extra.dpLogId
        if (!extra.shareId.isNullOrBlank()) target.shareId = extra.shareId
        if (!extra.uk.isNullOrBlank()) target.uk = extra.uk
        if (!extra.sign.isNullOrBlank()) target.sign = extra.sign
        if (!extra.timestamp.isNullOrBlank()) target.timestamp = extra.timestamp
        if (!extra.sekey.isNullOrBlank()) target.sekey = extra.sekey
    }

    private fun mergeMeta(tokens: Tokens, body: JSONObject) {
        fun read(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                body.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            }

        read("shareid", "shareId")?.let { tokens.shareId = it }
        read("uk")?.let { tokens.uk = it }
        read("sign")?.let { tokens.sign = it }
        read("timestamp")?.let { tokens.timestamp = it }
        read("randsk")?.let { tokens.sekey = decode(it) }
    }

    private fun entries(body: JSONObject): List<JSONObject> {
        val array = body.optJSONArray("list") ?: body.optJSONArray("file_list") ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private fun toFile(obj: JSONObject): RemoteFile? {
        val name = obj.optString("server_filename").ifBlank { obj.optString("filename") }.trim()
        if (name.isBlank()) return null

        return RemoteFile(
            name = name,
            path = obj.optString("path").ifBlank { "/" + name },
            fsId = obj.opt("fs_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            size = obj.optLong("size", 0L),
            isDir = obj.optInt("isdir", 0) == 1,
            dlink = obj.optString("dlink")
                .replace("&amp;", "&")
                .takeIf { it.startsWith("http") }
        )
    }

    private fun checkApi(body: JSONObject, providerUrl: String) {
        val code = errno(body)
        if (code == 0) return

        val message = body.optString("errmsg").ifBlank { body.optString("msg") }
        when (code) {
            -9 -> throw InteractiveProviderRequiredException(
                "TeraBox",
                providerUrl,
                "TeraBox share is password-protected."
            )
            -10, -62, 115, 130 -> throw InteractiveProviderRequiredException(
                "TeraBox",
                providerUrl,
                "TeraBox share is deleted, private, or expired."
            )
            else -> {
                val suffix = if (message.isNotBlank()) ": " + message else ""
                throw InteractiveProviderRequiredException(
                    "TeraBox",
                    providerUrl,
                    "TeraBox refused the public share request (errno " + code + suffix + ")."
                )
            }
        }
    }

    private fun errno(body: JSONObject): Int {
        val raw = if (body.has("errno")) body.opt("errno") else body.opt("code")
        return raw?.toString()?.toIntOrNull() ?: 0
    }

    private data class TextResponse(val body: String, val finalUrl: String)

    private fun requestText(url: String, jar: CookieJar, referer: String?): TextResponse {
        val conn = openConnection(
            url,
            jar,
            referer,
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        try {
            val code = conn.responseCode
            jar.absorb(conn.headerFields)
            if (code !in 200..299) throw HttpStatusException(code, "HTTP " + code + " for " + url)
            return TextResponse(
                conn.inputStream.bufferedReader().use { it.readText() },
                conn.url.toString()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun requestJson(url: String, jar: CookieJar, referer: String?): JSONObject {
        val conn = openConnection(url, jar, referer, "application/json,text/plain,*/*")
        try {
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            val code = conn.responseCode
            jar.absorb(conn.headerFields)
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw HttpStatusException(code, "Empty TeraBox response")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        jar: CookieJar,
        referer: String?,
        accept: String
    ): HttpURLConnection {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cache-Control", "no-cache")
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            jar.header()?.let { setRequestProperty("Cookie", it) }
        }
    }

    private fun surlFrom(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null

        Regex("""(?:^|&)surl=([^&]+)""")
            .find(uri.rawQuery.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return cleanSurl(decode(it)) }

        Regex("""/s/([A-Za-z0-9_\-]+)""")
            .find(uri.path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return cleanSurl(it) }

        return null
    }

    private fun cleanSurl(raw: String): String {
        val value = raw.trim().trimEnd('/', '?', '#')
        return if (value.length > 8 && value.startsWith("1")) value.drop(1) else value
    }

    private fun apiBases(raw: String): List<String> =
        listOfNotNull(
            originOf(raw),
            "https://www.terabox.com",
            "https://www.terabox.app",
            "https://www.1024terabox.com",
            "https://www.teraboxshare.com",
            "https://www.4funbox.com"
        ).distinct()

    private fun originOf(raw: String): String? = runCatching {
        val u = URI(raw)
        if (u.scheme == null || u.host == null) null
        else URI(u.scheme, null, u.host, u.port, null, null, null).toString().trimEnd('/')
    }.getOrNull()

    private fun tokensForName(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""\[[^]]*]""")) { " " + it.value.trim('[', ']') + " " }
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun query(values: Map<String, String>): String =
        values.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)

    companion object {
        private const val APP_ID = "250528"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private val VERIFY_CODES = setOf(-6, 4000020, 400141, 400210, 460020)
        private val STOP_WORDS = setOf(
            "the", "and", "with", "cosplay", "photos", "photo",
            "videos", "video", "part", "vol", "download", "free"
        )
    }
}
