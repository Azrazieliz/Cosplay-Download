package com.asterion.videoflow

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity: Activity() {
    private val bg=Color.rgb(11,14,24)
    private val panel=Color.rgb(23,28,43)
    private val field=Color.rgb(33,39,57)
    private val textColor=Color.rgb(243,246,255)
    private val muted=Color.rgb(158,170,192)
    private val accent=Color.rgb(123,112,255)
    private val ok=Color.rgb(89,211,166)
    private val bad=Color.rgb(247,126,144)
    private lateinit var db: VideoDb
    private lateinit var performerDb: PerformerDb
    private lateinit var performers: LinearLayout
    private lateinit var performerInput: EditText
    private lateinit var performerSites: EditText
    private lateinit var performerLength: EditText
    private lateinit var watches: LinearLayout
    private lateinit var queue: LinearLayout
    private lateinit var stats: TextView
    private lateinit var searchBox: EditText
    private lateinit var sourceBox: EditText
    private lateinit var results: LinearLayout
    private val work=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private fun dp(value:Int): Int=(value*resources.displayMetrics.density).toInt()
    private fun shape(color:Int, radius:Int=16): GradientDrawable = GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun txt(value:String,size:Float=14f,color:Int=textColor,bold:Boolean=false):TextView=TextView(this).apply {
        text=value;setTextColor(color);textSize=size
        if(bold) setTypeface(null,Typeface.BOLD)
    }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun padding(v:View,a:Int=16,b:Int=12) {v.setPadding(dp(a),dp(b),dp(a),dp(b))}
    private fun gap(h:Int=10)=Space(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(h)) }
    private fun card(): LinearLayout=column().apply { background=shape(panel);padding(this,16,14) }
    private fun input(hintText:String):EditText=EditText(this).apply {
        hint=hintText;setHintTextColor(muted);setTextColor(textColor);textSize=14f
        background=shape(field,12);padding(this,14,12);setSingleLine(true)
    }
    private fun action(label:String,emphasis:Boolean=true,run:()->Unit):TextView = txt(label,12f,if(emphasis)Color.WHITE else textColor,true).apply {
        gravity=Gravity.CENTER;background=shape(if(emphasis) accent else field,11);padding(this,13,11)
        setOnClickListener { run() }
    }
    private fun row(vararg views:View):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        views.forEach { child -> addView(child, LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply {marginEnd=dp(7)}) }
    }
    private fun heading(label:String): TextView=txt(label,17f,textColor,true)
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        db=VideoDb(this);db.seed()
        performerDb=PerformerDb(this)
        Scheduling.ensure(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),109)
        render()
        if(intent?.action==Intent.ACTION_SEND) {
            val shared=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if(shared.isNotBlank()) searchBox.setText(shared)
        }
    }
    override fun onResume() {
        super.onResume();active=true;refresh()
        handler.postDelayed(pulse,4500)
    }
    override fun onPause(){active=false;super.onPause()}
    private val pulse=object:Runnable {
        override fun run() { if(active) { refresh();handler.postDelayed(this,4500) } }
    }
    private fun render(){
        val base=column().apply{setBackgroundColor(bg);padding(this,18,22)}
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(base)}
        setContentView(scroll)
        val over=txt("ASTERION  /  MEDIA TOOLS",11f,accent,true)
        base.addView(over);base.addView(gap(7))
        base.addView(txt("VideoFlow",30f,textColor,true))
        base.addView(txt("AUTOMATED VIDEO ARCHIVER",12f,muted))
        base.addView(gap(18))
        stats=txt("Checking library...",13f,ok,true);base.addView(stats)
        base.addView(gap(18))
        val add=card()
        add.addView(heading("Search or paste a video URL"))
        add.addView(gap(7))
        add.addView(txt("Search for videos across websites or download an exact link.",12f,muted))
        add.addView(gap(12))
        searchBox=input("Video URL or search words")
        add.addView(searchBox);add.addView(gap(10))
        add.addView(row(
            action("DOWNLOAD / AUTO SEARCH") { submitInput(true) },
            action("SEARCH RESULTS",false) { submitInput(false) }
        ))
        results=column();add.addView(results)
        base.addView(add);base.addView(gap(16))
        val control=card()
        control.addView(heading("Automation"))
        val prefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val auto=Switch(this).apply {
            text="Background source checks (15-minute minimum)"
            setTextColor(textColor); textSize=13f
            isChecked=prefs.getBoolean("auto",true)
            setOnCheckedChangeListener { _,enabled -> Scheduling.configure(this@MainActivity,enabled);toast(if(enabled)"Automation enabled" else "Automation disabled") }
        }
        control.addView(auto);control.addView(gap(9))
        control.addView(row(action("SCAN SAVED SOURCES") {VideoEngine.launch(this,true);toast("Discovery and downloads requested")},
            action("QUEUE DOWNLOADS",false) { VideoEngine.launch(this) }))
        control.addView(gap(10))
        control.addView(row(
            action("DOWNLOAD SETTINGS",false){settings()},
            action("ROUND SYNC / TERABOX",false){cloudSettings()}
        ))
        base.addView(control);base.addView(gap(20))
        base.addView(heading("Performer monitoring"));base.addView(gap(9))
        val watchCard=card()
        watchCard.addView(txt("All ${AdultSiteCatalog.sites.size} listed sites are included in a new performer watch by default. Sources rotate between checks; only duration-confirmed full videos enter the automatic queue.",12f,muted))
        watchCard.addView(gap(12))
        performerInput=input("Performer / model name")
        performerSites=input("Sites: * for all, or comma-separated domains").apply { setText("*") }
        performerLength=input("Minimum duration (minutes)").apply {
            setText("10");inputType=InputType.TYPE_CLASS_NUMBER
        }
        watchCard.addView(performerInput);watchCard.addView(gap(8))
        watchCard.addView(performerSites);watchCard.addView(gap(8))
        watchCard.addView(performerLength);watchCard.addView(gap(8))
        watchCard.addView(row(action("+ FOLLOW PERFORMER") {
            val name=performerInput.text.toString()
            val selection=performerSites.text.toString()
            val minutes=performerLength.text.toString().toIntOrNull()?:10
            if(performerDb.add(name,selection,minutes)) {
                performerInput.text.clear();refresh()
            } else toast("Invalid or duplicate performer watch")
        },action("SUPPORTED SITES",false) {showSites()}))
        base.addView(watchCard);base.addView(gap(8))
        performers=column();base.addView(performers)
        base.addView(gap(20))
        base.addView(heading("Watched sources"));base.addView(gap(10))
        val source=card()
        sourceBox=input("Profile, category, feed URL or search words")
        source.addView(sourceBox);source.addView(gap(9))
        source.addView(action("+ ADD SOURCE",false){
            val raw=sourceBox.text.toString().trim()
            if(raw.isBlank()) return@action
            val address=if(WebDiscovery.isUrl(raw))raw else "search:$raw"
            if(db.addWatch(address,if(address.startsWith("search:"))"Search: $raw" else Uri.parse(raw).host?:"Source")) {
                sourceBox.text.clear();refresh()
            } else toast("Source is already saved or invalid")
        })
        base.addView(source);base.addView(gap(10))
        watches=column();base.addView(watches)
        base.addView(gap(20))
        base.addView(heading("Transfer queue"));base.addView(gap(10))
        queue=column();base.addView(queue)
        base.addView(gap(30))
        base.addView(txt("Downloads: Files → Downloads/VideoFlow. Source failures are recorded; protected media is not decrypted.",11f,muted))
        refresh()
    }
    private fun showSites() {
        val group=AdultSiteCatalog.sites.groupBy { it.extractor }
        val lines=group.entries.joinToString("\n\n") { (status,items) ->
            status.uppercase()+"\n"+items.joinToString(", ") { it.label }
        }
        val view=ScrollView(this)
        val label=txt(lines,13f,Color.BLACK)
        padding(label,18,14);view.addView(label)
        AlertDialog.Builder(this).setTitle("${AdultSiteCatalog.sites.size} discovery sites")
            .setMessage("Every site is searchable. Dedicated extractors are not guarantees; unknown-duration clips are excluded from automatic full-video downloads.")
            .setView(view).setPositiveButton("Close",null).show()
    }
    private fun refresh(){
        if(!::queue.isInitialized || !::watches.isInitialized || !::performers.isInitialized)return
        performers.removeAllViews()
        for(w in performerDb.list()) {
            val c=card()
            c.addView(row(txt(w.name,15f,textColor,true),action(if(w.enabled)"MONITORING" else "PAUSED",w.enabled) {
                performerDb.setEnabled(w.id,!w.enabled);refresh()
            }))
            c.addView(gap(5))
            c.addView(txt("Full videos >= ${w.minSeconds/60} min  ·  ${w.domains().size} sites",12f,muted))
            if(w.status!=null)c.addView(txt("Last scan: ${w.status}",11f,muted))
            c.addView(gap(7))
            c.addView(action("REMOVE PERFORMER",false) {
                AlertDialog.Builder(this).setMessage("Stop monitoring ${w.name}?")
                    .setNegativeButton("Cancel",null).setPositiveButton("Remove"){_,_->
                        performerDb.remove(w.id);refresh()
                    }.show()
            })
            performers.addView(c);performers.addView(gap(8))
        }
        stats.text="${db.count("queued")} queued     •     ${db.count("running")} downloading     •     ${db.count("done")} saved     •     ${db.count("error")} errors"
        watches.removeAllViews()
        for(w in db.listWatches()){
            val c=card()
            val label=row(
                txt(w.label,14f,textColor,true),
                action(if(w.enabled)"MONITORING" else "PAUSED",w.enabled) {
                    db.enableWatch(w.id,!w.enabled);refresh()
                }
            )
            c.addView(label)
            c.addView(txt(w.address,11f,muted))
            if(w.error!=null)c.addView(txt("Last check: ${w.error}",11f,bad))
            c.addView(gap(7))
            c.addView(action("REMOVE SOURCE",false){
                AlertDialog.Builder(this).setMessage("Remove ${w.label} from monitoring?").setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove"){_,_->db.removeWatch(w.id);refresh()}.show()
            })
            watches.addView(c);watches.addView(gap(8))
        }
        queue.removeAllViews()
        val all=db.listVideos(80)
        if(all.isEmpty())queue.addView(txt("No downloads yet. Paste a link, search, or run a source check.",13f,muted))
        for(v in all) {
            val c=card()
            val color=when(v.status){"done"->ok;"error"->bad;"running"->accent;else->muted}
            c.addView(txt(v.title,13f,textColor,true))
            c.addView(gap(5))
            c.addView(txt("${v.status.uppercase()}   ${if(v.status=="running")"${v.progress}%" else ""}",11f,color,true))
            if(v.status=="running") c.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100;progress=v.progress
            })
            if(v.status=="error")c.addView(txt(v.error?:"Unknown error",11f,bad))
            c.addView(gap(7))
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(v.status=="done" && v.savedUri!=null)actions.addView(action("OPEN",false){
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(v.savedUri),"video/*");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }) }.onFailure{toast(it.message?:"No video viewer")}
            })
            if(v.status=="error")actions.addView(action("RETRY",false){
                db.retry(v.id);VideoEngine.launch(this);refresh()
            })
            actions.addView(action("REMOVE",false){db.delete(v.id);refresh()})
            c.addView(actions)
            queue.addView(c);queue.addView(gap(8))
        }
    }
    private fun submitInput(auto:Boolean){
        val input=searchBox.text.toString().trim()
        if(input.isBlank()){toast("Enter a video URL or search phrase");return}
        if(WebDiscovery.isUrl(input)){
            if(db.addVideo(input,input)){VideoEngine.launch(this);toast("Video queued")}
            else toast("Already queued, or invalid URL")
            refresh();return
        }
        results.removeAllViews()
        results.addView(gap(8));results.addView(txt("Searching websites...",12f,muted))
        work.submit{
            val found=WebDiscovery.search(input,12)
            runOnUiThread {
                results.removeAllViews();results.addView(gap(12))
                if(found.isEmpty()) {
                    results.addView(txt("No matching video links found. Try a direct URL or more specific keywords.",12f,bad))
                    return@runOnUiThread
                }
                if(auto) {
                    val first=found.first()
                    if(db.addVideo(first.url,first.title))VideoEngine.launch(this)
                    toast("Best matching result queued")
                    refresh()
                }
                found.take(8).forEach { hit ->
                    val c=column()
                    c.addView(txt(hit.title,12f,textColor,true))
                    c.addView(txt(hit.url,10f,muted))
                    c.addView(gap(5))
                    c.addView(action("DOWNLOAD THIS RESULT",false){
                        if(db.addVideo(hit.url,hit.title))VideoEngine.launch(this)
                        refresh()
                    })
                    results.addView(c);results.addView(gap(9))
                }
            }
        }
    }
    private fun settings(){
        val p=getSharedPreferences("video_options",MODE_PRIVATE)
        val container=column().apply{padding(this,12,6)}
        container.addView(txt("Max new videos per watched source, per scan",13f,Color.DKGRAY))
        val max=input("3").apply{
            setTextColor(Color.BLACK);setHintTextColor(Color.GRAY);inputType=InputType.TYPE_CLASS_NUMBER
            setText(p.getInt("max_new",3).toString())
        }
        container.addView(max);container.addView(gap(10))
        container.addView(txt("Download format",13f,Color.DKGRAY))
        val qualities=listOf("Best compatible MP4 or single-file","Best available single-file")
        val spinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,qualities)
            setSelection(if(p.getString("quality","")=="best")1 else 0)
        }
        container.addView(spinner);container.addView(gap(10))
        container.addView(action("IMPORT NETSCAPE COOKIE FILE",false){pickCookies()})
        container.addView(txt("Cookies are stored privately on-device. Only import cookies from accounts you are authorized to access.",12f,Color.DKGRAY))
        AlertDialog.Builder(this).setTitle("Download settings").setView(container).setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                p.edit().putInt("max_new",max.text.toString().toIntOrNull()?.coerceIn(1,20)?:3)
                    .putString("quality",if(spinner.selectedItemPosition==1)"best" else "best[ext=mp4]/best").apply()
                toast("Settings saved")
            }.show()
    }
    private fun pickCookies(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
        },551)
    }
    @Deprecated("Legacy SAF result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==551 && resultCode==RESULT_OK){
            val uri=data?.data ?: return
            runCatching {
                val dst=File(filesDir,"cookies.txt")
                contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
                require(dst.length()>0){"Cookie file is empty"}
            }.onSuccess{toast("Cookie file imported")}.onFailure{toast("Cookie import failed: ${it.message}")}
        }
    }
    private fun cloudSettings(){
        val p=getSharedPreferences("video_options",MODE_PRIVATE)
        val c=column().apply{padding(this,12,6)}
        c.addView(txt("Configure a Round Sync COPY task first. Local source: Downloads/VideoFlow/; destination: your TeraBox folder. Use COPY, not mirror/delete.",13f,Color.DKGRAY))
        c.addView(gap(7))
        val check=CheckBox(this).apply{ text="Request cloud backup after downloads";isChecked=p.getBoolean("cloud_after",false)}
        c.addView(check)
        val field=input("Round Sync task ID").apply{setTextColor(Color.BLACK);inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            val id=p.getInt("cloud_task",-1);if(id>=0)setText(id.toString())
        }
        c.addView(field)
        c.addView(txt("Round Sync manages its own authentication. Requesting a task does not prove upload completion.",12f,Color.DKGRAY))
        AlertDialog.Builder(this).setTitle("Round Sync / TeraBox").setView(c).setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                p.edit().putBoolean("cloud_after",check.isChecked)
                    .putInt("cloud_task",field.text.toString().toIntOrNull()?:-1).apply()
                toast("Cloud task settings saved")
            }.show()
    }
}
