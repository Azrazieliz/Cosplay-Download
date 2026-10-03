package com.azrael.galleryflow

enum class SourceId(val wireName: String) {
    KIUTAKU("kiutaku"),
    FOUR_K_HD("4khd"),
    BUONDUA("buondua"),
    COSPLAYTELE("cosplaytele");

    companion object {
        fun fromWire(value: String): SourceId = entries.first { it.wireName == value }
    }
}

enum class TransferState(val dbValue: String) {
    UNSEEN("unseen"),
    PENDING("pending"),
    DOWNLOADING("downloading"),
    PARTIAL("partial"),
    COMPLETE("complete"),
    SKIPPED("skipped"),
    INACCESSIBLE("inaccessible"),
    RETRYING("retrying"),
    PERMANENT_ERROR("permanent_error")
}

enum class SyncMode { LIVE, BACKFILL }

data class SourceEntity(
    val source: SourceId,
    val stableId: String,
    val displayName: String,
    val canonicalUrl: String,
    val kind: String = "tag"
)

data class GalleryRef(
    val source: SourceId,
    val stableId: String,
    val entityStableId: String,
    val canonicalUrl: String,
    val title: String,
    val publishedAt: String? = null
)

data class GalleryMeta(
    val source: SourceId,
    val stableId: String,
    val entityStableId: String,
    val canonicalUrl: String,
    val title: String,
    val tags: List<String>,
    val publishedAt: String?,
    val pageUrls: List<String>
)

data class MediaRef(
    val source: SourceId,
    val stableId: String,
    val galleryStableId: String,
    val index: Int,
    val url: String,
    val normalizedUrl: String,
    val referer: String,
    val mimeHint: String? = null
)

data class EntityRecord(
    val source: SourceId,
    val entityId: String,
    val displayName: String,
    val canonicalUrl: String,
    val kind: String,
    val selected: Boolean,
    val liveEnabled: Boolean,
    val liveCursor: String?,
    val lastSyncAt: Long?,
    val lastError: String?
)

data class MediaRecord(
    val source: SourceId,
    val mediaId: String,
    val galleryId: String,
    val entityId: String,
    val index: Int,
    val url: String,
    val contentUri: String?,
    val filename: String?,
    val state: TransferState,
    val sha256: String?,
    val aHash64: String?,
    val duplicateOf: String?
)

data class DownloadResult(
    val contentUri: String,
    val filename: String,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
    val aHash64: String?
)

class AdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)
class HttpStatusException(val code: Int, message: String) : Exception(message)
class SyncCancelledException : Exception("Sync cancelled")
