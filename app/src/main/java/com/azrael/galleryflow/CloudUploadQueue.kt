package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Independent durable TeraBox outbox. Never changes local gallery completion or
 * removes local files. Also includes historical completed media on every sync.
 */
class CloudUploadQueue(context: Context) :
    SQLiteOpenHelper(context, "kyora_cloud_uploads.db", null, 1) {

    data class Candidate(
        val source: String,
        val mediaId: String,
        val contentUri: String,
        val originalName: String,
        val sha256: String
    )

    data class Pending(
        val source: String,
        val mediaId: String,
        val uri: String,
        val remotePath: String,
        val attempts: Int
    )
    data class Summary(val pending: Int, val uploaded: Int, val lastError: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cloud_media (
                 source TEXT NOT NULL,
                 media_id TEXT NOT NULL,
                 content_uri TEXT NOT NULL,
                 sha256 TEXT NOT NULL,
                 remote_path TEXT NOT NULL,
                 state TEXT NOT NULL DEFAULT 'pending',
                 attempts INTEGER NOT NULL DEFAULT 0,
                 last_attempt INTEGER NOT NULL DEFAULT 0,
                 last_error TEXT,
                 PRIMARY KEY(source, media_id)
               )"""
        )
        db.execSQL("CREATE INDEX cloud_next ON cloud_media(state, last_attempt)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun reconcile(items: List<Candidate>, directory: String) {
        require(TeraboxSettings.validDirectory(directory)) { "Invalid cloud destination" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                if (item.contentUri.isBlank() || item.sha256.length != 64) continue
                val remote = FlatCloudNaming.path(
                    directory,
                    FlatCloudNaming.fileName(item.source, item.mediaId, item.sha256, item.originalName)
                )
                db.insertWithOnConflict(
                    "cloud_media", null,
                    ContentValues().apply {
                        put("source", item.source)
                        put("media_id", item.mediaId)
                        put("content_uri", item.contentUri)
                        put("sha256", item.sha256)
                        put("remote_path", remote)
                        put("state", "pending")
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                // Re-queue only on changed bytes or changed target folder, not every scan.
                db.execSQL(
                    """UPDATE cloud_media SET content_uri=?, sha256=?, remote_path=?,
                         state='pending', attempts=0, last_attempt=0, last_error=NULL
                         WHERE source=? AND media_id=?
                           AND (sha256<>? OR remote_path<>?)""",
                    arrayOf<Any>(
                        item.contentUri, item.sha256, remote, item.source, item.mediaId,
                        item.sha256, remote
                    )
                )
                db.execSQL(
                    """UPDATE cloud_media SET content_uri=?
                       WHERE source=? AND media_id=? AND content_uri<>?""",
                    arrayOf<Any>(item.contentUri, item.source, item.mediaId, item.contentUri)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun nextReady(maxCount: Int, timestamp: Long): List<Pending> {
        val out = mutableListOf<Pending>()
        readableDatabase.query(
            "cloud_media",
            arrayOf("source", "media_id", "content_uri", "remote_path", "attempts"),
            "state='pending' AND (last_attempt=0 OR last_attempt<=?)",
            arrayOf((timestamp - 15L * 60_000).toString()),
            null, null, "last_attempt ASC, source ASC, media_id ASC",
            maxCount.coerceIn(1, 1000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Pending(
                    cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getInt(4)
                )
            }
        }
        return out
    }

    fun success(item: Pending) {
        writableDatabase.update(
            "cloud_media",
            ContentValues().apply {
                put("state", "uploaded")
                putNull("last_error")
                put("last_attempt", System.currentTimeMillis())
            },
            "source=? AND media_id=? AND remote_path=? AND content_uri=?",
            arrayOf(item.source, item.mediaId, item.remotePath, item.uri)
        )
    }

    fun error(item: Pending, message: String) {
        writableDatabase.execSQL(
            """UPDATE cloud_media SET attempts=attempts+1, last_attempt=?,
                 last_error=? WHERE source=? AND media_id=? AND state='pending'""",
            arrayOf<Any>(
                System.currentTimeMillis(), message.take(200), item.source, item.mediaId
            )
        )
    }

    fun summary(): Summary {
        var waiting = 0
        var finished = 0
        readableDatabase.rawQuery(
            "SELECT state, COUNT(*) FROM cloud_media GROUP BY state", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == "uploaded") finished += cursor.getInt(1)
                else waiting += cursor.getInt(1)
            }
        }
        val last = readableDatabase.query(
            "cloud_media", arrayOf("last_error"), "last_error IS NOT NULL",
            null, null, null, "last_attempt DESC", "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return Summary(waiting, finished, last)
    }
}
