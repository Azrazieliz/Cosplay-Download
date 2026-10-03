package com.azrael.galleryflow

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class FlowLinkEmitter(private val context: Context, private val db: GalleryDb) {
    fun mediaDownloaded(entity: EntityRecord, gallery: GalleryMeta, media: MediaRef, result: DownloadResult) {
        emit("MEDIA_DOWNLOADED", JSONObject().apply {
            put("source", media.source.wireName)
            put("entity_id", entity.entityId)
            put("gallery_id", gallery.stableId)
            put("media_id", media.stableId)
            put("content_uri", result.contentUri)
            put("sha256", result.sha256)
        })
    }

    fun galleryDownloaded(entity: EntityRecord, gallery: GalleryMeta) {
        emit("GALLERY_DOWNLOADED", JSONObject().apply {
            put("source", gallery.source.wireName)
            put("entity_id", entity.entityId)
            put("gallery_id", gallery.stableId)
            put("title", gallery.title)
            put("canonical_url", gallery.canonicalUrl)
        })
    }

    private fun emit(type: String, payload: JSONObject) {
        db.recordEvent(type, payload.toString())
        context.sendBroadcast(Intent(ACTION_FLOWLINK).apply {
            setPackage(null)
            putExtra("event_type", type)
            putExtra("payload_json", payload.toString())
        })
    }

    companion object {
        const val ACTION_FLOWLINK = "com.azrael.galleryflow.FLOWLINK_EVENT"
    }
}
