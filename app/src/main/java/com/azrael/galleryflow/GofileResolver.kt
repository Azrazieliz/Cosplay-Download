package com.azrael.galleryflow

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves public Gofile folders using Gofile's guest-account API. It does not
 * access private folders or bypass passwords.
 */
class GofileResolver {
    data class Resolution(
        val url: String,
        val referer: String,
        val cookie: String?,
        val filename: String
    )

    private data class RemoteFile(
        val name: String,
        val url: String,
        val size: Long
    )

    fun resolve(rawUrl: String, nameHint: String?): Resolution {
        val id = contentId(rawUrl)
            ?: throw InteractiveProviderRequiredException("Gofile", rawUrl, "Invalid Gofile folder URL.")

        val token = createGuestAccount()
        val files = mutableListOf<RemoteFile>()
        collect(id, token, files, depth = 0)

        if (files.isEmpty()) {
            throw InteractiveProviderRequiredException("Gofile", rawUrl, "Gofile folder contains no downloadable files.")
        }

        val chosen = chooseFile(files, nameHint)
            ?: throw InteractiveProviderRequiredException(
                "Gofile",
                rawUrl,
                "Gofile folder did not contain a file matching this gallery."
            )

        val cookie = "accountToken=" + token
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setCookie("https://gofile.io/", cookie + "; Domain=.gofile.io; Path=/")
            cm.setCookie(chosen.url, cookie + "; Domain=.gofile.io; Path=/")
            cm.flush()
        }
        return Resolution(chosen.url, rawUrl, cookie, chosen.name)
    }

    private fun createGuestAccount(): String {
        val conn = URL(API + "/accounts").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Origin", "https://gofile.io")
            conn.setRequestProperty("Referer", "https://gofile.io/")
            conn.doOutput = true
            conn.outputStream.use { }
            val text = responseText(conn)
            val root = JSONObject(text)
            if (root.optString("status") != "ok") {
                throw AdapterException("Gofile guest session failed: " + root.optString("status"))
            }
            return root.optJSONObject("data")?.optString("token")
                ?.takeIf { it.isNotBlank() }
                ?: throw AdapterException("Gofile returned no guest token.")
        } finally {
            conn.disconnect()
        }
    }

    private fun collect(id: String, token: String, out: MutableList<RemoteFile>, depth: Int) {
        if (depth > 4 || out.size >= 500 || !SyncControl.checkpoint()) return

        val params = linkedMapOf(
            "contentFilter" to "",
            "page" to "1",
            "pageSize" to "1000",
            "sortField" to "name",
            "sortDirection" to "1"
        )
        val url = API + "/contents/" + enc(id) + "?" + query(params)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + token)
            conn.setRequestProperty("X-Website-Token", websiteToken(token))
            conn.setRequestProperty("X-BL", "en-US")
            conn.setRequestProperty("Origin", "https://gofile.io")
            conn.setRequestProperty("Referer", "https://gofile.io/")

            val root = JSONObject(responseText(conn))
            val status = root.optString("status")
            if (status == "error-passwordRequired") {
                throw InteractiveProviderRequiredException("Gofile", "https://gofile.io/d/" + id, "Gofile folder is password-protected.")
            }
            if (status != "ok") {
                throw AdapterException("Gofile API returned " + status)
            }

            val data = root.optJSONObject("data") ?: return
            val children = data.optJSONObject("children") ?: return
            val keys = children.keys()
            while (keys.hasNext() && out.size < 500) {
                val child = children.optJSONObject(keys.next()) ?: continue
                when (child.optString("type")) {
                    "file" -> {
                        val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                        val name = child.optString("name").ifBlank { URI(link).path.substringAfterLast('/') }
                        out += RemoteFile(name, link, child.optLong("size", 0L))
                    }
                    "folder" -> {
                        val childId = child.optString("id").takeIf { it.isNotBlank() } ?: continue
                        collect(childId, token, out, depth + 1)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun chooseFile(files: List<RemoteFile>, nameHint: String?): RemoteFile? {
        val archives = files.filter { extension(it.name) in ARCHIVE_EXTENSIONS }
        val pool = if (archives.isNotEmpty()) archives else files
        if (pool.size == 1) return pool.first()

        val hintTokens = tokens(nameHint.orEmpty())
        if (hintTokens.isEmpty()) return pool.maxByOrNull { it.size }

        val scored = pool.map { file ->
            val ft = tokens(file.name)
            val overlap = hintTokens.count { it in ft }
            val initialsBonus = bracketInitials(nameHint.orEmpty())
                ?.let { initials -> if (file.name.trim().startsWith(initials, ignoreCase = true)) 3 else 0 }
                ?: 0
            Triple(file, overlap + initialsBonus, file.size)
        }.sortedWith(compareByDescending<Triple<RemoteFile, Int, Long>> { it.second }.thenByDescending { it.third })

        val best = scored.firstOrNull() ?: return null
        // In shared date folders, never silently select an unrelated archive.
        return if (best.second >= 2) best.first else null
    }

    private fun websiteToken(accountToken: String): String {
        val epochBucket = System.currentTimeMillis() / 1000L / 14400L
        val material = USER_AGENT + "::en-US::" + accountToken + "::" + epochBucket + "::5d4f7g8sd45fsd"
        return sha256(material)
    }

    private fun contentId(raw: String): String? =
        Regex("""/d/([^/?#]+)""").find(runCatching { URI(raw).path }.getOrDefault(""))?.groupValues?.getOrNull(1)

    private fun bracketInitials(value: String): String? =
        Regex("""\[([A-Za-z0-9]{1,8})]""").find(value)?.groupValues?.getOrNull(1)

    private fun extension(value: String): String =
        value.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun tokens(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""\[[^]]*]""")) { " " + it.value.trim('[', ']') + " " }
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun responseText(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpStatusException(code, "Gofile HTTP " + code)
        return text
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun query(values: Map<String, String>): String =
        values.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val API = "https://api.gofile.io"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")
        private val STOP_WORDS = setOf(
            "the", "and", "with", "cosplay", "photos", "photo", "videos", "video",
            "part", "vol", "download", "free", "honkai", "impact", "rail", "star"
        )
    }
}
