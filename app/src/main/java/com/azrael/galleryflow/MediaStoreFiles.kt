package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.github.junrar.Junrar
import net.lingala.zip4j.ZipFile
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

class MediaStoreFiles(private val context: Context, private val http: HttpClient) {
    fun exists(contentUri: String?): Boolean {
        if (contentUri.isNullOrBlank()) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun download(entity: EntityRecord, gallery: GalleryMeta, media: MediaRef): DownloadOutcome {
        val candidates = buildList {
            add(RemoteMirror(media.url, media.provider))
            addAll(media.mirrors)
        }.distinctBy { it.url }

        var bestError: Throwable? = null
        for (candidate in candidates) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            try {
                return downloadCandidate(entity, gallery, media, candidate)
            } catch (t: Throwable) {
                if (t is SyncCancelledException) throw t
                // Mirrors are alternatives for the same logical archive.
                bestError = when {
                    bestError == null -> t
                    bestError is InteractiveProviderRequiredException && t !is InteractiveProviderRequiredException -> t
                    else -> bestError
                }
            }
        }
        throw bestError ?: AdapterException("No usable download mirror.")
    }

    private fun downloadCandidate(
        entity: EntityRecord,
        gallery: GalleryMeta,
        media: MediaRef,
        candidate: RemoteMirror
    ): DownloadOutcome {
        val resolved = http.resolveDownloadUrl(
            candidate.url,
            media.referer,
            candidate.provider,
            media.nameHint
        )

        http.open(resolved.url, resolved.referer ?: candidate.url, resolved.cookie).use { response ->
            val responseMime = response.contentType?.lowercase(Locale.ROOT)
            if (media.kind == MediaKind.ARCHIVE && responseMime?.startsWith("text/html") == true) {
                throw InteractiveProviderRequiredException(
                    candidate.provider ?: "Archive provider",
                    response.finalUrl
                )
            }

            val dispositionName = filenameFromDisposition(response.contentDisposition)
            val nameSource = dispositionName ?: resolved.filenameHint ?: response.finalUrl
            val mime = chooseMime(media, response.contentType, nameSource, response.finalUrl)
            val ext = extensionFor(mime, nameSource, media.kind)
            val filename = when (media.kind) {
                MediaKind.ARCHIVE -> dispositionName?.let(::safeFileName)
                    ?: resolved.filenameHint?.let(::safeFileName)
                    ?: "archive-%04d.%s".format(media.index + 1, ext)
                else -> "%04d.%s".format(media.index + 1, ext)
            }

            val uri = createRow(filename, mime, relativePath(entity, gallery))
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var finished = false

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
                finished = true

                val primary = DownloadResult(
                    contentUri = uri.toString(),
                    filename = filename,
                    mimeType = mime,
                    bytes = bytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                    aHash64 = if (mime.startsWith("image/")) averageHash64(uri) else null
                )

                if (media.kind != MediaKind.ARCHIVE) {
                    return DownloadOutcome(primary)
                }

                val extraction = runCatching {
                    extractArchive(entity, gallery, media, uri, filename)
                }
                return DownloadOutcome(
                    primary = primary,
                    extracted = extraction.getOrDefault(emptyList()),
                    extractionError = extraction.exceptionOrNull()?.message
                )
            } catch (t: Throwable) {
                if (!finished) runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }
    }

    fun extractExisting(
        entity: EntityRecord,
        gallery: GalleryMeta,
        media: MediaRef,
        record: MediaRecord
    ): Pair<List<ExtractedDownload>, String?> {
        val uri = record.contentUri?.let(Uri::parse)
            ?: return emptyList<ExtractedDownload>() to "Archive file is missing."
        val filename = record.filename ?: "archive.bin"
        return runCatching {
            extractArchive(entity, gallery, media, uri, filename)
        }.fold(
            onSuccess = { it to null },
            onFailure = { emptyList<ExtractedDownload>() to (it.message ?: it.javaClass.simpleName) }
        )
    }

    fun delete(contentUri: String?) {
        if (!contentUri.isNullOrBlank()) {
            runCatching { context.contentResolver.delete(Uri.parse(contentUri), null, null) }
        }
    }

    private fun extractArchive(
        entity: EntityRecord,
        gallery: GalleryMeta,
        archive: MediaRef,
        archiveUri: Uri,
        filename: String
    ): List<ExtractedDownload> {
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in setOf("zip", "rar")) return emptyList()

        val tempRoot = File(context.cacheDir, "galleryflow_extract_" + System.nanoTime())
        val archiveFile = File(tempRoot, "archive." + extension)
        val extractedDir = File(tempRoot, "out")
        archiveFile.parentFile?.mkdirs()
        extractedDir.mkdirs()

        try {
            context.contentResolver.openInputStream(archiveUri)!!.use { input ->
                archiveFile.outputStream().use { output -> input.copyTo(output) }
            }

            when (extension) {
                "zip" -> {
                    val zip = ZipFile(archiveFile)
                    if (zip.isEncrypted) {
                        val password = archive.archivePassword?.takeIf { it.isNotBlank() }
                            ?: throw AdapterException("Encrypted ZIP requires an extraction password.")
                        zip.setPassword(password.toCharArray())
                    }
                    zip.extractAll(extractedDir.absolutePath)
                }
                "rar" -> {
                    val password = archive.archivePassword?.takeIf { it.isNotBlank() }
                    if (password == null) {
                        Junrar.extract(archiveFile, extractedDir)
                    } else {
                        Junrar.extract(archiveFile, extractedDir, password)
                    }
                }
            }

            val files = extractedDir.walkTopDown()
                .filter { it.isFile && mimeForExtension(it.extension) != null }
                .toList()

            if (files.isEmpty()) {
                throw AdapterException(
                    archiveLabel(extension) + " contained no supported image/video files."
                )
            }

            return files.mapIndexed { extractedIndex, file ->
                if (!SyncControl.checkpoint()) throw SyncCancelledException()
                importExtracted(entity, gallery, archive, file, extractedIndex)
            }
        } finally {
            runCatching { tempRoot.deleteRecursively() }
        }
    }

    private fun importExtracted(
        entity: EntityRecord,
        gallery: GalleryMeta,
        archive: MediaRef,
        source: File,
        extractedIndex: Int
    ): ExtractedDownload {
        val mime = mimeForExtension(source.extension)
            ?: throw AdapterException("Unsupported extracted file: " + source.name)
        val kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE
        val base = safeFileName(source.name)
        val filename = "%04d_%04d_%s".format(
            archive.index + 1,
            extractedIndex + 1,
            base
        )

        val uri = createRow(filename, mime, relativePath(entity, gallery))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L

        try {
            source.inputStream().use { input ->
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
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }

        val syntheticUrl = "archive:" + archive.normalizedUrl + "#" + source.name
        val media = MediaRef(
            source = archive.source,
            stableId = sha256Text(archive.stableId + ":" + source.name + ":" + bytes).take(24),
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
        return ExtractedDownload(media, result)
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

    private fun chooseMime(
        media: MediaRef,
        responseMime: String?,
        filename: String?,
        url: String
    ): String {
        val clean = responseMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (!clean.isNullOrBlank() && clean != "application/octet-stream") return clean

        media.mimeHint?.takeIf { it.isNotBlank() }?.let { return it }

        val source = filename ?: url
        val ext = source.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return mimeForExtension(ext)
            ?: if (media.kind == MediaKind.ARCHIVE) "application/octet-stream"
            else "application/octet-stream"
    }

    private fun extensionFor(mime: String, source: String, kind: MediaKind): String = when (mime.lowercase(Locale.ROOT)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/vnd.rar", "application/x-rar-compressed" -> "rar"
        else -> {
            val ext = source.substringBefore('?').substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .take(8)
            if (ext in setOf("zip", "rar", "7z", "mp4", "mkv", "webm", "mov", "jpg", "jpeg", "png", "webp", "gif", "avif")) {
                ext
            } else if (kind == MediaKind.ARCHIVE) {
                "bin"
            } else {
                "bin"
            }
        }
    }

    private fun filenameFromDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null

        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                return runCatching {
                    URLDecoder.decode(it.trim(), StandardCharsets.UTF_8.name())
                }.getOrDefault(it.trim())
            }

        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun mimeForExtension(ext: String): String? = when (ext.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        else -> null
    }

    private fun archiveLabel(ext: String): String =
        ext.uppercase(Locale.ROOT).ifBlank { "Archive" }

    private fun averageHash64(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val sample = max(1, max(bounds.outWidth / 256, bounds.outHeight / 256))
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } ?: return@runCatching null

        bitmap.useBitmap { source ->
            val tiny = Bitmap.createScaledBitmap(source, 8, 8, true)
            tiny.useBitmap { b ->
                val luminance = IntArray(64)
                var total = 0L
                var i = 0
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val color = b.getPixel(x, y)
                        val lum = (
                            android.graphics.Color.red(color) * 299 +
                                android.graphics.Color.green(color) * 587 +
                                android.graphics.Color.blue(color) * 114
                            ) / 1000
                        luminance[i++] = lum
                        total += lum
                    }
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

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        try {
            return block(this)
        } finally {
            if (!isRecycled) recycle()
        }
    }
}
