package com.azrael.galleryflow

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * TeraBox OFFICIAL Open Platform upload. Three-stage precreate/chunks/create.
 * Uses the registered application's OAuth access token, never site cookies.
 */
class TeraboxCloudUploader(private val context: Context) {
    private data class Domains(val api: String, val upload: String)
    private data class Parts(val hashes: List<String>, val chunkSize: Long, val bytes: Long)

    fun upload(uriText: String, remotePath: String): String {
        val token = TeraboxSettings.accessToken(context)
            ?: throw IOException("TeraBox Open Platform token is missing")
        val directory = TeraboxSettings.remoteDirectory(context).trimEnd('/')
        if (!TeraboxSettings.validDirectory(directory) ||
            !remotePath.startsWith("$directory/") ||
            remotePath.removePrefix("$directory/").contains('/')
        ) throw IOException("Cloud upload path must be in one flat app folder")

        val uri = Uri.parse(uriText)
        if (uri.scheme != "content") throw IOException("Cloud upload requires a local content URI")
        val length = context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        ).use { c -> if (c != null && c.moveToFirst()) c.getLong(0) else -1L }
        if (length <= 0L) throw IOException("Local file is missing or empty")

        val parts = fingerprintParts(uri, length)
        val jsonHashes = JSONArray(parts.hashes).toString()
        val domains = domains(token)
        val precreate = form(
            domains.api, "/openapi/api/precreate", token,
            mapOf("autoinit" to "1", "path" to remotePath, "block_list" to jsonHashes)
        )
        if (precreate.optInt("return_type") == 2) {
            val existing = precreate.optJSONObject("info")
                ?: throw IOException("TeraBox existing file lacks metadata")
            if (existing.optLong("size", -1L) != length ||
                existing.optString("path") != remotePath ||
                existing.optString("fs_id").isBlank()
            ) throw IOException("TeraBox existing cloud file does not match")
            return remotePath
        }

        val uploadId = precreate.optString("uploadid")
        if (uploadId.isBlank()) throw IOException("TeraBox precreate did not return uploadid")
        val needed = precreate.optJSONArray("block_list")
        // The official docs describe [] as equivalent to [0] for precreate.
        val requested = if (needed == null || needed.length() == 0) {
            setOf(0)
        } else {
            (0 until needed.length()).map { i ->
                needed.opt(i)?.toString()?.toIntOrNull()
                    ?: throw IOException("TeraBox returned an invalid part index")
            }.toSet()
        }
        if (requested.any { it !in parts.hashes.indices }) {
            throw IOException("TeraBox requested an out-of-range part")
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            parts.hashes.forEachIndexed { index, expectedHash ->
                if (!SyncControl.checkpoint()) throw SyncCancelledException()
                val start = index.toLong() * parts.chunkSize
                val bytes = readExact(stream, minOf(parts.chunkSize, length - start).toInt())
                if (index in requested) {
                    val actual = multipart(
                        domains.upload, "/rest/2.0/pcs/superfile2",
                        mapOf(
                            "access_tokens" to token, "method" to "upload",
                            "app_id" to "250528", "path" to remotePath,
                            "uploadid" to uploadId, "partseq" to index.toString()
                        ), bytes
                    )
                    if (!expectedHash.equals(actual.optString("md5"), ignoreCase = true)) {
                        throw IOException("TeraBox part $index checksum mismatch")
                    }
                }
            }
        } ?: throw IOException("Cannot reopen archived file for cloud upload")

        val result = form(
            domains.api, "/openapi/api/create", token,
            mapOf(
                "path" to remotePath, "size" to length.toString(),
                "uploadid" to uploadId, "block_list" to jsonHashes,
                // Fail rather than silently renaming/overwriting a collision.
                "rtype" to "0"
            )
        )
        if (result.optString("path") != remotePath ||
            result.optLong("size", -1L) != length ||
            result.optString("fs_id").isBlank()
        ) throw IOException("TeraBox did not confirm the correct uploaded file")
        return remotePath
    }

    private fun fingerprintParts(uri: Uri, length: Long): Parts {
        val target = 8L * 1024L * 1024L
        val count = ((length + target - 1) / target).toInt().coerceAtLeast(1)
        if (count > 20000) throw IOException("File exceeds supported upload size")
        val chunkSize = (length + count - 1) / count
        val hashes = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            repeat(count) { index ->
                if (!SyncControl.checkpoint()) throw SyncCancelledException()
                val readSize = minOf(chunkSize, length - index.toLong() * chunkSize).toInt()
                val bytes = readExact(stream, readSize)
                val digest = MessageDigest.getInstance("MD5").digest(bytes)
                hashes += digest.joinToString("") {
                    String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
                }
            }
        } ?: throw IOException("Cannot read local archive")
        return Parts(hashes, chunkSize, length)
    }

    private fun readExact(stream: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var position = 0
        while (position < size) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            val n = stream.read(buffer, position, size - position)
            if (n <= 0) throw IOException("Local file became truncated")
            position += n
        }
        return buffer
    }

    private fun domains(token: String): Domains {
        val result = multipart(
            "www.terabox.com", "/oauth/tokeninfo",
            emptyMap(), token.toByteArray(Charsets.UTF_8),
            fieldName = "access_token", asText = true
        )
        val info = result.optJSONObject("data")
            ?: throw IOException("TeraBox tokeninfo has no domain data")
        val api = info.optString("api_domain", "")
        val upload = info.optString("upload_domain", "")
        if (!validHost(api) || !validHost(upload)) {
            throw IOException("TeraBox returned an unsupported API domain")
        }
        return Domains(api, upload)
    }

    private fun form(
        host: String, endpoint: String, token: String, params: Map<String, String>
    ): JSONObject {
        val conn = connect(host, endpoint, mapOf("access_tokens" to token))
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val bytes = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
                .toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            readApiResult(conn)
        } finally {
            NetworkRequestRegistry.unregister(conn)
            conn.disconnect()
        }
    }

    private fun multipart(
        host: String,
        endpoint: String,
        query: Map<String, String>,
        bytes: ByteArray,
        fieldName: String = "file",
        asText: Boolean = false
    ): JSONObject {
        val conn = connect(host, endpoint, query)
        val boundary = "----KyoraOpenPlatformBoundary"
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val header = if (asText) {
                "--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"\r\n\r\n"
            } else {
                "--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"part.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"
            }
            val prefix = header.toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(prefix.size + bytes.size + suffix.size)
            conn.outputStream.use { output ->
                output.write(prefix)
                output.write(bytes)
                output.write(suffix)
            }
            readApiResult(conn)
        } finally {
            NetworkRequestRegistry.unregister(conn)
            conn.disconnect()
        }
    }

    private fun readApiResult(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("TeraBox HTTP $code")
        val json = try {
            JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) {
            throw IOException("TeraBox returned an invalid response")
        }
        val errno = json.optInt("errno", Int.MIN_VALUE)
        if (errno != 0) throw IOException("TeraBox API error $errno")
        return json
    }

    private fun connect(host: String, endpoint: String, query: Map<String, String>): HttpURLConnection {
        if (!validHost(host) || !endpoint.startsWith("/")) {
            throw IOException("TeraBox API host was not verified")
        }
        val suffix = if (query.isEmpty()) "" else "?" +
            query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val conn = URL("https://$host$endpoint$suffix").openConnection() as HttpURLConnection
        NetworkRequestRegistry.register(conn)
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 65_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Kyora/0.7 (Android)")
        return conn
    }

    private fun validHost(host: String): Boolean =
        host.matches(Regex("[A-Za-z0-9.-]+")) &&
            (host.equals("terabox.com", ignoreCase = true) ||
             host.endsWith(".terabox.com", ignoreCase = true) ||
             host.equals("terabox.app", ignoreCase = true) ||
             host.endsWith(".terabox.app", ignoreCase = true))

    private fun enc(input: String): String = URLEncoder.encode(input, "UTF-8")
}
