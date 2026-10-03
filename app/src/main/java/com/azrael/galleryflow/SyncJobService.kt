package com.azrael.galleryflow

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (!Prefs.autoSync(this)) return false
        startForegroundService(
            Intent(this, SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_START)
                .putExtra(SyncForegroundService.EXTRA_MODE, SyncMode.LIVE.name)
        )
        jobFinished(params, false)
        return false
    }
    override fun onStopJob(params: JobParameters?): Boolean = true
}
