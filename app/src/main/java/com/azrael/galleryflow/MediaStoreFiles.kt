package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class MediaStoreFiles(private val context: Context, private val http: HttpClient) {
    fun exists(contentUri: String?): Boolean {
        if (contentUri.isNullOrBlank()) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun download(entity: EntityRecord, gallery: GalleryMeta, media: MediaRef): DownloadOutcome {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        val resolved = http.resolveDownloadUrl(media.url, media.referer, media.provider)
        if (media.kind == MediaKind.VIDEO && isHls(resolved, media.mimeHint)) {
            return downloadHls(entity, gallery, media, resolved)
        }
        val resolvedReferer = when (media.provider?.lowercase()) {
            "terabox" -> "https://www.terabox.com/"
            "mediafire", "sorafolder", "gofile" -> media.url
            else -> media.referer
        }
        openWithFallback(media, resolved, resolvedReferer).use { response ->
            val responseMime = response.contentType?.lowercase()
            if (media.kind == MediaKind.ARCHIVE && responseMime?.startsWith("text/html") == true) {
                throw InteractiveProviderRequiredException(media.provider ?: "Archive provider", response.finalUrl)
            }
            if (media.kind == MediaKind.IMAGE && responseMime?.startsWith("text/") == true) {
                throw AdapterException("Image URL returned HTML/text instead of an image.")
            }
            if (media.kind == MediaKind.VIDEO && responseMime?.startsWith("text/") == true) {
                throw AdapterException("Video URL returned HTML/text instead of a video.")
            }

            val dispositionName = filenameFromDisposition(response.contentDisposition)
            val mime = chooseMime(media, response.contentType, dispositionName, response.finalUrl)
            val ext = extensionFor(mime, dispositionName ?: response.finalUrl, media.kind)
            val filename = when (media.kind) {
                MediaKind.ARCHIVE -> dispositionName?.let(::safeFileName)
                    ?: "archive-%04d.%s".format(media.index + 1, ext)
                else -> "%04d.%s".format(media.index + 1, ext)
            }

            val uri = createRow(filename, mime, relativePath(entity, gallery))
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        if (!SyncControl.checkpoint()) throw SyncCancelledException()
                        val read = response.input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytes += read
                    }
                }
                finishRow(uri)
                val primary = DownloadResult(
                    contentUri = uri.toString(),
                    filename = filename,
                    mimeType = mime,
                    bytes = bytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                    aHash64 = if (mime.startsWith("image/")) averageHash64(uri) else null
                )
                val extracted = when {
                    media.kind != MediaKind.ARCHIVE -> emptyList()
                    isZip(filename, mime) -> extractZip(entity, gallery, media, uri)
                    isRar(filename, mime) -> extractRar(entity, gallery, media, uri)
                    media.archiveVideosOnly ->
                        throw AdapterException("Supplemental video archive format is not supported: $filename")
                    else -> emptyList()
                }
                return DownloadOutcome(primary, extracted)
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }
    }

    private data class HlsKey(val url: String, val ivHex: String?)
    private data class HlsSegment(val url: String, val key: HlsKey?, val sequence: Long)
    private data class HlsMediaPlaylist(
        val playlistUrl: String,
        val initUrl: String?,
        val segments: List<HlsSegment>
    )

    private fun isHls(url: String, mimeHint: String?): Boolean =
        url.substringBefore('?').endsWith(".m3u8", true) ||
            mimeHint.equals("application/vnd.apple.mpegurl", true) ||
            mimeHint.equals("application/x-mpegURL", true)

    private fun downloadHls(
        entity: EntityRecord,
        gallery: GalleryMeta,
        media: MediaRef,
        playlistUrl: String
    ): DownloadOutcome {
        val playlist = resolveHlsPlaylist(playlistUrl, media.referer, 0)
        if (playlist.segments.isEmpty()) throw AdapterException("HLS playlist contains no video segments.")

        val fmp4 = playlist.initUrl != null || playlist.segments.any {
            val path = runCatching { URI(it.url).path.lowercase() }.getOrDefault("")
            path.endsWith(".m4s") || path.endsWith(".mp4")
        }
        val mime = if (fmp4) "video/mp4" else "video/mp2t"
        val ext = if (fmp4) "mp4" else "ts"
        val filename = "%04d.%s".format(media.index + 1, ext)
        val uri = createRow(filename, mime, relativePath(entity, gallery))
        val digest = MessageDigest.getInstance("SHA-256")
        val keyCache = mutableMapOf<String, ByteArray>()
        var bytes = 0L

        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                playlist.initUrl?.let { initUrl ->
                    val init = readRemoteBytes(initUrl, playlist.playlistUrl)
                    output.write(init)
                    digest.update(init)
                    bytes += init.size
                }

                for (segment in playlist.segments) {
                    if (!SyncControl.checkpoint()) throw SyncCancelledException()
                    val raw = readRemoteBytes(segment.url, playlist.playlistUrl)
                    val data = segment.key?.let { key ->
                        val keyBytes = keyCache.getOrPut(key.url) {
                            readRemoteBytes(key.url, playlist.playlistUrl)
                        }
                        decryptHlsAes128(raw, keyBytes, key.ivHex, segment.sequence)
                    } ?: raw
                    output.write(data)
                    digest.update(data)
                    bytes += data.size
                }
            }
            finishRow(uri)
            val result = DownloadResult(
                contentUri = uri.toString(),
                filename = filename,
                mimeType = mime,
                bytes = bytes,
                sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                aHash64 = null
            )
            return DownloadOutcome(result)
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun resolveHlsPlaylist(url: String, referer: String?, depth: Int): HlsMediaPlaylist {
        if (depth > 4) throw AdapterException("Too many nested HLS playlists.")
        val response = http.textResponse(
            url,
            referer,
            "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*"
        )
        val lines = response.body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

        val variants = mutableListOf<Pair<Long, String>>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) continue
            val bandwidth = Regex("""(?i)BANDWIDTH=(\d+)""")
                .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: continue
            variants += bandwidth to URI(response.finalUrl).resolve(next).toString()
        }
        if (variants.isNotEmpty()) {
            val best = variants.maxByOrNull { it.first }!!.second
            return resolveHlsPlaylist(best, response.finalUrl, depth + 1)
        }

        var sequence = Regex("""(?m)^#EXT-X-MEDIA-SEQUENCE:(\d+)""")
            .find(response.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        var currentKey: HlsKey? = null
        var initUrl: String? = null
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:", true) -> {
                    val raw = Regex("""(?i)URI=["']([^"']+)["']""")
                        .find(line)?.groupValues?.getOrNull(1)
                    if (!raw.isNullOrBlank()) initUrl = URI(response.finalUrl).resolve(raw).toString()
                }
                line.startsWith("#EXT-X-KEY:", true) -> {
                    val method = Regex("""(?i)METHOD=([^,]+)""")
                        .find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    currentKey = when {
                        method.equals("NONE", true) -> null
                        method.equals("AES-128", true) -> {
                            val raw = Regex("""(?i)URI=["']([^"']+)["']""")
                                .find(line)?.groupValues?.getOrNull(1)
                                ?: throw AdapterException("Encrypted HLS key URL is missing.")
                            val iv = Regex("""(?i)(?:^|,)IV=([^,]+)""")
                                .find(line.substringAfter(':'))?.groupValues?.getOrNull(1)?.trim()
                            HlsKey(URI(response.finalUrl).resolve(raw).toString(), iv)
                        }
                        else -> throw AdapterException("Unsupported HLS encryption method: $method")
                    }
                }
                !line.startsWith("#") -> {
                    segments += HlsSegment(
                        URI(response.finalUrl).resolve(line).toString(),
                        currentKey,
                        sequence++
                    )
                }
            }
        }
        return HlsMediaPlaylist(response.finalUrl, initUrl, segments)
    }

    private fun readRemoteBytes(url: String, referer: String?): ByteArray =
        http.open(url, referer).use { response ->
            response.input.readBytes()
        }

    private fun decryptHlsAes128(
        encrypted: ByteArray,
        key: ByteArray,
        ivHex: String?,
        sequence: Long
    ): ByteArray {
        if (key.size != 16) throw AdapterException("Invalid HLS AES-128 key length: ${key.size}")
        val iv = if (!ivHex.isNullOrBlank()) {
            val clean = ivHex.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
            ByteArray(16) { index ->
                clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } else {
            ByteArray(16).also { out ->
                var value = sequence
                for (i in 15 downTo 8) {
                    out[i] = (value and 0xff).toByte()
                    value = value ushr 8
                }
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    private fun openWithFallback(
        media: MediaRef,
        resolved: String,
        preferredReferer: String?
    ): HttpClient.OpenResponse {
        val referers = linkedSetOf<String?>()
        val host = runCatching { URI(resolved).host?.lowercase().orEmpty() }.getOrDefault("")

        // Kiutaku currently serves its actual image files from mitaku.net. The working
        // browser/userscript path uses mitaku.net itself as the Referer, not kiutaku.com.
        if (host == "mitaku.net" || host.endsWith(".mitaku.net")) {
            referers += "https://mitaku.net/"
        }

        referers += preferredReferer

        if (media.kind == MediaKind.IMAGE || media.kind == MediaKind.VIDEO) {
            val root = runCatching {
                val uri = URI(resolved)
                uri.scheme + "://" + uri.host + "/"
            }.getOrNull()
            referers += root
            referers += null
        }

        var last: Throwable? = null
        for (referer in referers) {
            try {
                return http.open(resolved, referer)
            } catch (e: HttpStatusException) {
                last = e
                // Some image CDNs intentionally return 404/406/410 for a rejected
                // hotlink Referer, so try the remaining browser-compatible Referers.
                // 429 is the only response where immediately trying again is harmful.
                if (e.code == 429) throw e
            } catch (t: Throwable) {
                last = t
                // Network/TLS failures can be host-path specific; try the next referer
                // before giving up.
            }
        }
        throw last ?: AdapterException("Could not open media URL.")
    }

    fun delete(contentUri: String?) {
        if (!contentUri.isNullOrBlank()) runCatching {
            context.contentResolver.delete(Uri.parse(contentUri), null, null)
        }
    }

    private fun extractZip(
        entity: EntityRecord,
        gallery: GalleryMeta,
        archive: MediaRef,
        archiveUri: Uri
    ): List<ExtractedDownload> {
        val temp = File.createTempFile("galleryflow_", ".zip", context.cacheDir)
        val createdUris = mutableListOf<Uri>()
        try {
            context.contentResolver.openInputStream(archiveUri)!!.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            val zip = ZipFile(temp)
            if (zip.isEncrypted) {
                val password = archive.archivePassword?.takeIf { it.isNotBlank() }
                    ?: throw AdapterException("Encrypted ZIP requires an extraction password.")
                zip.setPassword(password.toCharArray())
            }

            val out = mutableListOf<ExtractedDownload>()
            var extractedIndex = 0
            for (header in zip.fileHeaders) {
                if (!SyncControl.checkpoint()) throw SyncCancelledException()
                if (header.isDirectory) continue
                val entryName = header.fileName.replace('\\', '/')
                val ext = entryName.substringAfterLast('.', "").lowercase()
                val mime = mimeForExtension(ext) ?: continue
                val kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE
                if (archive.archiveVideosOnly && kind != MediaKind.VIDEO) continue
                val base = safeFileName(entryName.substringAfterLast('/').ifBlank { "file.$ext" })
                val filename = "%04d_%04d_%s".format(archive.index + 1, extractedIndex + 1, base)
                val uri = createRow(filename, mime, relativePath(entity, gallery))
                createdUris += uri
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                zip.getInputStream(header).use { input ->
                    context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        while (true) {
                            if (!SyncControl.checkpoint()) throw SyncCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytes += read
                        }
                    }
                }
                finishRow(uri)
                val syntheticUrl = "archive:" + archive.normalizedUrl + "#" + entryName
                val media = MediaRef(
                    source = archive.source,
                    stableId = sha256Text(archive.stableId + ":" + entryName).take(24),
                    galleryStableId = archive.galleryStableId,
                    index = 100000 + archive.index * 10000 + extractedIndex,
                    url = syntheticUrl,
                    normalizedUrl = syntheticUrl,
                    referer = archive.url,
                    mimeHint = mime,
                    kind = kind,
                    provider = archive.provider
                )
                val result = DownloadResult(
                    contentUri = uri.toString(),
                    filename = filename,
                    mimeType = mime,
                    bytes = bytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                    aHash64 = if (mime.startsWith("image/")) averageHash64(uri) else null
                )
                out += ExtractedDownload(media, result)
                extractedIndex++
            }
            if (out.isEmpty()) {
                val expected = if (archive.archiveVideosOnly) "video files" else "supported image/video files"
                throw AdapterException("ZIP contained no $expected.")
            }
            return out
        } catch (t: Throwable) {
            createdUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw t
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun extractRar(
        entity: EntityRecord,
        gallery: GalleryMeta,
        archive: MediaRef,
        archiveUri: Uri
    ): List<ExtractedDownload> {
        val temp = File.createTempFile("galleryflow_", ".rar", context.cacheDir)
        val createdUris = mutableListOf<Uri>()
        try {
            context.contentResolver.openInputStream(archiveUri)!!.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            val password = archive.archivePassword?.takeIf { it.isNotBlank() }
            val rar = if (password == null) Archive(temp) else Archive(temp, password)
            rar.use { opened ->
                val out = mutableListOf<ExtractedDownload>()
                var extractedIndex = 0

                while (true) {
                    if (!SyncControl.checkpoint()) throw SyncCancelledException()
                    val header = opened.nextFileHeader() ?: break
                    if (header.isDirectory) continue

                    val entryName = header.fileName.replace('\\', '/')
                    val ext = entryName.substringAfterLast('.', "").lowercase()
                    val mime = mimeForExtension(ext) ?: continue
                    val kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE
                    if (archive.archiveVideosOnly && kind != MediaKind.VIDEO) continue

                    val base = safeFileName(entryName.substringAfterLast('/').ifBlank { "file.$ext" })
                    val filename = "%04d_%04d_%s".format(archive.index + 1, extractedIndex + 1, base)
                    val uri = createRow(filename, mime, relativePath(entity, gallery))
                    createdUris += uri

                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    opened.getInputStream(header).use { input ->
                        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            while (true) {
                                if (!SyncControl.checkpoint()) throw SyncCancelledException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                bytes += read
                            }
                        }
                    }
                    finishRow(uri)

                    val syntheticUrl = "archive:" + archive.normalizedUrl + "#" + entryName
                    val media = MediaRef(
                        source = archive.source,
                        stableId = sha256Text(archive.stableId + ":" + entryName).take(24),
                        galleryStableId = archive.galleryStableId,
                        index = 100000 + archive.index * 10000 + extractedIndex,
                        url = syntheticUrl,
                        normalizedUrl = syntheticUrl,
                        referer = archive.url,
                        mimeHint = mime,
                        kind = kind,
                        provider = archive.provider
                    )
                    val result = DownloadResult(
                        contentUri = uri.toString(),
                        filename = filename,
                        mimeType = mime,
                        bytes = bytes,
                        sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                        aHash64 = if (mime.startsWith("image/")) averageHash64(uri) else null
                    )
                    out += ExtractedDownload(media, result)
                    extractedIndex++
                }

                if (out.isEmpty()) {
                    val expected = if (archive.archiveVideosOnly) "video files" else "supported image/video files"
                    throw AdapterException("RAR contained no $expected.")
                }
                return out
            }
        } catch (t: Throwable) {
            createdUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw t
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun createRow(filename: String, mime: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create MediaStore row")
    }

    private fun finishRow(uri: Uri) {
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
    }

    private fun relativePath(entity: EntityRecord, gallery: GalleryMeta): String =
        Environment.DIRECTORY_DOWNLOADS + "/Cosplay/GalleryFlow/" +
            safe(entity.source.wireName) + "/" +
            safe(entity.displayName.ifBlank { entity.entityId }) + "/" +
            safe(gallery.stableId + " - " + gallery.title)

    private fun safe(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "unknown" }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(160)
        .ifBlank { "file.bin" }

    private fun chooseMime(media: MediaRef, responseMime: String?, filename: String?, url: String): String {
        val clean = responseMime?.substringBefore(';')?.trim()?.lowercase()
        if (!clean.isNullOrBlank() &&
            clean != "application/octet-stream" &&
            clean != "binary/octet-stream"
        ) return clean

        val source = filename ?: url
        val ext = source.substringBefore('?').substringAfterLast('.', "").lowercase()
        mimeForExtension(ext)?.let { return it }
        media.mimeHint?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }

        return if (media.kind == MediaKind.ARCHIVE) "application/octet-stream" else "application/octet-stream"
    }

    private fun extensionFor(mime: String, source: String, kind: MediaKind): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/mp2t" -> "ts"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/vnd.rar", "application/x-rar-compressed", "application/x-rar" -> "rar"
        else -> {
            val ext = source.substringBefore('?').substringAfterLast('.', "").lowercase().take(8)
            if (ext.isNotBlank()) ext else if (kind == MediaKind.ARCHIVE) "zip" else "bin"
        }
    }

    private fun filenameFromDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.let {
            return runCatching { URLDecoder.decode(it.trim(), StandardCharsets.UTF_8.name()) }.getOrDefault(it.trim())
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isZip(filename: String, mime: String): Boolean =
        filename.endsWith(".zip", true) || mime.contains("zip", true)

    private fun isRar(filename: String, mime: String): Boolean =
        filename.endsWith(".rar", true) || mime.contains("rar", true)

    private fun mimeForExtension(ext: String): String? = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        else -> null
    }

    private fun averageHash64(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = max(1, max(bounds.outWidth / 256, bounds.outHeight / 256))
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        bitmap.useBitmap { source ->
            val tiny = Bitmap.createScaledBitmap(source, 8, 8, true)
            tiny.useBitmap { b ->
                val luminance = IntArray(64)
                var total = 0L
                var i = 0
                for (y in 0 until 8) for (x in 0 until 8) {
                    val color = b.getPixel(x, y)
                    val lum = (android.graphics.Color.red(color) * 299 +
                        android.graphics.Color.green(color) * 587 +
                        android.graphics.Color.blue(color) * 114) / 1000
                    luminance[i++] = lum
                    total += lum
                }
                val average = total / 64
                var bits = 0L
                luminance.forEachIndexed { index, lum ->
                    if (lum >= average) bits = bits or (1L shl index)
                }
                java.lang.Long.toUnsignedString(bits, 16).padStart(16, '0')
            }
        }
    }.getOrNull()

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        try { return block(this) } finally { if (!isRecycled) recycle() }
    }
}
