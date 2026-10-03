package com.azrael.galleryflow

import android.content.Context

object Prefs {
    private const val NAME = "galleryflow_prefs"
    private const val AUTO_SYNC = "auto_sync"
    fun autoSync(context: Context): Boolean = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(AUTO_SYNC, true)
    fun setAutoSync(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(AUTO_SYNC, value).apply()
    }
}
