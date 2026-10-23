package com.azrael.galleryflow

import android.content.Context

object Prefs {
    private const val NAME = "galleryflow_prefs"
    private const val AUTO_SYNC = "auto_sync"
    private const val EXCLUDED_TAGS = "excluded_tags"
    fun excludedTags(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(EXCLUDED_TAGS, "").orEmpty()
    fun setExcludedTags(context: Context, text: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(EXCLUDED_TAGS, text.trim()).apply()
    }

    fun autoSync(context: Context): Boolean = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(AUTO_SYNC, true)
    fun setAutoSync(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(AUTO_SYNC, value).apply()
    }
}
