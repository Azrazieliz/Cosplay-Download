package com.asterion.videoflow

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.text.DateFormat
import java.util.Date

/** URL-based archiver only: no keyword search, performer indexing, or Tor. */
class MainActivity:Activity() {
    private val bg=Color.rgb(10,14,24)
    private val surface=Color.rgb(20,27,42)
    private val elevated=Color.rgb(30,38,55)
    private val outline=Color.rgb(48,59,79)
    private val primary=Color.rgb(156,146,249)
    private val mint=Color.rgb(105,220,180)
    private val amber=Color.rgb(249,193,111)
    private val bad=Color.rgb(252,126,144)
    private val white=Color.rgb(242,245,254)
    private val grey=Color.rgb(162,174,194)
    private lateinit var db:VideoDb
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var viewport:ScrollView
    private lateinit var navigation:LinearLayout
    private val handler=Handler(Looper.getMainLooper())
    private var live=false
    private var page="home"
    private var filter="All"
    private var inputUrl:String=""
    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun rounded(fill:Int,radius:Int=18,border:Boolean=false)=GradientDrawable().apply{
        setColor(fill);cornerRadius=dp(radius).toFloat()
        if(border)setStroke(dp(1),outline)
    }
    private fun text(value:String,size:Float=14f,color:Int=white,heavy:Boolean=false):TextView=
        TextView(this).apply{
            this.text=value;textSize=size;setTextColor(color);includeFontPadding=true
            if(heavy)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        }
    private fun col():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row():LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
    }
    private fun gap(n:Int)=Space(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(n))}
    private fun pad(v:View,x:Int=16,y:Int=14){v.setPadding(dp(x),dp(y),dp(x),dp(y))}
    private fun card():LinearLayout=col().apply{background=rounded(surface,20,true);pad(this,17,16)}
    private fun add(p:LinearLayout,v:View,top:Int=0) {
        p.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)})
    }
    private fun button(label:String,solid:Boolean=true,click:()->Unit)=text(label,13f,
        if(solid)bg else white,true).apply{
        gravity=Gravity.CENTER;minHeight=dp(48)
        background=rounded(if(solid)primary else elevated,13,!solid)
        pad(this,15,12);setOnClickListener{click()}
    }
    private fun pill(label:String,active:Boolean,click:()->Unit)=text(label,12f,
        if(active)bg else grey,true).apply{
        gravity=Gravity.CENTER;background=rounded(if(active)primary else elevated,24,!active)
        pad(this,10,10);setOnClickListener{click()}
    }
    private fun edit(hintText:String,value:String="")=EditText(this).apply{
        hint=hintText;setText(value);setSingleLine(true);textSize=14f
        setTextColor(white);setHintTextColor(grey)
        background=rounded(elevated,14,true);pad(this,14,13)
        setSelectAllOnFocus(true)
    }
    private fun title(value:String,description:String):LinearLayout=col().apply{
        addView(text(value,26f,white,true))
        addView(gap(5));addView(text(description,13f,grey))
    }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
    private fun statsValue(status:String)=db.count(status).toString()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        db=VideoDb(this)
        Scheduling.ensure(this)
        root=col().apply{setBackgroundColor(bg)}
        setContentView(root)
        val top=row().apply{pad(this,19,12)}
        top.addView(text("▶",22f,primary,true),LinearLayout.LayoutParams(dp(35),-2))
        val appTitle=col()
        appTitle.addView(text("VIDEOFLOW",18f,white,true).apply{letterSpacing=0.08f})
        appTitle.addView(text("VIDEO DOWNLOAD MANAGER",10f,grey,true).apply{letterSpacing=0.10f})
        top.addView(appTitle,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(top)
        viewport=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false}
        content=col().apply{pad(this,18,18)}
        viewport.addView(content)
        root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        navigation=row().apply{setBackgroundColor(surface);pad(this,6,9)}
        root.addView(navigation)
        val shared=if(intent?.action==Intent.ACTION_SEND)
            intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if(!shared.isNullOrBlank())inputUrl=shared.trim()
        navigate("home")
    }
    private fun navigate(to:String){
        page=to
        viewport.scrollTo(0,0)
        draw()
    }
    private fun draw(){
        content.removeAllViews()
        when(page){
            "home"->home()
            "downloads"->downloads()
            "sources"->sources()
            else->settings()
        }
        navigation.removeAllViews()
        listOf(Triple("home","⌂","Home"),Triple("downloads","↓","Downloads"),
            Triple("sources","◎","Sources"),Triple("settings","⚙","Settings")).forEach{
            (key,icon,label)->
            val active=page==key
            val tab=col().apply{gravity=Gravity.CENTER;setOnClickListener{navigate(key)}}
            tab.addView(text(icon,23f,if(active)primary else grey,true).apply{gravity=Gravity.CENTER})
            tab.addView(gap(3))
            tab.addView(text(label,11f,if(active)white else grey,active).apply{gravity=Gravity.CENTER})
            navigation.addView(tab,LinearLayout.LayoutParams(0,dp(54),1f))
        }
    }
    private fun tile(number:String,label:String,color:Int):View{
        val item=col().apply{background=rounded(surface,16,true);pad(this,11,14)}
        item.addView(text(number,23f,color,true))
        item.addView(gap(3));item.addView(text(label,11f,grey))
        return item
    }
    private fun home(){
        val hero=col().apply{
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(46,43,93),Color.rgb(23,51,72))).apply{
                cornerRadius=dp(24).toFloat()
            };pad(this,21,24)
        }
        hero.addView(text("DIRECT VIDEO DOWNLOADS",11f,primary,true).apply{letterSpacing=0.08f})
        hero.addView(gap(9))
        hero.addView(text("Paste a video link.\nSave it locally.",26f,white,true))
        hero.addView(gap(8))
        hero.addView(text("No keyword search, no Tor. Paste the exact video page or media URL.",13f,grey))
        add(content,hero)
        val box=card()
        box.addView(text("VIDEO URL",12f,primary,true))
        val input=edit("https://website.com/video",inputUrl)
        add(box,input,10)
        val actions=row()
        actions.addView(button("ADD DOWNLOAD",true){
            inputUrl=input.text.toString().trim()
            queueUrl(inputUrl)
        },LinearLayout.LayoutParams(0,-2,2f))
        actions.addView(button("PASTE",false){
            val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val pasted=clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
            if(pasted.isNotEmpty()){input.setText(pasted);inputUrl=pasted}
            else toast("Clipboard is empty")
        },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(9)})
        add(box,actions,11)
        add(box,text("Supported sites vary. Some require valid cookies or reject automated extraction.",12f,grey),11)
        add(content,box,14)
        val status=row()
        listOf(tile(statsValue("done"),"SAVED",mint),
            tile((db.count("queued")+db.count("running")).toString(),"PENDING",primary),
            tile(statsValue("error"),"ERRORS",bad)).forEach{
            status.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(6)})
        }
        add(content,status,16)
        add(content,text("QUICK ACTIONS",11f,grey,true),26)
        add(content,button("VIEW DOWNLOADS  →",false){navigate("downloads")},11)
        add(content,button("MANAGE MONITORED PAGES  →",false){navigate("sources")},9)
        add(content,text("RECENT TRANSFERS",12f,grey,true),25)
        val items=db.listVideos(5)
        if(items.isEmpty())add(content,empty("No videos yet","Paste a URL above to queue your first download."),10)
        else items.take(3).forEach{add(content,videoCard(it,true),9)}
        add(content,gap(22))
    }
    private fun queueUrl(value:String){
        if(!WebDiscovery.isUrl(value)) {
            toast("Enter a valid public HTTP or HTTPS video link");return
        }
        if(db.addVideo(value,value)) {
            requestTransfer(false)
            inputUrl=""
            navigate("downloads")
        } else toast("This URL is already in your history")
    }
    private fun requestTransfer(scan:Boolean){
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),107)
        }
        if(!VideoEngine.launch(this,scan))
            toast("Added to the queue, but Android blocked immediate background start")
    }
    private fun empty(heading:String,description:String)=card().apply{
        addView(text("◇",25f,primary,true).apply{gravity=Gravity.CENTER})
        addView(gap(7))
        addView(text(heading,16f,white,true).apply{gravity=Gravity.CENTER})
        addView(gap(7))
        addView(text(description,12f,grey).apply{gravity=Gravity.CENTER})
    }
    private fun downloads(){
        add(content,title("Downloads","Active transfers, saved videos and failures"))
        val tabs=row()
        listOf("All","Active","Saved","Failed").forEach{label->
            tabs.addView(pill(label,label==filter){filter=label;draw()},
                LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(5)})
        }
        add(content,tabs,17)
        val list=db.listVideos(300).filter {
            when(filter){
                "Active"->it.status in setOf("queued","running")
                "Saved"->it.status=="done"
                "Failed"->it.status=="error"
                else->true
            }
        }
        val controls=row()
        controls.addView(text(list.size.toString()+" ITEMS",12f,grey,true),
            LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("START QUEUE",false){requestTransfer(false)})
        add(content,controls,16)
        if(list.isEmpty())add(content,empty("Nothing here","Downloads will appear as you add URLs."),15)
        list.forEach{add(content,videoCard(it,false),9)}
        add(content,gap(24))
    }
    private fun videoCard(v:Video,compact:Boolean):View{
        val box=card()
        val label=when(v.status){"done"->"SAVED";"error"->"FAILED";"running"->"DOWNLOADING";else->"QUEUED"}
        val color=when(v.status){"done"->mint;"error"->bad;"running"->primary;else->amber}
        box.addView(text(label,11f,color,true))
        add(box,text(v.title,14f,white,true),7)
        add(box,text(v.url,11f,grey),6)
        if(v.status=="running"){
            val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=100;this.progress=v.progress
                progressTintList=android.content.res.ColorStateList.valueOf(primary)
            }
            add(box,progress,11)
            add(box,text(v.progress.toString()+"% downloaded",11f,grey),5)
        }
        if(v.status=="error"){
            add(box,text(SourceDiagnostics.summary(v.error),12f,bad),9)
            add(box,text(SourceDiagnostics.retryAdvice(v.error),11f,grey),5)
        }
        if(!compact){
            val actions=row()
            val labelAction=when(v.status){"done"->"OPEN";"error"->"RETRY";else->"START"}
            actions.addView(button(labelAction,false){
                when(v.status){
                    "done"->openSaved(v)
                    "error"->{db.retry(v.id);requestTransfer(false);draw()}
                    else->requestTransfer(false)
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button("REMOVE",false){
                AlertDialog.Builder(this).setTitle("Remove from history?")
                    .setMessage("The history entry will be removed. Previously saved files remain on your device.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("REMOVE"){_,_->db.delete(v.id);draw()}
                    .show()
            },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(8)})
            add(box,actions,13)
            if(v.status=="error")add(box,button("COPY TECHNICAL ERROR",false){
                val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Download error",v.error.orEmpty()))
                toast("Error copied")
            },8)
        }
        return box
    }
    private fun openSaved(video:Video){
        val address=video.savedUri?:return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(Uri.parse(address),"video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure{toast("No installed app can open this video")}
    }
    private fun sources(){
        add(content,title("Watched pages","Monitor exact channel, profile or video-list pages"))
        val intro=card()
        intro.addView(text("HOW MONITORING WORKS",12f,primary,true))
        add(intro,text("Add the exact page URL. VideoFlow checks that page for new video links and queues them. It does not search websites by keywords or performer names.",13f,grey),9)
        add(intro,button("+  ADD PAGE URL"){addSourceDialog()},16)
        add(content,intro,16)
        val options=card()
        val checked=Switch(this).apply{
            text="Periodic source checking";textSize=14f;setTextColor(white)
            isChecked=getSharedPreferences("video_options",MODE_PRIVATE).getBoolean("auto",true)
            setOnCheckedChangeListener{_,enabled->Scheduling.configure(this@MainActivity,enabled)}
        }
        options.addView(checked)
        add(options,text("Android schedules checks approximately every 15 minutes or later. Disabled sources are skipped.",12f,grey),8)
        add(options,button("SCAN ENABLED PAGES",false){requestTransfer(true);toast("Page scan requested")},14)
        add(content,options,12)
        add(content,text("SAVED PAGES",12f,grey,true),24)
        val watches=db.listWatches().filter{WebDiscovery.isUrl(it.address)}
        if(watches.isEmpty())add(content,empty("No monitored pages","You can use direct URL downloads without adding any sources."),10)
        watches.forEach{w->
            val c=card()
            val top=row()
            top.addView(text(w.label,15f,white,true),LinearLayout.LayoutParams(0,-2,1f))
            top.addView(pill(if(w.enabled)"ON" else "OFF",w.enabled){
                db.enableWatch(w.id,!w.enabled);draw()
            })
            c.addView(top)
            add(c,text(w.address,12f,grey),8)
            if(w.lastCheck>0)add(c,text("Last scan: "+
                DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(w.lastCheck)),
                11f,grey),7)
            if(w.error!=null)add(c,text("Issue: "+w.error,12f,amber),7)
            add(c,button("REMOVE PAGE",false){
                AlertDialog.Builder(this).setTitle("Remove page?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("REMOVE"){_,_->db.removeWatch(w.id);draw()}
                    .show()
            },11)
            add(content,c,9)
        }
        add(content,gap(20))
    }
    private fun addSourceDialog(){
        val box=col().apply{pad(this,17,13)}
        box.addView(text("Enter a specific channel, profile or listing URL. This is not a keyword search.",13f,grey))
        val address=edit("https://website.com/videos/")
        add(box,address,13)
        AlertDialog.Builder(this).setTitle("Monitor a page")
            .setView(box).setNegativeButton("Cancel",null)
            .setPositiveButton("ADD PAGE"){_,_->
                val link=address.text.toString().trim()
                if(!WebDiscovery.isUrl(link))toast("Enter a valid public HTTP(S) URL")
                else if(db.addWatch(link,Uri.parse(link).host?:link)){toast("Source added");draw()}
                else toast("This page is already saved")
            }.show()
    }
    private fun settings(){
        add(content,title("Settings","Downloads, cookies and optional cloud backup"))
        val prefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val quality=card()
        quality.addView(text("DOWNLOAD PREFERENCES",12f,primary,true))
        add(quality,text("Maximum new videos per source scan",13f,white),13)
        val maximum=edit("3",prefs.getInt("max_new",3).toString()).apply{
            inputType=InputType.TYPE_CLASS_NUMBER
        }
        add(quality,maximum,8)
        add(quality,text("Preferred video format",13f,white),14)
        val choices=listOf("Compatible MP4 preferred","Best single-file available")
        val spinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,choices)
            setSelection(if(prefs.getString("quality","")=="best")1 else 0)
            background=rounded(elevated,12)
        }
        add(quality,spinner,8)
        add(quality,button("SAVE PREFERENCES"){
            prefs.edit().putInt("max_new",maximum.text.toString().toIntOrNull()?.coerceIn(1,20)?:3)
                .putString("quality",if(spinner.selectedItemPosition==1)"best" else "best[ext=mp4]/best").apply()
            toast("Preferences saved")
        },14)
        add(content,quality,17)
        val auth=card()
        auth.addView(text("AUTHENTICATED DOWNLOADS",12f,primary,true))
        add(auth,text("Optional: import a Netscape-format cookies.txt file for websites you are authorized to access.",12f,grey),9)
        add(auth,button("IMPORT COOKIE FILE",false){
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
            },551)
        },12)
        add(content,auth,12)
        val cloud=card()
        cloud.addView(text("ROUND SYNC / TERABOX",12f,primary,true))
        add(cloud,text("Configure a COPY task in Round Sync with local source Downloads/VideoFlow. The upload runs separately.",12f,grey),9)
        val cloudSwitch=Switch(this).apply{
            text="Request cloud backup after downloads";textSize=13f;setTextColor(white)
            isChecked=prefs.getBoolean("cloud_after",false)
        }
        add(cloud,cloudSwitch,8)
        val taskId=edit("Round Sync task ID",prefs.getInt("cloud_task",-1)
            .takeIf{it>=0}?.toString().orEmpty()).apply{
            inputType=InputType.TYPE_CLASS_NUMBER
        }
        add(cloud,taskId,9)
        add(cloud,button("SAVE CLOUD SETTINGS",false){
            prefs.edit().putBoolean("cloud_after",cloudSwitch.isChecked)
                .putInt("cloud_task",taskId.text.toString().toIntOrNull()?:-1).apply()
            toast("Cloud settings saved")
        },12)
        add(content,cloud,12)
        add(content,text("Saved videos: Downloads/VideoFlow. VideoFlow does not bypass DRM or website authentication.",12f,grey),17)
        add(content,gap(24))
    }
    @Deprecated("Legacy Android picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==551&&resultCode==RESULT_OK){
            val fileUri=data?.data?:return
            runCatching{
                val target=File(filesDir,"cookies.txt")
                contentResolver.openInputStream(fileUri)!!.use{source->
                    target.outputStream().use{source.copyTo(it)}
                }
                require(target.length()>0){"Cookie file empty"}
            }.onSuccess{toast("Cookies imported")}.onFailure{toast("Cookie import failed")}
        }
    }
    @Deprecated("Android back navigation")
    override fun onBackPressed(){
        if(page!="home")navigate("home")
        else super.onBackPressed()
    }
    private val pulse=object:Runnable{
        override fun run(){
            if(live){
                if(page=="downloads"){
                    val old=viewport.scrollY
                    draw()
                    viewport.post{viewport.scrollTo(0,old)}
                }
                handler.postDelayed(this,5500)
            }
        }
    }
    override fun onResume(){
        super.onResume();live=true
        handler.removeCallbacks(pulse);handler.postDelayed(pulse,5500)
    }
    override fun onPause(){
        live=false;handler.removeCallbacks(pulse);super.onPause()
    }
}
