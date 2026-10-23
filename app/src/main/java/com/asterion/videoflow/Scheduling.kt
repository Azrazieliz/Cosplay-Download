package com.asterion.videoflow

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

object Scheduling {
    private const val ID=42681
    fun configure(context: Context, enabled: Boolean) {
        context.getSharedPreferences("video_options",Context.MODE_PRIVATE).edit().putBoolean("auto",enabled).apply()
        val scheduler=context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(ID)
        if(enabled) scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,PollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15*60*1000L)
            .setBackoffCriteria(60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun ensure(context: Context) {
        if(context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getBoolean("auto",true)) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(scheduler.allPendingJobs.none { it.id==ID }) configure(context,true)
        }
    }
}
class PollJobService: JobService() {
    private val exec=Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters?): Boolean {
        if(!getSharedPreferences("video_options",MODE_PRIVATE).getBoolean("auto",true)) return false
        if(VideoEngine.launch(applicationContext,true)) return false
        exec.submit {
            // JobScheduler can run this even when starting a foreground service is restricted.
            runCatching { VideoEngine.work(applicationContext,true) }
            jobFinished(params,false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean = true
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if(intent?.action==Intent.ACTION_BOOT_COMPLETED) Scheduling.ensure(context)
    }
}
