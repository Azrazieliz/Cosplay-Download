package com.azrael.galleryflow

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Android 12+ background launches cannot assume they may start an FGS. Keep
 * periodic work inside JobScheduler's actual job lifecycle and cooperate with
 * onStopJob. The user's foreground buttons still use SyncForegroundService.
 */
class SyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingRun: Future<*>? = null
    @Volatile private var jobActive = false

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null || !Prefs.autoSync(this)) return false

        if (SyncControl.snapshot().running) {
            // The next periodic occurrence handles new work after a foreground run.
            return false
        }
        jobActive = true
        pendingRun = executor.submit {
            try {
                SyncEngine(applicationContext).run(SyncMode.LIVE)
            } finally {
                if (jobActive) jobFinished(params, false)
                jobActive = false
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        if (pendingRun?.isDone == false) {
            jobActive = false
            SyncControl.stop()
            pendingRun?.cancel(true)
            return true // OS may reschedule unfinished periodic work.
        }
        return false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
