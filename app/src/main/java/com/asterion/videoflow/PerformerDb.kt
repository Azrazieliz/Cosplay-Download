package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PerformerWatch(
    val id: Long, val name: String, val sites: String, val minSeconds: Int,
    val enabled: Boolean, val cursor: Int, val checked: Long, val status: String?
) {
    fun domains(): List<String> {
        val requested=sites.split(',').map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotBlank() }
        return if(requested.isEmpty() || requested.contains("*")) AdultSiteCatalog.sites.map { it.host }
            else requested.distinct().filter { AdultSiteCatalog.isAllowedHost(it) }
    }
}

class PerformerDb(context: Context): SQLiteOpenHelper(context, "videoflow_performers.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE performer_watches(
            id INTEGER PRIMARY KEY, performer TEXT NOT NULL, site_selection TEXT NOT NULL DEFAULT '*',
            min_seconds INTEGER NOT NULL DEFAULT 600, enabled INTEGER NOT NULL DEFAULT 1,
            scan_cursor INTEGER NOT NULL DEFAULT 0, checked_at INTEGER NOT NULL DEFAULT 0,
            last_status TEXT, UNIQUE(performer, site_selection))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion:Int, newVersion:Int) {}
    fun add(name:String, sites:String="*", minMinutes:Int=10): Boolean {
        val trimmed=name.trim().replace(Regex("\\s+")," ").take(100)
        if(trimmed.length<2 || trimmed.startsWith("http",true)) return false
        val source=sites.trim().ifBlank { "*" }.lowercase()
        if(source!="*" && source.split(',').any {
            !AdultSiteCatalog.isAllowedHost(it.trim().removePrefix("www."))
        }) return false
        val row=ContentValues().apply {
            put("performer",trimmed);put("site_selection",source)
            put("min_seconds",minMinutes.coerceIn(1,360)*60)
        }
        return writableDatabase.insertWithOnConflict("performer_watches",null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun list():List<PerformerWatch> {
        val out=ArrayList<PerformerWatch>()
        readableDatabase.rawQuery(
            "SELECT id,performer,site_selection,min_seconds,enabled,scan_cursor,checked_at,last_status FROM performer_watches ORDER BY performer COLLATE NOCASE",null
        ).use { c ->
            while(c.moveToNext()) out+=PerformerWatch(
                c.getLong(0),c.getString(1),c.getString(2),c.getInt(3),c.getInt(4)==1,
                c.getInt(5),c.getLong(6),c.getString(7)
            )
        }
        return out
    }
    fun edit(id:Long,name:String,sites:String,minMinutes:Int):Boolean {
        val clean=name.trim().replace(Regex("\\s+")," ").take(100)
        if(clean.length<2 || clean.startsWith("http",true))return false
        val selection=sites.trim().lowercase().ifBlank{"*"}
        if(selection!="*" && selection.split(',').any{
            !AdultSiteCatalog.isAllowedHost(it.trim().removePrefix("www."))
        })return false
        val values=ContentValues().apply {
            put("performer",clean)
            put("site_selection",selection)
            put("min_seconds",minMinutes.coerceIn(1,360)*60)
            put("scan_cursor",0)
            putNull("last_status")
        }
        return runCatching{
            writableDatabase.update("performer_watches",values,"id=?",arrayOf(id.toString()))>0
        }.getOrDefault(false)
    }
    fun remove(id:Long) { writableDatabase.delete("performer_watches","id=?",arrayOf(id.toString())) }
    fun setEnabled(id:Long,value:Boolean) {
        writableDatabase.update("performer_watches",ContentValues().apply {
            put("enabled",if(value)1 else 0)
        },"id=?",arrayOf(id.toString()))
    }
    fun record(id:Long,cursor:Int,summary:String) {
        writableDatabase.update("performer_watches",ContentValues().apply {
            put("scan_cursor",cursor.coerceAtLeast(0))
            put("last_status",summary.take(400))
            put("checked_at",System.currentTimeMillis())
        },"id=?",arrayOf(id.toString()))
    }
}