package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

data class Watch(val id: Long, val address: String, val label: String, val enabled: Boolean, val lastCheck: Long, val error: String?)
data class Video(val id: Long, val url: String, val title: String, val status: String, val progress: Int, val savedUri: String?, val error: String?, val watchId: Long)

class VideoDb(context: Context): SQLiteOpenHelper(context, "videoflow.sqlite", null, 1) {
    private val appContext = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watches(id INTEGER PRIMARY KEY, address TEXT UNIQUE NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_check INTEGER NOT NULL DEFAULT 0, error TEXT)")
        db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'queued', progress INTEGER NOT NULL DEFAULT 0, saved_uri TEXT, error TEXT, watch_id INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX video_queue ON videos(state, updated)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun seed() {
        val prefs=appContext.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        if (prefs.getBoolean("sources_seeded",false)) return
        listOf(
            "https://www.pornhub.com/" to "Pornhub",
            "https://notfans.com/" to "NotFans",
            "https://archivebate.com/" to "ArchiveBate",
            "https://www.freepornvideos.xxx/" to "FreePornVideos"
        ).forEach { pair ->
            addWatch(pair.first, pair.second)
            listWatches().firstOrNull { it.address == pair.first }?.let { setLiveSeedPaused(it.id) }
        }
        prefs.edit().putBoolean("sources_seeded",true).apply()
    }
    fun addWatch(address: String, label: String = address): Boolean {
        val normalized = address.trim()
        if (!WebDiscovery.isUrl(normalized) && !normalized.startsWith("search:")) return false
        val row = ContentValues().apply {
            put("address", normalized); put("label", label.take(120))
        }
        return writableDatabase.insertWithOnConflict("watches", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    private fun setLiveSeedPaused(id: Long) {
        writableDatabase.update("watches", ContentValues().apply { put("enabled",0) }, "id=?",arrayOf("$id"))
    }
    fun removeWatch(id: Long) { writableDatabase.delete("watches", "id=?", arrayOf("$id")) }
    fun enableWatch(id: Long, enabled: Boolean) {
        writableDatabase.update("watches", ContentValues().apply { put("enabled", if(enabled) 1 else 0) }, "id=?", arrayOf("$id"))
    }
    fun listWatches(): List<Watch> {
        val out = ArrayList<Watch>()
        readableDatabase.rawQuery("SELECT id,address,label,enabled,last_check,error FROM watches ORDER BY id", null).use { c ->
            while(c.moveToNext()) out += Watch(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)==1, c.getLong(4), c.getString(5))
        }
        return out
    }
    fun checked(id: Long, error: String?) {
        writableDatabase.update("watches", ContentValues().apply {
            put("last_check", System.currentTimeMillis())
            if(error==null) putNull("error") else put("error", error.take(300))
        }, "id=?", arrayOf("$id"))
    }
    fun addVideo(url: String, title: String = url, watchId: Long = 0): Boolean {
        val normalized = WebDiscovery.normalize(url) ?: return false
        val row = ContentValues().apply {
            put("url", normalized); put("title", title.take(220))
            put("watch_id", watchId); put("state", "queued")
            put("updated", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("videos", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun next(): Video? = listVideos(200).firstOrNull { it.status == "queued" }
    fun listVideos(limit: Int = 100): List<Video> {
        val out=ArrayList<Video>()
        readableDatabase.rawQuery("SELECT id,url,title,state,progress,saved_uri,error,watch_id FROM videos ORDER BY CASE state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 ELSE 2 END, updated DESC LIMIT ${limit.coerceIn(1,1000)}", null).use { c ->
            while(c.moveToNext()) out += Video(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getString(5),c.getString(6),c.getLong(7))
        }
        return out
    }
    fun count(status: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM videos WHERE state=?", arrayOf(status)).use { c -> c.moveToFirst(); return c.getInt(0) }
    }
    fun update(id: Long, state: String, progress: Int = 0, savedUri: String? = null, error: String? = null) {
        val row = ContentValues().apply {
            put("state", state); put("progress", progress.coerceIn(0,100))
            put("updated", System.currentTimeMillis())
            if(savedUri != null) put("saved_uri", savedUri)
            if(error != null) put("error", error.take(700)) else putNull("error")
        }
        writableDatabase.update("videos", row, "id=?", arrayOf("$id"))
    }
    fun progress(id: Long, percent: Int) {
        writableDatabase.update("videos", ContentValues().apply { put("progress", percent.coerceIn(0,100)) }, "id=?", arrayOf("$id"))
    }
    fun retry(id: Long) { update(id,"queued") }
    fun recover() {
        writableDatabase.execSQL("UPDATE videos SET state='queued', progress=0 WHERE state='running'")
    }
    fun delete(id: Long) { writableDatabase.delete("videos","id=?", arrayOf("$id")) }
}
