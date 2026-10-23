package com.azrael.galleryflow

import android.content.Context

/** Flat archive-to-cloud sync. Local SQLite/media are authoritative; cloud state
 * is an independent outbox and can never invalidate downloaded galleries. */
class CloudSync(private val context: Context) {
    data class Outcome(val uploaded: Int, val failures: Int, val pending: Int)

    fun drain(
        db: GalleryDb,
        limit: Int,
        progress: (String) -> Unit
    ): Outcome {
        if (!TeraboxSettings.enabled(context) || !TeraboxSettings.isConfigured(context)) {
            return Outcome(0, 0, 0)
        }
        CloudUploadQueue(context).use { queue ->
            queue.reconcile(db.completedForCloud(), TeraboxSettings.remoteDirectory(context))
            val batch = queue.nextReady(limit, System.currentTimeMillis())
            var success = 0
            var failures = 0
            val client = TeraboxCloudUploader(context)
            val files = MediaStoreFiles(context, HttpClient())
            for ((index, item) in batch.withIndex()) {
                if (!SyncControl.checkpoint()) break
                SyncControl.updateMessage("TeraBox • ${index + 1}/${batch.size}")
                progress("TeraBox • ${index + 1}/${batch.size}")
                try {
                    // Another app / user might have removed the local file.
                    if (!files.exists(item.uri)) {
                        throw IllegalStateException("Local media is unavailable (not removed by Kyora)")
                    }
                    client.upload(item.uri, item.remotePath)
                    queue.success(item)
                    success++
                } catch (t: Throwable) {
                    if (SyncControl.isStopping() ||
                        t is SyncCancelledException || t is InterruptedException
                    ) break
                    val error = t.message ?: "Cloud upload error"
                    queue.error(item, error)
                    failures++
                    progress("TeraBox • $error")
                    if (error.contains("token", true) ||
                        error.contains("HTTP 401") ||
                        error.contains("HTTP 403") ||
                        error.contains("unsupported API domain", true)
                    ) break
                }
            }
            return Outcome(success, failures, queue.summary().pending)
        }
    }
}
