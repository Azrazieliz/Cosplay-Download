package com.azrael.galleryflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SyncForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.azrael.galleryflow.START"
        const val ACTION_PAUSE = "com.azrael.galleryflow.PAUSE"
        const val ACTION_RESUME = "com.azrael.galleryflow.RESUME"
        const val ACTION_STOP = "com.azrael.galleryflow.STOP"
        const val EXTRA_MODE = "sync_mode"
        private const val CHANNEL_ID = "galleryflow_sync"
        private const val NOTIFICATION_ID = 91
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var currentRun: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kyora sync", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { SyncControl.pause(); notifyNow(); return START_NOT_STICKY }
            ACTION_RESUME -> { SyncControl.resume(); notifyNow(); return START_NOT_STICKY }
            ACTION_STOP -> {
                SyncControl.stop()
                currentRun?.cancel(true)
                notifyNow()
                return START_NOT_STICKY
            }
        }

        val mode = runCatching { SyncMode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: SyncMode.LIVE.name) }
            .getOrDefault(SyncMode.LIVE)
        if (currentRun?.isDone == false) {
            if (mode == SyncMode.LIVE) SyncControl.tryStart(SyncMode.LIVE, queueLiveIfBusy = true)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())
        currentRun = executor.submit {
            try {
                SyncEngine(applicationContext).run(mode) { notifyNow() }
            } finally {
                notifyNow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                currentRun = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val s = SyncControl.snapshot()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = if (s.paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (s.paused) "Resume" else "Pause"
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, SyncForegroundService::class.java).setAction(toggleAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Kyora")
            .setContentText(s.message.take(180))
            .setContentIntent(open)
            .setOngoing(s.running)
            .addAction(Notification.Action.Builder(null, toggleLabel, toggle).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
