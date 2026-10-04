package com.azrael.galleryflow

import android.content.Context

class SyncEngine(private val context: Context) {
    data class Stats(
        var entities: Int = 0,
        var galleriesSeen: Int = 0,
        var galleriesCompleted: Int = 0,
        var mediaDownloaded: Int = 0,
        var mediaDeduped: Int = 0,
        var errors: Int = 0
    )

    private val http = HttpClient()

    fun run(mode: SyncMode, progress: (String) -> Unit = {}): Stats {
        val stats = Stats()
        if (!SyncControl.tryStart(mode, queueLiveIfBusy = true)) return stats
        val db = GalleryDb(context)
        val files = MediaStoreFiles(context, http)
        val flow = FlowLinkEmitter(context, db)
        try {
            SyncControl.setMode(mode)
            when (mode) {
                SyncMode.LIVE -> runLive(db, files, flow, stats, progress)
                SyncMode.BACKFILL -> runBackfill(db, files, flow, stats, progress)
            }
            while (!SyncControl.isStopping() && SyncControl.takePendingLive()) {
                SyncControl.setMode(SyncMode.LIVE)
                progress("Running queued Live Sync…")
                runLive(db, files, flow, stats, progress)
            }
            val label = mode.name.lowercase().replaceFirstChar { it.uppercase() } + " complete"
            SyncControl.finish(if (SyncControl.isStopping()) "Stopped" else label)
        } catch (t: Throwable) {
            if (!SyncControl.isStopping() && t !is SyncCancelledException) stats.errors++
            SyncControl.finish(if (SyncControl.isStopping()) "Stopped" else "Sync finished with errors")
        } finally {
            db.close()
        }
        return stats
    }

    private fun runLive(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        val entities = db.listEntities().filter { it.selected && it.liveEnabled }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            val adapter = AdapterRegistry.forSource(entity.source)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val message = "Live • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(message)
            progress(message)
            try {
                val galleries = adapter.enumerateGalleries(entity.toSourceEntity()).toList()
                val newest = galleries.firstOrNull()?.stableId
                if (entity.liveCursor.isNullOrBlank()) {
                    db.setLiveCursor(entity.source, entity.entityId, newest)
                    progress(entity.displayName + ": Live baseline established")
                    continue
                }
                val newOnes = galleries.takeWhile { it.stableId != entity.liveCursor }
                for (gallery in newOnes.asReversed()) {
                    if (!SyncControl.checkpoint()) return
                    if (processGallery(entity, gallery, adapter, db, files, flow, stats, progress)) {
                        db.setLiveCursor(entity.source, entity.entityId, gallery.stableId)
                    } else {
                        break
                    }
                }
                db.setEntityError(entity.source, entity.entityId, null)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                db.setEntityError(entity.source, entity.entityId, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun runBackfill(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        val entities = db.listEntities().filter { it.selected }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            val adapter = AdapterRegistry.forSource(entity.source)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val prefix = "Backfill • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(prefix)
            progress(prefix)
            try {
                for (gallery in adapter.enumerateGalleries(entity.toSourceEntity())) {
                    if (!SyncControl.checkpoint()) return
                    processGallery(entity, gallery, adapter, db, files, flow, stats, progress)
                    if (serviceQueuedLive(db, files, flow, stats, progress)) {
                        SyncControl.setMode(SyncMode.BACKFILL)
                        SyncControl.updateMessage(prefix)
                        progress("Resuming " + prefix)
                    }
                }
                db.setEntityError(entity.source, entity.entityId, null)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                db.setEntityError(entity.source, entity.entityId, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun serviceQueuedLive(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ): Boolean {
        if (!SyncControl.takePendingLive()) return false
        SyncControl.setMode(SyncMode.LIVE)
        progress("Backfill yielded to queued Live Sync")
        runLive(db, files, flow, stats, progress)
        return true
    }

    private fun processGallery(
        entity: EntityRecord,
        ref: GalleryRef,
        adapter: SourceAdapter,
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ): Boolean {
        stats.galleriesSeen++
        db.upsertGallery(ref, TransferState.PENDING)
        return try {
            val (meta, mediaItems) = adapter.fetchGallery(ref)
            db.updateGalleryMeta(meta, mediaItems.size)
            var allComplete = true
            for ((position, media) in mediaItems.withIndex()) {
                if (!SyncControl.checkpoint()) return false
                db.upsertMedia(entity.entityId, media)
                val existing = db.media(media.source, media.stableId)
                if (existing != null && existing.state == TransferState.COMPLETE && files.exists(existing.contentUri)) continue
                if (existing != null && !files.exists(existing.contentUri)) {
                    db.setMediaState(media.source, media.stableId, TransferState.PENDING)
                }

                SyncControl.updateMessage(meta.title + " • " + (position + 1) + "/" + mediaItems.size)
                db.markMediaDownloading(media)
                try {
                    val fresh = files.download(entity, meta, media)
                    val duplicate = db.findCompleteByHash(fresh.sha256)
                    if (duplicate != null && files.exists(duplicate.contentUri)) {
                        files.delete(fresh.contentUri)
                        db.markMediaComplete(
                            media,
                            fresh.copy(
                                contentUri = requireNotNull(duplicate.contentUri),
                                filename = duplicate.filename ?: fresh.filename
                            ),
                            duplicateOf = duplicate.source.wireName + ":" + duplicate.mediaId
                        )
                        stats.mediaDeduped++
                    } else {
                        db.markMediaComplete(media, fresh)
                        stats.mediaDownloaded++
                        flow.mediaDownloaded(entity, meta, media, fresh)
                    }
                } catch (e: HttpStatusException) {
                    allComplete = false
                    val state = when (e.code) {
                        401, 403, 404, 410 -> TransferState.INACCESSIBLE
                        429, 500, 502, 503, 504 -> TransferState.RETRYING
                        else -> TransferState.PARTIAL
                    }
                    db.setMediaState(media.source, media.stableId, state)
                    if (e.code == 429) break
                } catch (t: Throwable) {
                    if (SyncControl.isStopping() || t is SyncCancelledException) return false
                    allComplete = false
                    db.setMediaState(media.source, media.stableId, TransferState.RETRYING)
                }
            }

            val records = db.galleryMedia(meta.source, meta.stableId)
            val diskComplete = records.isNotEmpty() &&
                records.size >= mediaItems.size &&
                records.all { it.state == TransferState.COMPLETE && files.exists(it.contentUri) }

            if (allComplete && diskComplete) {
                db.setGalleryState(meta.source, meta.stableId, TransferState.COMPLETE)
                stats.galleriesCompleted++
                flow.galleryDownloaded(entity, meta)
                progress("Complete • " + meta.title)
                true
            } else {
                db.setGalleryState(meta.source, meta.stableId, TransferState.PARTIAL)
                false
            }
        } catch (e: HttpStatusException) {
            stats.errors++
            val state = if (e.code in listOf(401,403,404,410)) TransferState.INACCESSIBLE else TransferState.RETRYING
            db.setGalleryState(ref.source, ref.stableId, state, e.message)
            false
        } catch (t: Throwable) {
            if (SyncControl.isStopping() || t is SyncCancelledException) return false
            stats.errors++
            db.setGalleryState(ref.source, ref.stableId, TransferState.RETRYING, t.message ?: t.javaClass.simpleName)
            false
        }
    }

    private fun EntityRecord.toSourceEntity() =
        SourceEntity(source, entityId, displayName, canonicalUrl, kind)
}
