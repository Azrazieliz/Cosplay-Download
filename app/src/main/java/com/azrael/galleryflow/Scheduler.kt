package com.azrael.galleryflow

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object Scheduler {
    private const val PERIODIC_JOB_ID = 9811
    private const val FIFTEEN_MINUTES = 15L * 60L * 1000L

    fun ensure(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!Prefs.autoSync(context)) {
            scheduler.cancel(PERIODIC_JOB_ID)
            return
        }
        if (scheduler.allPendingJobs.any { it.id == PERIODIC_JOB_ID }) return
        scheduler.schedule(
            JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(FIFTEEN_MINUTES)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(PERIODIC_JOB_ID)
    }
}
