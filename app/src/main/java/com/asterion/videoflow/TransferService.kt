package com.asterion.videoflow

import android.app.*
import android.content.*
import android.os.*
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest

object VideoEngine {
    private val busy=AtomicBoolean(false)
    private val executor=Executors.newSingleThreadExecutor()
    const val CHANNEL="videoflow-transfers"
    fun makeChannel(context: Context) {
        val channel=NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    fun notification(context: Context, message: String): Notification {
        makeChannel(context)
        val open=PendingIntent.getActivity(context, 0, Intent(context,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context,CHANNEL).setContentTitle("VideoFlow")
            .setContentText(message).setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setContentIntent(open).build()
    }
    fun launch(context: Context, scan: Boolean = false): Boolean {
        val intent=Intent(context,TransferService::class.java).putExtra("scan",scan)
        return try {
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: Exception) {
            false // JobService can use its own job execution when FGS launch is restricted.
        }
    }
    fun work(context: Context, scan: Boolean, onStatus: (String)->Unit = {}) {
        if(!busy.compareAndSet(false,true)) return
        try {
            val db=VideoDb(context)
            db.recover()
            try { YtDlp.init(context.applicationContext) }
            catch(t:Exception) {
                db.next()?.let { db.update(it.id,"error",error="Video engine unavailable: ${t.message}") }
                onStatus("Video engine initialization failed")
                return
            }
            if(scan) {
                runCatching {
                    PerformerScanner.scan(context,db,PerformerDb(context),onStatus)
                }.onFailure { onStatus("Performer scan issue: ${it.message?.take(100)}") }
                val settings=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
                val max=settings.getInt("max_new",3).coerceIn(1,20)
                for(watch in db.listWatches().filter { it.enabled }) {
                    try {
                        onStatus("Scanning ${watch.label}")
                        val hits=WebDiscovery.discover(watch)
                        if(hits.isEmpty()) error("No video links found on this page; use a specific feed, profile, or search watch")
                        var added=0
                        for(hit in hits) {
                            if(db.addVideo(hit.url,hit.title,watch.id)) added++
                            if(added>=max) break
                        }
                        db.checked(watch.id,null)
                    } catch(t:Exception) {
                        db.checked(watch.id,t.message?: "Discovery failed")
                    }
                }
            }
            while(true) {
                val next=db.next() ?: break
                onStatus("Downloading ${next.title}")
                db.update(next.id,"running")
                try {
                    val uri=downloadOne(context,db,next) { percent ->
                        onStatus("${next.title}: $percent%")
                    }
                    db.update(next.id,"done",100,uri)
                    onStatus("Saved ${next.title}")
                } catch(t:Exception) {
                    db.update(next.id,"error",error=t.message?:t.javaClass.simpleName)
                    onStatus("Failed ${next.title}")
                }
            }
            if(context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getBoolean("cloud_after",false)) {
                val id=context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getInt("cloud_task",-1)
                if(id>=0) runCatching {
                    val intent=Intent("START_TASK").apply {
                        setClassName("de.felixnuesse.extract","ca.pkay.rcloneexplorer.Services.SyncService")
                        putExtra("task",id);putExtra("notification",true)
                    }
                    context.startForegroundService(intent)
                }
            }
        } finally { busy.set(false) }
    }
    fun submit(context: Context, scan: Boolean, onStatus: (String)->Unit = {}) =
        executor.submit { work(context,scan,onStatus) }
    private fun downloadOne(context: Context, db: VideoDb, item: Video, status:(Int)->Unit): String {
        val parent=File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),"staging/${item.id}")
        parent.mkdirs()
        val template=File(parent,"%(title).160s [%(id)s].%(ext)s").absolutePath
        val prefs=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        val cookie=File(context.filesDir,"cookies.txt")
        fun request(link: String) = YtDlpRequest(link).setOutputTemplate(template)
            .addOption("--no-playlist").addOption("--continue").addOption("--no-overwrites")
            .addOption("--retries","5").addOption("--fragment-retries","5")
            .addOption("--socket-timeout","25")
            .addOption("-f",prefs.getString("quality","best[ext=mp4]/best") ?: "best[ext=mp4]/best")
            .apply { if(cookie.isFile && cookie.length()>0) addOption("--cookies",cookie.absolutePath) }
        fun execute(link: String) {
            val res=YtDlp.execute(request(link)) { p, _, _ ->
                val percent=p.toInt().coerceIn(0,100)
                db.progress(item.id,percent)
                if(percent%5==0) status(percent)
            }
            if(!res.isSuccess) throw IllegalStateException("Extractor finished with code ${res.exitCode}")
        }
        try {
            execute(item.url)
        } catch(initial:Exception) {
            val direct=runCatching { WebDiscovery.directMedia(item.url) }.getOrNull()
            if(direct.isNullOrBlank() || direct==item.url) throw initial
            execute(direct)
        }
        val file=parent.listFiles()?.filter { it.isFile && it.length()>0 &&
            it.extension.lowercase() in setOf("mp4","mkv","webm","mov","ts") }
            ?.maxByOrNull { it.length() } ?: error("No completed playable video found")
        val name=file.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(210)
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name)
            put(MediaStore.Downloads.MIME_TYPE,when(file.extension.lowercase()){
                "mp4"->"video/mp4";"webm"->"video/webm";"mkv"->"video/x-matroska"; else->"video/*"
            })
            put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/VideoFlow/")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
            ?: error("Cannot create Downloads/VideoFlow file")
        try {
            context.contentResolver.openOutputStream(uri,"w")!!.use { output ->
                file.inputStream().buffered().use { input -> input.copyTo(output,1024*256) }
            }
            val size=context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: -1
            if(size>=0 && size!=file.length()) error("Copy verification failed")
            context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            parent.listFiles()?.forEach { it.delete() }
            parent.delete()
            return uri.toString()
        } catch(t:Exception) {
            context.contentResolver.delete(uri,null,null)
            throw t
        }
    }
}

class TransferService: Service() {
    private val binder=Binder()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VideoEngine.makeChannel(this)
        startForeground(24014,VideoEngine.notification(this,"Preparing downloads"))
        VideoEngine.submit(applicationContext,intent?.getBooleanExtra("scan",false)==true) { text ->
            getSystemService(NotificationManager::class.java)
                .notify(24014,VideoEngine.notification(this,text.take(80)))
        }.let { future ->
            Thread {
                runCatching { future.get() }
                stopSelfResult(startId)
            }.start()
        }
        return START_REDELIVER_INTENT
    }
    override fun onDestroy() { super.onDestroy() }
}
