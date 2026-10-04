package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class GalleryDb(context: Context) : SQLiteOpenHelper(context, "galleryflow.db", null, 7) {
    data class SourceStats(
        val galleries: Int,
        val complete: Int,
        val partial: Int,
        val inaccessible: Int,
        val mediaComplete: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entities(
            source TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            display_name TEXT NOT NULL,
            canonical_url TEXT NOT NULL,
            kind TEXT NOT NULL,
            selected INTEGER NOT NULL DEFAULT 1,
            live_enabled INTEGER NOT NULL DEFAULT 1,
            live_cursor TEXT,
            last_sync_at INTEGER,
            last_error TEXT,
            PRIMARY KEY(source, entity_id)
        )""")
        db.execSQL("""CREATE TABLE galleries(
            source TEXT NOT NULL,
            gallery_id TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            canonical_url TEXT NOT NULL,
            title TEXT NOT NULL,
            tags_json TEXT,
            published_at TEXT,
            retrieved_at INTEGER,
            media_count INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'unseen',
            last_error TEXT,
            PRIMARY KEY(source, gallery_id)
        )""")
        db.execSQL("""CREATE TABLE media(
            source TEXT NOT NULL,
            media_id TEXT NOT NULL,
            gallery_id TEXT NOT NULL,
            entity_id TEXT NOT NULL,
            media_index INTEGER NOT NULL,
            url TEXT NOT NULL,
            normalized_url TEXT,
            referer TEXT,
            content_uri TEXT,
            filename TEXT,
            mime_type TEXT,
            bytes INTEGER,
            sha256 TEXT,
            ahash64 TEXT,
            duplicate_of TEXT,
            state TEXT NOT NULL DEFAULT 'unseen',
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(source, media_id)
        )""")
        db.execSQL("""CREATE TABLE identity_aliases(
            canonical_identity TEXT NOT NULL,
            source TEXT NOT NULL,
            source_entity_id TEXT NOT NULL,
            confidence TEXT NOT NULL DEFAULT 'explicit',
            PRIMARY KEY(source, source_entity_id)
        )""")
        db.execSQL("""CREATE TABLE events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_media_hash ON media(sha256)")
        db.execSQL("CREATE INDEX idx_media_gallery ON media(source, gallery_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DELETE FROM entities WHERE kind!='site'")
        }
        if (oldVersion < 3) {
            resetProviderFailures(db)
            db.execSQL(
                """UPDATE entities
                   SET last_error=NULL
                   WHERE source IN ('4khd','buondua','cosplaytele')"""
            )
        }
        if (oldVersion < 4) {
            // Previous test builds could catalogue hundreds of failures without saving a file.
            // Keep real completed media only and restart incomplete discovery cleanly.
            db.execSQL("DELETE FROM media WHERE state!='complete' OR content_uri IS NULL")
            db.execSQL(
                """DELETE FROM galleries
                   WHERE NOT EXISTS (
                     SELECT 1 FROM media m
                     WHERE m.source=galleries.source
                       AND m.gallery_id=galleries.gallery_id
                       AND m.state='complete'
                       AND m.content_uri IS NOT NULL
                   )"""
            )
            db.execSQL("UPDATE entities SET last_error=NULL, live_cursor=NULL")
        }
        if (oldVersion < 5) {
            // 0.5 changes 4KHD/CosplayTele from archive-first to direct-media-first.
            // Retry every unfinished gallery with the new parser while preserving real completed files.
            db.execSQL(
                """DELETE FROM media
                   WHERE source IN ('4khd','cosplaytele')
                     AND (state!='complete' OR content_uri IS NULL)"""
            )
            db.execSQL(
                """DELETE FROM galleries
                   WHERE source IN ('4khd','cosplaytele')
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                         AND m.content_uri IS NOT NULL
                     )"""
            )
            db.execSQL(
                """UPDATE entities
                   SET last_error=NULL, live_cursor=NULL
                   WHERE source IN ('4khd','cosplaytele')"""
            )
        }
        if (oldVersion < 6) {
            // Re-run Kiutaku with its current .article-fulltext parser and revisit
            // CosplayTele galleries that advertise videos but have no saved video yet.
            db.execSQL(
                """DELETE FROM media
                   WHERE source='kiutaku'
                     AND (state!='complete' OR content_uri IS NULL)"""
            )
            db.execSQL(
                """DELETE FROM galleries
                   WHERE source='kiutaku'
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                         AND m.content_uri IS NOT NULL
                     )"""
            )
            db.execSQL(
                """UPDATE galleries
                   SET state='pending', last_error=NULL
                   WHERE source='cosplaytele'
                     AND lower(title) LIKE '%video%'
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                         AND m.mime_type LIKE 'video/%'
                         AND m.content_uri IS NOT NULL
                     )"""
            )
            db.execSQL(
                """UPDATE entities
                   SET last_error=NULL, live_cursor=NULL
                   WHERE source IN ('kiutaku','cosplaytele')"""
            )
        }
        if (oldVersion < 7) {
            // 0.5.2 changes the actual Kiutaku download request/referer path and
            // adds direct embedded/HLS video extraction for CosplayTele.
            db.execSQL(
                """DELETE FROM media
                   WHERE source='kiutaku'
                     AND (state!='complete' OR content_uri IS NULL)"""
            )
            db.execSQL(
                """DELETE FROM galleries
                   WHERE source='kiutaku'
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                         AND m.content_uri IS NOT NULL
                     )"""
            )
            db.execSQL(
                """DELETE FROM media
                   WHERE source='cosplaytele'
                     AND state!='complete'
                     AND (
                       mime_type LIKE 'video/%'
                       OR url LIKE '%cossora%'
                       OR url LIKE '%mediafire%'
                       OR url LIKE '%sorafolder%'
                       OR url LIKE '%gofile%'
                     )"""
            )
            db.execSQL(
                """UPDATE galleries
                   SET state='pending', last_error=NULL
                   WHERE source='cosplaytele'
                     AND lower(title) LIKE '%video%'
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                         AND m.mime_type LIKE 'video/%'
                         AND m.content_uri IS NOT NULL
                     )"""
            )
            db.execSQL(
                """UPDATE entities
                   SET last_error=NULL, live_cursor=NULL
                   WHERE source IN ('kiutaku','cosplaytele')"""
            )
        }
    }

    fun ensureEntities(entities: List<SourceEntity>) {
        entities.forEach(::addEntity)
    }

    fun addEntity(entity: SourceEntity) {
        val values = ContentValues().apply {
            put("source", entity.source.wireName)
            put("entity_id", entity.stableId)
            put("display_name", entity.displayName)
            put("canonical_url", entity.canonicalUrl)
            put("kind", entity.kind)
            put("selected", 1)
            put("live_enabled", 1)
        }
        writableDatabase.insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                put("display_name", entity.displayName)
                put("canonical_url", entity.canonicalUrl)
                put("kind", entity.kind)
            },
            "source=? AND entity_id=?",
            arrayOf(entity.source.wireName, entity.stableId)
        )
    }

    fun listEntities(): List<EntityRecord> {
        val out = mutableListOf<EntityRecord>()
        readableDatabase.query(
            "entities",
            arrayOf("source","entity_id","display_name","canonical_url","kind","selected","live_enabled","live_cursor","last_sync_at","last_error"),
            null,null,null,null,"source ASC, display_name COLLATE NOCASE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += EntityRecord(
                    source = SourceId.fromWire(c.getString(0)),
                    entityId = c.getString(1),
                    displayName = c.getString(2),
                    canonicalUrl = c.getString(3),
                    kind = c.getString(4),
                    selected = c.getInt(5) == 1,
                    liveEnabled = c.getInt(6) == 1,
                    liveCursor = c.getString(7),
                    lastSyncAt = if (c.isNull(8)) null else c.getLong(8),
                    lastError = c.getString(9)
                )
            }
        }
        return out
    }

    fun sourceStats(source: SourceId): SourceStats {
        fun count(sql: String, args: Array<String>): Int =
            readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val src = source.wireName
        return SourceStats(
            galleries = count("SELECT COUNT(*) FROM galleries WHERE source=?", arrayOf(src)),
            complete = count("SELECT COUNT(*) FROM galleries WHERE source=? AND state=?", arrayOf(src, TransferState.COMPLETE.dbValue)),
            partial = count("SELECT COUNT(*) FROM galleries WHERE source=? AND state IN (?,?)", arrayOf(src, TransferState.PARTIAL.dbValue, TransferState.RETRYING.dbValue)),
            inaccessible = count("SELECT COUNT(*) FROM galleries WHERE source=? AND state=?", arrayOf(src, TransferState.INACCESSIBLE.dbValue)),
            mediaComplete = count("SELECT COUNT(*) FROM media WHERE source=? AND state=?", arrayOf(src, TransferState.COMPLETE.dbValue))
        )
    }

    fun retryProviderFailures() {
        resetProviderFailures(writableDatabase)
        writableDatabase.execSQL(
            """UPDATE entities
               SET last_error=NULL
               WHERE source IN ('4khd','buondua','cosplaytele')"""
        )
    }

    fun firstProviderBlockedMedia(): MediaRecord? =
        queryMedia(
            """state=? AND (
                url LIKE '%terabox%'
                OR url LIKE '%m.4khd.com%'
                OR url LIKE '%mediafire.com%'
                OR url LIKE '%sorafolder.com%'
                OR url LIKE '%gofile.io%'
                OR url LIKE '%t.me%'
            )""".trimIndent(),
            arrayOf(TransferState.INACCESSIBLE.dbValue),
            "1",
            "updated_at ASC"
        ).firstOrNull()

    fun setSelected(source: SourceId, entityId: String, value: Boolean) =
        setEntityBoolean(source, entityId, "selected", value)

    fun setLiveEnabled(source: SourceId, entityId: String, value: Boolean) =
        setEntityBoolean(source, entityId, "live_enabled", value)

    private fun setEntityBoolean(source: SourceId, entityId: String, column: String, value: Boolean) {
        require(column == "selected" || column == "live_enabled")
        writableDatabase.update(
            "entities",
            ContentValues().apply { put(column, if (value) 1 else 0) },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun setLiveCursor(source: SourceId, entityId: String, cursor: String?) {
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                if (cursor == null) putNull("live_cursor") else put("live_cursor", cursor)
                put("last_sync_at", System.currentTimeMillis())
                putNull("last_error")
            },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun setEntityError(source: SourceId, entityId: String, error: String?) {
        writableDatabase.update(
            "entities",
            ContentValues().apply {
                if (error == null) putNull("last_error") else put("last_error", error.take(500))
                put("last_sync_at", System.currentTimeMillis())
            },
            "source=? AND entity_id=?",
            arrayOf(source.wireName, entityId)
        )
    }

    fun upsertGallery(ref: GalleryRef, state: TransferState = TransferState.PENDING) {
        val values = ContentValues().apply {
            put("source", ref.source.wireName)
            put("gallery_id", ref.stableId)
            put("entity_id", ref.entityStableId)
            put("canonical_url", ref.canonicalUrl)
            put("title", ref.title)
            if (ref.publishedAt == null) putNull("published_at") else put("published_at", ref.publishedAt)
            put("state", state.dbValue)
        }
        val updated = writableDatabase.update(
            "galleries", values, "source=? AND gallery_id=?", arrayOf(ref.source.wireName, ref.stableId)
        )
        if (updated == 0) writableDatabase.insertOrThrow("galleries", null, values)
    }

    fun updateGalleryMeta(meta: GalleryMeta, mediaCount: Int) {
        writableDatabase.update(
            "galleries",
            ContentValues().apply {
                put("title", meta.title)
                put("canonical_url", meta.canonicalUrl)
                put("tags_json", JSONArray(meta.tags).toString())
                if (meta.publishedAt == null) putNull("published_at") else put("published_at", meta.publishedAt)
                put("retrieved_at", System.currentTimeMillis())
                put("media_count", mediaCount)
            },
            "source=? AND gallery_id=?",
            arrayOf(meta.source.wireName, meta.stableId)
        )
    }

    fun setGalleryState(source: SourceId, galleryId: String, state: TransferState, error: String? = null) {
        writableDatabase.update(
            "galleries",
            ContentValues().apply {
                put("state", state.dbValue)
                if (error == null) putNull("last_error") else put("last_error", error.take(500))
            },
            "source=? AND gallery_id=?",
            arrayOf(source.wireName, galleryId)
        )
    }

    fun upsertMedia(entityId: String, media: MediaRef) {
        val values = ContentValues().apply {
            put("source", media.source.wireName)
            put("media_id", media.stableId)
            put("gallery_id", media.galleryStableId)
            put("entity_id", entityId)
            put("media_index", media.index)
            put("url", media.url)
            put("normalized_url", media.normalizedUrl)
            put("referer", media.referer)
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update(
            "media", values, "source=? AND media_id=?", arrayOf(media.source.wireName, media.stableId)
        )
        if (updated == 0) {
            values.put("state", TransferState.PENDING.dbValue)
            writableDatabase.insertOrThrow("media", null, values)
        }
    }

    fun media(source: SourceId, mediaId: String): MediaRecord? = queryMedia(
        "source=? AND media_id=?", arrayOf(source.wireName, mediaId), "1"
    ).firstOrNull()

    fun galleryMedia(source: SourceId, galleryId: String): List<MediaRecord> = queryMedia(
        "source=? AND gallery_id=?", arrayOf(source.wireName, galleryId), null, "media_index ASC"
    )

    private fun queryMedia(selection: String, args: Array<String>, limit: String?, orderBy: String? = null): List<MediaRecord> {
        val out = mutableListOf<MediaRecord>()
        readableDatabase.query(
            "media",
            arrayOf("source","media_id","gallery_id","entity_id","media_index","url","content_uri","filename","state","sha256","ahash64","duplicate_of"),
            selection,args,null,null,orderBy,limit
        ).use { c ->
            while (c.moveToNext()) {
                out += MediaRecord(
                    SourceId.fromWire(c.getString(0)), c.getString(1), c.getString(2), c.getString(3),
                    c.getInt(4), c.getString(5), c.getString(6), c.getString(7),
                    stateFromDb(c.getString(8)), c.getString(9), c.getString(10), c.getString(11)
                )
            }
        }
        return out
    }

    fun markMediaDownloading(media: MediaRef) =
        setMediaState(media.source, media.stableId, TransferState.DOWNLOADING)

    fun setMediaState(source: SourceId, mediaId: String, state: TransferState) {
        writableDatabase.update(
            "media",
            ContentValues().apply {
                put("state", state.dbValue)
                put("updated_at", System.currentTimeMillis())
            },
            "source=? AND media_id=?",
            arrayOf(source.wireName, mediaId)
        )
    }

    fun markMediaComplete(media: MediaRef, result: DownloadResult, duplicateOf: String? = null) {
        writableDatabase.update(
            "media",
            ContentValues().apply {
                put("content_uri", result.contentUri)
                put("filename", result.filename)
                put("mime_type", result.mimeType)
                put("bytes", result.bytes)
                put("sha256", result.sha256)
                if (result.aHash64 == null) putNull("ahash64") else put("ahash64", result.aHash64)
                if (duplicateOf == null) putNull("duplicate_of") else put("duplicate_of", duplicateOf)
                put("state", TransferState.COMPLETE.dbValue)
                put("updated_at", System.currentTimeMillis())
            },
            "source=? AND media_id=?",
            arrayOf(media.source.wireName, media.stableId)
        )
    }

    fun findCompleteByHash(hash: String): MediaRecord? =
        queryMedia("sha256=? AND state=? AND content_uri IS NOT NULL", arrayOf(hash, TransferState.COMPLETE.dbValue), "1", "updated_at DESC").firstOrNull()

    fun recordEvent(type: String, payloadJson: String) {
        writableDatabase.insertOrThrow(
            "events", null,
            ContentValues().apply {
                put("event_type", type)
                put("payload_json", payloadJson)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    fun exportTables(): Map<String, List<Map<String, Any?>>> = linkedMapOf(
        "entities" to tableRows("entities"),
        "galleries" to tableRows("galleries"),
        "media" to tableRows("media"),
        "identity_aliases" to tableRows("identity_aliases"),
        "events" to tableRows("events")
    )

    private fun tableRows(table: String): List<Map<String, Any?>> {
        require(table in setOf("entities","galleries","media","identity_aliases","events"))
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery("SELECT * FROM " + table, null).use { c ->
            while (c.moveToNext()) {
                val row = linkedMapOf<String, Any?>()
                c.columnNames.forEachIndexed { i, name ->
                    row[name] = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { byte -> "%02x".format(byte) }
                        else -> c.getString(i)
                    }
                }
                out += row
            }
        }
        return out
    }

    private fun stateFromDb(value: String): TransferState =
        TransferState.entries.firstOrNull { it.dbValue == value } ?: TransferState.UNSEEN

    companion object {
        private fun resetProviderFailures(db: SQLiteDatabase) {
            db.execSQL(
                """DELETE FROM media
                   WHERE content_uri IS NULL
                     AND state IN ('inaccessible','retrying','partial','downloading')
                     AND (
                       url LIKE '%terabox%'
                       OR url LIKE '%1024tera%'
                       OR url LIKE '%teraboxapp%'
                       OR url LIKE '%m.4khd.com%'
                       OR url LIKE '%mediafire.com%'
                       OR url LIKE '%sorafolder.com%'
                       OR url LIKE '%gofile.io%'
                       OR url LIKE '%t.me%'
                     )"""
            )
            db.execSQL(
                """UPDATE galleries
                   SET state='pending', last_error=NULL
                   WHERE source IN ('4khd','buondua','cosplaytele')
                     AND state IN ('partial','inaccessible','retrying')
                     AND NOT EXISTS (
                       SELECT 1 FROM media m
                       WHERE m.source=galleries.source
                         AND m.gallery_id=galleries.gallery_id
                         AND m.state='complete'
                     )"""
            )
        }
    }
}
