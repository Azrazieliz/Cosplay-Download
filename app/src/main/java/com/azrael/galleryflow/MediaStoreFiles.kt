package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.security.MessageDigest
import kotlin.math.max

class MediaStoreFiles(private val context: Context, private val http: HttpClient) {
    fun exists(contentUri: String?): Boolean {
        if (contentUri.isNullOrBlank()) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun download(entity: EntityRecord, gallery: GalleryMeta, media: MediaRef): DownloadResult {
        if (!SyncControl.checkpoint()) throw SyncCancelledException()
        http.open(media.url, media.referer).use { response ->
            val mime = response.contentType?.takeIf { it.startsWith("image/") }
                ?: media.mimeHint ?: "application/octet-stream"
            val ext = extensionFor(mime, media.url)
            val filename = "%04d.%s".format(media.index + 1, ext)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath(entity, gallery))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create MediaStore row")
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
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                return DownloadResult(
                    contentUri = uri.toString(),
                    filename = filename,
                    mimeType = mime,
                    bytes = bytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                    aHash64 = averageHash64(uri)
                )
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }
    }

    fun delete(contentUri: String?) {
        if (!contentUri.isNullOrBlank()) runCatching {
            context.contentResolver.delete(Uri.parse(contentUri), null, null)
        }
    }

    private fun relativePath(entity: EntityRecord, gallery: GalleryMeta): String =
        Environment.DIRECTORY_DOWNLOADS + "/GalleryFlow/" +
            safe(entity.source.wireName) + "/" +
            safe(entity.displayName.ifBlank { entity.entityId }) + "/" +
            safe(gallery.stableId + " - " + gallery.title)

    private fun safe(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "unknown" }

    private fun extensionFor(mime: String, url: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> url.substringBefore('?').substringAfterLast('.', "bin").take(6).lowercase()
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

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        try { return block(this) } finally { if (!isRecycled) recycle() }
    }
}
