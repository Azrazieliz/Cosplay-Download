package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

class ExportManager(private val context: Context) {
    fun exportJson(): String = GalleryDb(context).use { db ->
        val root = JSONObject()
        db.exportTables().forEach { (table, rows) ->
            val array = JSONArray()
            rows.forEach { row ->
                val obj = JSONObject()
                row.forEach { (key, value) -> obj.put(key, value ?: JSONObject.NULL) }
                array.put(obj)
            }
            root.put(table, array)
        }
        write("galleryflow-catalog.json", "application/json", root.toString(2))
    }

    fun exportCsv(): String = GalleryDb(context).use { db ->
        val rows = db.exportTables()["media"].orEmpty()
        val headers = rows.flatMap { it.keys }.distinct()
        val text = buildString {
            append(headers.joinToString(",") { csv(it) }).append('\n')
            rows.forEach { row ->
                append(headers.joinToString(",") { csv(row[it]?.toString().orEmpty()) }).append('\n')
            }
        }
        write("galleryflow-media.csv", "text/csv", text)
    }

    private fun write(name: String, mime: String, text: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GalleryFlow/exports")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create export")
        context.contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(text) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null
        )
        return uri.toString()
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
