package com.azrael.galleryflow

import android.content.Context

class SyncEngine(private val context: Context) {
    private enum class ProcessResult { COMPLETE, PARTIAL, PROVIDER_REQUIRED }

    data class Stats(
        var entities: Int = 0,
        var galleriesSeen: Int = 0,
        var galleriesCompleted: Int = 0,
        var mediaDownloaded: Int = 0,
        var mediaDeduped: Int = 0,
        var errors: Int = 0
    )

    fun run(mode: SyncMode, progress: (String) -> Unit = {}): Stats {
        val stats = Stats()
        if (!SyncControl.tryStart(mode, queueLiveIfBusy = true)) return stats
        val db = GalleryDb(context)
        val files = MediaStoreFiles(context, HttpClient())
        val flow = FlowLinkEmitter(context, db)
        try {
            db.ensureEntities(AdapterRegistry.defaultEntities())
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
        val entities = orderedEntities(db.listEntities().filter { it.selected && it.liveEnabled })
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
                val iterator = adapter.enumerateGalleries(entity.toSourceEntity()).iterator()
                if (entity.liveCursor.isNullOrBlank()) {
                    val newest = if (iterator.hasNext()) iterator.next().stableId else null
                    db.setLiveCursor(entity.source, entity.entityId, newest)
                    progress(entity.displayName + ": Live baseline established")
                    continue
                }

                val newOnes = mutableListOf<GalleryRef>()
                while (iterator.hasNext()) {
                    if (!SyncControl.checkpoint()) return
                    val gallery = iterator.next()
                    if (gallery.stableId == entity.liveCursor) break
                    newOnes += gallery
                }

                var blocked = false
                for (gallery in newOnes.asReversed()) {
                    if (!SyncControl.checkpoint()) return
                    when (processGallery(entity, gallery, adapter, db, files, flow, stats, progress)) {
                        ProcessResult.COMPLETE ->
                            db.setLiveCursor(entity.source, entity.entityId, gallery.stableId)
                        ProcessResult.PROVIDER_REQUIRED -> {
                            blocked = true
                            db.setEntityError(entity.source, entity.entityId, "Provider session required before Live Sync can continue.")
                            break
                        }
                        ProcessResult.PARTIAL -> break
                    }
                }
                if (!blocked) db.setEntityError(entity.source, entity.entityId, null)
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
        val entities = orderedEntities(db.listEntities().filter { it.selected })
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
                var blocked = false
                var consecutiveFailures = 0
                for (gallery in adapter.enumerateGalleries(entity.toSourceEntity())) {
                    if (!SyncControl.checkpoint()) return
                    val filesBefore = stats.mediaDownloaded + stats.mediaDeduped
                    val hadPhysicalFilesBefore = db.galleryMedia(gallery.source, gallery.stableId)
                        .any { it.state == TransferState.COMPLETE && files.exists(it.contentUri) }
                    when (processGallery(entity, gallery, adapter, db, files, flow, stats, progress)) {
                        ProcessResult.COMPLETE -> {
                            consecutiveFailures = 0
                            db.setEntityError(entity.source, entity.entityId, null)
                        }
                        ProcessResult.PROVIDER_REQUIRED -> {
                            val filesAfter = stats.mediaDownloaded + stats.mediaDeduped
                            if (filesAfter > filesBefore || hadPhysicalFilesBefore) {
                                // A supplemental provider (typically CosplayTele video archive)
                                // failed after the directly exposed media were already saved.
                                // Keep the gallery partial for a later retry, but do not block
                                // the rest of the source.
                                consecutiveFailures = 0
                                db.setEntityError(
                                    entity.source,
                                    entity.entityId,
                                    "Some provider-only media remain pending at " + gallery.title
                                )
                                progress("Direct files saved; provider-only media pending • continuing")
                            } else {
                                blocked = true
                                db.setEntityError(
                                    entity.source,
                                    entity.entityId,
                                    "Provider download could not be resolved automatically at " + gallery.title
                                )
                                progress("Provider blocked • moving to next source • " + entity.displayName)
                                break
                            }
                        }
                        ProcessResult.PARTIAL -> {
                            val filesAfter = stats.mediaDownloaded + stats.mediaDeduped
                            if (filesAfter > filesBefore || hadPhysicalFilesBefore) {
                                // Partial gallery still has real filesystem progress/content.
                                consecutiveFailures = 0
                            } else {
                                consecutiveFailures++
                            }
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                blocked = true
                                db.setEntityError(
                                    entity.source,
                                    entity.entityId,
                                    "Stopped after $MAX_CONSECUTIVE_FAILURES zero-file gallery failures; moved to next source."
                                )
                                progress("Source produced no files repeatedly • moving on • " + entity.displayName)
                                break
                            }
                        }
                    }
                    if (serviceQueuedLive(db, files, flow, stats, progress)) {
                        SyncControl.setMode(SyncMode.BACKFILL)
                        SyncControl.updateMessage(prefix)
                        progress("Resuming " + prefix)
                    }
                }
                if (!blocked) db.setEntityError(entity.source, entity.entityId, null)
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
    ): ProcessResult {
        stats.galleriesSeen++
        db.upsertGallery(ref, TransferState.PENDING)
        return try {
            val (meta, mediaItems) = adapter.fetchGallery(ref)
            db.updateGalleryMeta(meta, mediaItems.size)
            var allComplete = true
            var providerRequired = false
            for ((position, media) in mediaItems.withIndex()) {
                if (!SyncControl.checkpoint()) return ProcessResult.PARTIAL
                db.upsertMedia(entity.entityId, media)
                val existing = db.media(media.source, media.stableId)
                if (existing != null && existing.state == TransferState.COMPLETE && files.exists(existing.contentUri)) continue
                if (existing != null && !files.exists(existing.contentUri)) {
                    db.setMediaState(media.source, media.stableId, TransferState.PENDING)
                }

                SyncControl.updateMessage(meta.title + " • " + (position + 1) + "/" + mediaItems.size)
                db.markMediaDownloading(media)
                try {
                    val outcome = files.download(entity, meta, media)
                    commitDownloaded(entity, meta, media, outcome.primary, db, files, flow, stats)
                    for (extracted in outcome.extracted) {
                        db.upsertMedia(entity.entityId, extracted.media)
                        commitDownloaded(entity, meta, extracted.media, extracted.result, db, files, flow, stats)
                    }
                } catch (e: InteractiveProviderRequiredException) {
                    allComplete = false
                    providerRequired = true
                    db.setMediaState(media.source, media.stableId, TransferState.INACCESSIBLE)
                    db.setGalleryState(meta.source, meta.stableId, TransferState.PARTIAL, e.provider + " session required")
                    progress("Provider session required • " + e.provider + " • " + meta.title)
                    break
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
                    if (SyncControl.isStopping() || t is SyncCancelledException) return ProcessResult.PARTIAL
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
                ProcessResult.COMPLETE
            } else {
                db.setGalleryState(meta.source, meta.stableId, TransferState.PARTIAL)
                if (providerRequired) ProcessResult.PROVIDER_REQUIRED else ProcessResult.PARTIAL
            }
        } catch (e: HttpStatusException) {
            stats.errors++
            val state = if (e.code in listOf(401,403,404,410)) TransferState.INACCESSIBLE else TransferState.RETRYING
            db.setGalleryState(ref.source, ref.stableId, state, e.message)
            ProcessResult.PARTIAL
        } catch (t: Throwable) {
            if (SyncControl.isStopping() || t is SyncCancelledException) return ProcessResult.PARTIAL
            stats.errors++
            db.setGalleryState(ref.source, ref.stableId, TransferState.RETRYING, t.message ?: t.javaClass.simpleName)
            ProcessResult.PARTIAL
        }
    }

    private fun commitDownloaded(
        entity: EntityRecord,
        meta: GalleryMeta,
        media: MediaRef,
        fresh: DownloadResult,
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats
    ) {
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
    }

    private fun orderedEntities(input: List<EntityRecord>): List<EntityRecord> {
        val order = AdapterRegistry.adapters.mapIndexed { index, adapter -> adapter.source to index }.toMap()
        return input.sortedWith(
            compareBy<EntityRecord> { order[it.source] ?: Int.MAX_VALUE }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun EntityRecord.toSourceEntity() =
        SourceEntity(source, entityId, displayName, canonicalUrl, kind)

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
