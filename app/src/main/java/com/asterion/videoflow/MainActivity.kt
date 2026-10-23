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
import android.text.TextWatcher
import android.text.Editable
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Phone-first, navigation-based UI. Download and database services remain unchanged. */
class MainActivity : Activity() {
    private val backdrop=Color.rgb(10,14,24)
    private val surface=Color.rgb(20,26,41)
    private val elevated=Color.rgb(29,37,55)
    private val stroke=Color.rgb(47,57,78)
    private val primary=Color.rgb(158,143,255)
    private val secondary=Color.rgb(128,206,242)
    private val white=Color.rgb(247,249,255)
    private val muted=Color.rgb(157,170,192)
    private val green=Color.rgb(106,217,179)
    private val amber=Color.rgb(248,196,113)
    private val red=Color.rgb(250,132,152)
    private lateinit var queueDb:VideoDb
    private lateinit var performerDb:PerformerDb
    private lateinit var layout:LinearLayout
    private lateinit var page:LinearLayout
    private lateinit var scroller:ScrollView
    private lateinit var tabs:LinearLayout
    private var screen="home"
    private var downloadFilter="All"
    private val expandedErrors=mutableSetOf<Long>()
    private var siteExpanded=false
    private var lastSearchQuery=""
    private var searchResults:List<DiscoveryMatch> = emptyList()
    private var searching=false
    private var searchGeneration=0
    private var searchProgress="Enter specific keywords to discover matching adult videos."
    private var torSearchNote:String?=null
    private var webSearchNote:String?=null
    private var progressLabel:TextView?=null
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var foreground=false

    private fun d(n:Int)=(n*resources.displayMetrics.density+0.5f).toInt()
    private fun drawable(fill:Int, radius:Int=20, outline:Int?=null):GradientDrawable =
        GradientDrawable().apply {
            setColor(fill);cornerRadius=d(radius).toFloat()
            if(outline!=null)setStroke(d(1),outline)
        }
    private fun grad(a:Int,b:Int,radius:Int=24)=GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply {
        cornerRadius=d(radius).toFloat()
    }
    private fun text(value:String, size:Float=14f, color:Int=white,
                     bold:Boolean=false):TextView=TextView(this).apply {
        this.text=value;setTextColor(color);textSize=size
        includeFontPadding=true
        if(bold)setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL))
    }
    private fun vbox():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun hbox():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun space(px:Int)=Space(this).apply{layoutParams=LinearLayout.LayoutParams(1,d(px))}
    private fun pad(view:View,x:Int=16,y:Int=14){view.setPadding(d(x),d(y),d(x),d(y))}
    private fun panel():LinearLayout=vbox().apply{background=drawable(surface,22,stroke);pad(this,17,17)}
    private fun bar(textLabel:String):TextView=text(textLabel,11f,muted,true).apply {
        letterSpacing=0.14f
    }
    private fun heading(a:String,b:String?=null):LinearLayout=vbox().apply{
        addView(text(a,24f,white,true))
        if(b!=null){addView(space(5));addView(text(b,13f,muted))}
    }
    private fun pill(label:String,selected:Boolean=false,click:(()->Unit)?=null):TextView=
        text(label,12f,if(selected)backdrop else muted,true).apply {
            gravity=Gravity.CENTER;pad(this,13,9)
            background=drawable(if(selected)primary else elevated,30,if(selected)primary else stroke)
            if(click!=null)setOnClickListener{click()}
        }
    private fun button(label:String,solid:Boolean=true,click:()->Unit):TextView =
        text(label,13f,if(solid)backdrop else white,true).apply{
            gravity=Gravity.CENTER;minHeight=d(48);pad(this,13,13)
            background=drawable(if(solid)primary else elevated,15,if(solid)null else stroke)
            setOnClickListener{click()}
        }
    private fun input(hint:String,initial:String=""):EditText=EditText(this).apply {
        setSingleLine(true);setTextColor(white);setHintTextColor(muted)
        textSize=15f;setText(initial);this.hint=hint
        background=drawable(elevated,14,stroke);pad(this,14,13)
        setSelectAllOnFocus(true)
    }
    private fun lineRow(left:View,right:View):LinearLayout=hbox().apply{
        addView(left,LinearLayout.LayoutParams(0,-2,1f))
        addView(right)
    }
    private fun inside(parent:LinearLayout,child:View,top:Int=0){
        parent.addView(child,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(top)})
    }
    private fun clickableCard(title:String,subtitle:String,glyph:String,color:Int,click:()->Unit):View{
        val box=panel().apply{setOnClickListener{click()}}
        val row=hbox()
        val tile=text(glyph,22f,color,true).apply{
            gravity=Gravity.CENTER;background=drawable(elevated,15)
        }
        row.addView(tile,LinearLayout.LayoutParams(d(51),d(51)))
        val labels=vbox()
        labels.addView(text(title,15f,white,true))
        labels.addView(space(5))
        labels.addView(text(subtitle,12f,muted))
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(13)})
        row.addView(text("›",26f,muted))
        box.addView(row);return box
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun ensureNotificationAccess(){
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),105)
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=backdrop;window.navigationBarColor=backdrop
        window.decorView.systemUiVisibility=0
        queueDb=VideoDb(this);queueDb.seed();performerDb=PerformerDb(this)
        Scheduling.ensure(this)
        layout=vbox().apply{setBackgroundColor(backdrop)}
        setContentView(layout)
        makeShell()
        val shared=if(intent?.action==Intent.ACTION_SEND)intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if(!shared.isNullOrBlank())main.post{addUrl(shared)}
    }
    private fun makeShell(){
        layout.removeAllViews()
        val header=hbox().apply{pad(this,20,13)}
        val mark=text("▶",19f,backdrop,true).apply{
            gravity=Gravity.CENTER;background=grad(primary,secondary,13)
        }
        header.addView(mark,LinearLayout.LayoutParams(d(42),d(42)))
        val heading=vbox().apply{
            addView(text("VIDEOFLOW",17f,white,true).apply{letterSpacing=0.10f})
            addView(text("PERSONAL VIDEO LIBRARY",10f,muted,true).apply{letterSpacing=0.10f})
        }
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(12)})
        header.addView(pill("SETTINGS",false){settings()})
        layout.addView(header)
        scroller=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false}
        page=vbox().apply{pad(this,18,18)}
        scroller.addView(page)
        layout.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        tabs=hbox().apply{
            setPadding(d(5),d(9),d(5),d(11));background=drawable(surface,0)
        }
        layout.addView(tabs)
        navigate("home")
    }
    private fun navigate(destination:String){
        screen=destination;scroller.scrollTo(0,0)
        renderCurrent();renderNav()
    }
    private fun renderNav(){
        tabs.removeAllViews()
        listOf(Triple("home","⌂","Home"),Triple("search","⌕","Discover"),
            Triple("models","◈","Models"),Triple("downloads","↓","Downloads"),
            Triple("sources","◎","Sites")).forEach { (key,glyph,title)->
            val selected=screen==key
            val tab=vbox().apply{gravity=Gravity.CENTER;setOnClickListener{navigate(key)}}
            tab.addView(text(glyph,23f,if(selected)primary else muted,true).apply{gravity=Gravity.CENTER})
            tab.addView(space(3))
            tab.addView(text(title,11f,if(selected)white else muted,selected).apply{gravity=Gravity.CENTER})
            tabs.addView(tab,LinearLayout.LayoutParams(0,d(53),1f))
        }
    }
    private fun renderCurrent(){
        if(!::page.isInitialized)return
        val position=scroller.scrollY
        page.removeAllViews()
        when(screen){
            "home"->homePage()
            "models"->modelPage()
            "downloads"->downloadPage()
            "sources"->sourcePage()
            "search"->searchPage()
        }
        if(position>0)scroller.post{scroller.scrollTo(0,if(screen=="downloads")position else 0)}
    }
    private fun metric(label:String,value:String,color:Int):View{
        val box=vbox().apply{background=drawable(surface,17,stroke);pad(this,12,15)}
        box.addView(text(value,24f,color,true))
        box.addView(space(5));box.addView(text(label,11f,muted))
        return box
    }
    private fun homePage(){
        val hero=vbox().apply{
            background=grad(Color.rgb(45,40,93),Color.rgb(18,57,75),27)
            pad(this,22,23)
        }
        hero.addView(text("PERSONAL VIDEO ARCHIVE",11f,secondary,true).apply{letterSpacing=0.12f})
        hero.addView(space(11))
        hero.addView(text("Your videos, organized.",25f,white,true))
        hero.addView(space(8))
        hero.addView(text("Find matching videos, follow performers, and manage downloads in one place.",13f,Color.rgb(208,217,241)))
        inside(hero,button("+  FOLLOW A PERFORMER"){performerDialog()},17)
        inside(page,hero)
        val metrics=hbox()
        listOf(
            metric("SAVED",queueDb.count("done").toString(),green),
            metric("IN QUEUE",(queueDb.count("queued")+queueDb.count("running")).toString(),primary),
            metric("MODELS",performerDb.list().count{it.enabled}.toString(),secondary)
        ).forEach { metrics.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(7)}) }
        inside(page,metrics,14)
        inside(page,bar("QUICK ACTIONS"),26)
        inside(page,clickableCard("PASTE VIDEO URL","Directly download one video from its exact link","↘",primary){addUrl()},12)
        inside(page,clickableCard("DISCOVER VIDEOS","Adult videos matching your keywords only","⌕",secondary){navigate("search")},10)
        inside(page,bar("AUTOMATION"),25)
        val box=panel()
        val prefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val toggle=Switch(this).apply{
            text="Automatic checking";setTextColor(white);textSize=15f
            isChecked=prefs.getBoolean("auto",true)
            setOnCheckedChangeListener{_,value->Scheduling.configure(this@MainActivity,value)}
        }
        box.addView(toggle)
        box.addView(text("Android checks monitored models and pages periodically. Exact timing depends on battery and system scheduling.",12f,muted))
        inside(box,button("CHECK FOR NEW VIDEOS",false){
            ensureNotificationAccess()
            val started=VideoEngine.launch(this,true)
            toast(if(started)"Scan requested" else "Background start restricted; retry from foreground")
        },15)
        inside(page,box,10)
        val models=performerDb.list().filter{it.enabled}.take(3)
        inside(page,lineRow(bar("FOLLOWED PERFORMERS"),pill("SEE ALL",false){navigate("models")}),25)
        if(models.isEmpty()){
            val empty=panel()
            empty.addView(text("No performers followed yet",17f,white,true))
            empty.addView(space(6))
            empty.addView(text("Add a model once, then VideoFlow searches supported sites for full videos.",13f,muted))
            inside(empty,button("ADD FIRST PERFORMER",false){performerDialog()},15)
            inside(page,empty,12)
        }else{
            models.forEach{m->inside(page,modelCard(m),9)}
        }
        inside(page,bar("RECENT DOWNLOADS"),24)
        val items=queueDb.listVideos(5)
        if(items.isEmpty())inside(page,text("Downloads will appear here after you add a link or model.",13f,muted),12)
        else items.take(3).forEach{inside(page,videoCard(it,true),8)}
        page.addView(space(24))
    }
    private fun modelPage(){
        val list=performerDb.list()
        inside(page,heading("Your performers","Follow a model to discover full videos across your chosen sites."))
        inside(page,button("+  FOLLOW PERFORMER"){performerDialog()},18)
        inside(page,text(list.size.toString()+" performer watches",12f,muted),19)
        if(list.isEmpty())inside(page,emptyState("Nothing followed yet",
            "Tap Follow Performer to choose who to monitor."),14)
        list.forEach{inside(page,modelCard(it),10)}
        page.addView(space(25))
    }
    private fun modelCard(w:PerformerWatch):View{
        val card=panel()
        val first=w.name.trim().take(1).uppercase()
        val row=hbox()
        row.addView(text(first,21f,primary,true).apply{
            gravity=Gravity.CENTER;background=drawable(elevated,18)
        },LinearLayout.LayoutParams(d(52),d(52)))
        val label=vbox().apply{
            addView(text(w.name,16f,white,true))
            addView(space(5))
            addView(text((if(w.sites=="*")"All "+AdultSiteCatalog.sites.size+" sites"
                else w.domains().size.toString()+" selected sites")+" · "+(w.minSeconds/60)+" min+",12f,muted))
        }
        row.addView(label,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(11)})
        row.addView(pill(if(w.enabled)"ACTIVE" else "PAUSED",w.enabled){
            performerDb.setEnabled(w.id,!w.enabled);renderCurrent()
        })
        card.addView(row)
        inside(card,text(if(w.checked==0L)"No scan yet"
            else "Last check: "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(w.checked)),
            12f,muted),11)
        if(w.status!=null)inside(card,text(w.status,12f,muted),7)
        val buttons=hbox()
        val edit=button("EDIT",false){performerDialog(w)}
        val remove=button("REMOVE",false){
            AlertDialog.Builder(this).setTitle("Remove performer?")
                .setMessage("Stop monitoring "+w.name+"? Saved videos will stay in your library.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Remove"){_,_->performerDb.remove(w.id);renderCurrent()}
                .show()
        }
        buttons.addView(edit,LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(remove,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(9)})
        inside(card,buttons,13)
        return card
    }
    private fun downloadPage(){
        inside(page,heading("Downloads","Manage transfers, saved videos and failed downloads."))
        val filters=hbox()
        listOf("All","Active","Saved","Issues").forEach { key ->
            filters.addView(pill(key,downloadFilter==key){downloadFilter=key;scroller.scrollTo(0,0);renderCurrent()},
                LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(5)})
        }
        inside(page,filters,17)
        val all=queueDb.listVideos(300)
        val shown=all.filter{when(downloadFilter){
            "Active"->it.status=="queued"||it.status=="running"
            "Saved"->it.status=="done"
            "Issues"->it.status=="error"
            else->true
        }}
        inside(page,lineRow(text(shown.size.toString()+" items",13f,muted),
            pill("START QUEUE",false){if(VideoEngine.launch(this))toast("Download queue started")}),16)
        if(shown.isEmpty())inside(page,emptyState("Nothing here yet",
            when(downloadFilter){"Saved"->"Completed downloads appear here."
                "Issues"->"No failed transfers in this view."
                "Active"->"Queue a video link to get started."
                else->"Add a video link or follow a performer."}),14)
        shown.forEach{inside(page,videoCard(it,false),9)}
        page.addView(space(30))
    }
    private fun videoCard(v:Video,compact:Boolean):View {
        val card=panel()
        val statusColor=when(v.status){
            "done"->green;"error"->red;"running"->primary;else->amber
        }
        val status=when(v.status){
            "done"->"SAVED";"error"->"NEEDS ATTENTION";"running"->"DOWNLOADING";else->"WAITING"
        }
        card.addView(lineRow(text(v.title,if(compact)13f else 15f,white,true),
            text(status,10f,statusColor,true)))
        inside(card,text(runCatching{Uri.parse(v.url).host ?: ""}.getOrDefault(""),12f,muted),7)
        if(v.status=="running"){
            val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=100;progress=v.progress
                progressTintList=android.content.res.ColorStateList.valueOf(primary)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(elevated)
            }
            inside(card,bar,12)
            inside(card,text(v.progress.toString()+"% downloaded",11f,muted),4)
        }
        if(v.status=="error") {
            inside(card,text(SourceDiagnostics.summary(v.error),12f,red),10)
            inside(card,text(SourceDiagnostics.retryAdvice(v.error),11f,muted),7)
            val isExpanded=expandedErrors.contains(v.id)
            inside(card,button(if(isExpanded)"HIDE TECHNICAL DETAILS" else "VIEW TECHNICAL DETAILS",false) {
                if(isExpanded)expandedErrors.remove(v.id) else expandedErrors.add(v.id)
                renderCurrent()
            },9)
            if(isExpanded)inside(card,text(v.error.orEmpty().take(700),11f,muted),8)
        }
        if(!compact){
            val actions=hbox()
            val action=when(v.status){
                "done"->"OPEN VIDEO";"error"->"TRY AGAIN";"running"->"IN PROGRESS";else->"START QUEUE"
            }
            actions.addView(button(action,false){
                when(v.status){
                    "done"->openVideo(v)
                    "error"->{queueDb.retry(v.id);VideoEngine.launch(this);renderCurrent()}
                    else->{VideoEngine.launch(this);toast("Queue requested")}
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button("REMOVE",false){
                AlertDialog.Builder(this).setTitle("Remove from history?")
                    .setMessage("Remove this item from VideoFlow? A previously saved file is not deleted.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove"){_,_->queueDb.delete(v.id);renderCurrent()}.show()
            },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(9)})
            inside(card,actions,13)
        }
        return card
    }
    private fun sourcePage(){
        inside(page,heading("Sources","Manage regular video sites and your Tor onion sources."))
        inside(page,button("CONFIGURE TOR / ORBOT",false){torSettings()},12)
        val info=panel()
        info.addView(text("SITE DIRECTORY",12f,secondary,true).apply{letterSpacing=0.08f})
        info.addView(space(6))
        info.addView(text(AdultSiteCatalog.sites.size.toString()+" discovery sites",25f,white,true))
        info.addView(space(7))
        info.addView(text("Listed sites are candidates, not guaranteed downloads. Individual videos depend on extractor and site access.",12f,muted))
        inside(page,info,17)
        inside(page,bar("FIND A SITE"),20)
        val search=input("Search websites")
        inside(page,search,9)
        val holder=vbox()
        val toggle=button("SHOW ALL "+AdultSiteCatalog.sites.size+" SITES",false){}
        inside(page,toggle,11)
        inside(page,holder,12)
        fun fill(q:String){
            holder.removeAllViews()
            val matches=AdultSiteCatalog.sites.filter{
                it.label.contains(q,true)||it.host.contains(q,true)
            }.let { all-> if(q.isBlank()&&!siteExpanded)all.take(6) else all }
            matches.forEach {site->
                val status=when(site.extractor){
                    "dedicated"->"Extractor listed"
                    "generic-tested"->"Metadata tested"
                    else->"Unverified"
                }
                val row=panel()
                row.addView(lineRow(text(site.label,14f,white,true),
                    text(status,10f,if(site.extractor=="compatibility-unknown"||
                        site.extractor=="best-effort")amber else green,true)))
                row.addView(text(site.host,12f,muted))
                holder.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(7)})
            }
            if(matches.isEmpty())holder.addView(emptyState("No site found","You can still add a custom page URL below."))
        }
        toggle.setOnClickListener {
            siteExpanded=!siteExpanded
            toggle.text=if(siteExpanded)"SHOW FEWER SITES" else "SHOW ALL "+AdultSiteCatalog.sites.size+" SITES"
            fill(search.text.toString())
        }
        fill("")
        search.addTextChangedListener(object:TextWatcher{
            override fun afterTextChanged(s:Editable?){fill(s?.toString().orEmpty())}
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
        })
        inside(page,bar("SPECIFIC PAGES & FEEDS"),27)
        inside(page,button("+  ADD PAGE OR PROFILE",false){addSource()},10)
        val sources=queueDb.listWatches()
        sources.forEach{watch->
            val card=panel()
            card.addView(lineRow(text(watch.label,14f,white,true),
                pill(if(watch.enabled)"ON" else "OFF",watch.enabled){
                    queueDb.enableWatch(watch.id,!watch.enabled);renderCurrent()
                }))
            inside(card,text(watch.address,12f,muted),6)
            if(watch.error!=null)inside(card,text(watch.error,11f,amber),7)
            inside(card,button("REMOVE SOURCE",false){
                AlertDialog.Builder(this).setTitle("Remove this page?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove"){_,_->queueDb.removeWatch(watch.id);renderCurrent()}.show()
            },10)
            inside(page,card,9)
        }
        page.addView(space(20))
    }
    private fun emptyState(title:String,description:String):View=panel().apply{
        addView(text("◇",29f,primary,true).apply{gravity=Gravity.CENTER})
        inside(this,text(title,16f,white,true).apply{gravity=Gravity.CENTER},7)
        inside(this,text(description,13f,muted).apply{gravity=Gravity.CENTER},6)
    }
    private fun searchPage(){
        inside(page,heading("Video discovery","Adult videos only. Every specific keyword must match."))
        val field=input("Enter keywords or a performer's name",lastSearchQuery)
        inside(page,field,16)
        inside(page,button(if(searching)"SEARCHING…" else "FIND MATCHING VIDEOS",!searching){
            val query=field.text.toString().trim()
            if(query.isBlank()) {
                toast("Enter keywords to search");return@button
            }
            if(WebDiscovery.isUrl(query)) {
                toast("Use Paste URL for links; Discover is keyword search")
                return@button
            }
            if(SearchRelevance.requestedTerms(query).isEmpty()) {
                toast("Enter more specific keywords than 'porn video'");return@button
            }
            startKeywordSearch(query)
        },10)
        val torPrefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val torBox=panel()
        val torToggle=Switch(this).apply {
            text="Include dark-web (.onion) sources"
            setTextColor(white);textSize=14f
            isChecked=torPrefs.getBoolean("tor_enabled",false)
            setOnCheckedChangeListener{_,enabled->
                torPrefs.edit().putBoolean("tor_enabled",enabled).apply()
                if(enabled)toast("Start Orbot and configure its SOCKS port in Tor settings")
            }
        }
        torBox.addView(torToggle)
        inside(torBox,text("Requires Orbot connected in Power User Mode. Tor is separate from regular web search.",12f,muted),7)
        inside(torBox,text("Ahmia filters adult searches. To find onion videos, add a known onion source under Sites.",12f,amber),7)
        inside(torBox,button("TOR CONNECTION SETTINGS",false){torSettings()},11)
        inside(torBox,button("ADD A KNOWN .ONION SOURCE",false){
            navigate("sources")
            addSource()
        },8)
        inside(page,torBox,13)
        inside(page,text("Sources: adult video sites and broader web indexes. Mainstream sites appear only when the video itself has adult-content evidence.",12f,muted),12)
        inside(page,button("DOWNLOAD FROM A URL INSTEAD",false){addUrl()},15)
        inside(page,bar("SEARCH RESULTS"),24)
        val notice=panel()
        if(searching) {
            val line=hbox()
            line.addView(ProgressBar(this),LinearLayout.LayoutParams(d(32),d(32)))
            val label=text(searchProgress,13f,white)
            progressLabel=label
            line.addView(label,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(12)})
            notice.addView(line)
            inside(notice,text("Only matching video links will be shown.",12f,muted),9)
        } else {
            progressLabel=null
            notice.addView(text(searchProgress,13f,if(searchResults.isEmpty())muted else green))
            if(lastSearchQuery.isNotBlank())
                inside(notice,text("Search: "+lastSearchQuery,12f,muted),7)
        }
        inside(page,notice,10)
        webSearchNote?.let { note ->
            inside(page,text(note,12f,muted),9)
        }
        torSearchNote?.let {note->
            inside(page,text(note,12f,amber),9)
        }
        if(searchResults.isNotEmpty()) {
            inside(page,text(searchResults.size.toString()+" relevant adult video matches",12f,secondary,true),16)
            for(hit in searchResults) {
                val result=panel()
                result.addView(text(hit.title,15f,white,true))
                inside(result,text(hit.origin+"   •   "+hit.type,11f,secondary,true),7)
                inside(result,text(hit.url,11f,muted),7)
                inside(result,text(hit.evidence+". Download compatibility is not yet verified.",11f,muted),10)
                val actions=hbox()
                actions.addView(button("DOWNLOAD",true){enqueue(hit.url,hit.title)},
                    LinearLayout.LayoutParams(0,-2,1f))
                actions.addView(button(if(TorAccess.isOnion(hit.url))"COPY ONION LINK" else "VIEW SOURCE",false){
                    if(TorAccess.isOnion(hit.url)) {
                        val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tor video URL",hit.url))
                        toast("Onion URL copied. Open it with Tor Browser.")
                    } else runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(hit.url)))
                    }.onFailure{toast("Cannot open this video page")}
                },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(8)})
                inside(result,actions,13)
                inside(page,result,10)
            }
        } else if(!searching&&lastSearchQuery.isNotBlank()) {
            inside(page,emptyState("No matching adult videos",
                "No indexed video page passed every keyword and adult-content check. Search sites may also block automated discovery."),12)
        } else if(!searching) {
            inside(page,emptyState("Search by video content",
                "Enter a specific performer, title or set of keywords. Irrelevant webpages are excluded."),12)
        }
        page.addView(space(24))
    }

    private fun startKeywordSearch(query:String){
        val generation=++searchGeneration
        lastSearchQuery=query
        searchResults=emptyList()
        searching=true
        searchProgress="Searching adult video indexes…"
        torSearchNote=null
        webSearchNote=null
        renderCurrent()
        val tor=TorAccess.settings(this)
        val onionSources=queueDb.listWatches().map{it.address}
            .filter{TorAccess.isV3Onion(it)}
        io.execute {
            val result=runCatching {
                val progress:(String)->Unit={ message->
                    runOnUiThread {
                        if(searchGeneration==generation) {
                            searchProgress=message
                            progressLabel?.text=message
                        }
                    }
                }
                var sourceReport:String?=null
                val publicResults=VideoSearch.search(query,30,progress){report->sourceReport=report}
                var torMessage:String?=null
                val onionResults=if(tor.enabled) {
                    try {
                        val matches=TorDiscovery.search(query,tor,onionSources,progress)
                        if(matches.isEmpty())torMessage=
                            "No verified Tor video. Ahmia filters sexual searches; add a known onion site under Sites. Saved onion sources: "+onionSources.size
                        matches
                    } catch(t:Exception) {
                        torMessage="Tor unavailable: "+(t.message?: "Proxy connection failed")
                        emptyList()
                    }
                } else emptyList()
                (publicResults+onionResults).distinctBy{it.url}
                    .sortedByDescending{it.score}.take(35) to Pair(torMessage,sourceReport)
            }
            runOnUiThread {
                if(generation!=searchGeneration)return@runOnUiThread
                searching=false
                searchResults=result.getOrNull()?.first ?: emptyList()
                torSearchNote=result.getOrNull()?.second?.first
                webSearchNote=result.getOrNull()?.second?.second
                searchProgress=if(result.isFailure)
                    "Search failed: "+(result.exceptionOrNull()?.message?.take(130)?:"network unavailable")
                    else if(searchResults.isEmpty())
                    "No relevant adult video results found. Unmatched pages were excluded."
                    else "Only adult video pages matching all keywords are shown."
                if(screen=="search")renderCurrent()
            }
        }
    }

    private fun enqueue(url:String,title:String){
        if(queueDb.addVideo(url,title)){
            ensureNotificationAccess()
            val started=VideoEngine.launch(this)
            toast(if(started)"Added to downloads" else "Queued; launch when Android allows background work")
        }else toast("Already in your library or invalid link")
        navigate("downloads")
    }
    private fun addUrl(prefill:String=""){
        val edit=input("https://example.com/video",prefill)
        val box=vbox().apply{pad(this,17,12)}
        box.addView(text("Paste a video page or direct file link.",13f,muted))
        inside(box,edit,13)
        AlertDialog.Builder(this).setTitle("Direct URL download").setView(box)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("ADD TO DOWNLOADS"){_,_->
                val url=edit.text.toString().trim()
                if(!WebDiscovery.isUrl(url))toast("Enter a valid HTTP(S) video URL")
                else enqueue(url,url)
            }.show()
    }
    private fun addSource(){
        val field=input("https://website.com/model/profile")
        val box=vbox().apply{pad(this,17,12)}
        box.addView(text("Monitor an exact profile, category or feed. Avoid entire homepages if you only want one performer.",13f,muted))
        inside(box,field,10)
        AlertDialog.Builder(this).setTitle("Add a monitored page").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("ADD"){_,_->
                val address=field.text.toString().trim()
                if(!WebDiscovery.isUrl(address))toast("Enter a valid page URL")
                else if(TorAccess.isOnion(address)&&!TorAccess.isV3Onion(address))
                    toast("Enter a valid 56-character Tor v3 onion URL")
                else if(queueDb.addWatch(address,Uri.parse(address).host ?: address)){
                    toast("Page added");renderCurrent()
                }else toast("This page is already monitored")
            }.show()
    }
    private fun performerDialog(existing:PerformerWatch?=null){
        val all=AdultSiteCatalog.sites
        val chosen=existing?.sites?.takeUnless{it=="*"}?.split(',')?.toMutableSet()
            ?: mutableSetOf<String>()
        var customAll=existing==null||existing.sites=="*"
        var minimum=existing?.minSeconds?.div(60)?:10
        val box=vbox().apply{pad(this,17,10)}
        inside(box,text("1  PERFORMER NAME",11f,secondary,true))
        val name=input("Enter performer name",existing?.name ?: "")
        inside(box,name,8)
        inside(box,text("2  MINIMUM VIDEO LENGTH",11f,secondary,true),20)
        val lengths=hbox()
        val lengthOptions=listOf(5,10,20,30)
        fun drawLengths(){
            lengths.removeAllViews()
            lengthOptions.forEach{option->
                lengths.addView(pill(option.toString()+"m",option==minimum){
                    minimum=option;drawLengths()
                },LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(5)})
            }
        }
        drawLengths();inside(box,lengths,10)
        inside(box,text("3  WHERE TO SEARCH",11f,secondary,true),20)
        val selector=button("ALL "+all.size+" SITES   ·   CHANGE",false){}
        fun refreshSelector(){
            selector.text=if(customAll)"ALL "+all.size+" SITES   ·   CHANGE"
                else chosen.size.toString()+" SITES SELECTED   ·   CHANGE"
        }
        selector.setOnClickListener{
            val names=all.map{it.label+"  ·  "+it.host}.toTypedArray()
            val checks=BooleanArray(all.size){customAll||chosen.contains(all[it].host)}
            AlertDialog.Builder(this).setTitle("Choose sites")
                .setMultiChoiceItems(names,checks){_,i,yes->checks[i]=yes}
                .setNeutralButton("ALL SITES"){_,_->customAll=true;chosen.clear();refreshSelector()}
                .setNegativeButton("Cancel",null)
                .setPositiveButton("USE SELECTION"){_,_->
                    val selected=all.indices.filter{checks[it]}.map{all[it].host}
                    customAll=selected.size==all.size
                    chosen.clear();chosen.addAll(selected);refreshSelector()
                }.show()
        }
        refreshSelector();inside(box,selector,9)
        inside(box,text("Only videos with verified duration and matching performer metadata are downloaded automatically.",12f,muted),15)
        val outer=ScrollView(this).apply{addView(box)}
        val dlg=AlertDialog.Builder(this).setTitle(if(existing==null)"Follow a performer" else "Edit performer")
            .setView(outer)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("SAVE",null).create()
        dlg.setOnShowListener{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val model=name.text.toString().trim()
                if(model.length<2){toast("Enter the performer name");return@setOnClickListener}
                if(!customAll && chosen.isEmpty()){toast("Choose at least one site");return@setOnClickListener}
                val sites=if(customAll)"*" else chosen.joinToString(",")
                val saved=if(existing==null) performerDb.add(model,sites,minimum)
                    else performerDb.edit(existing.id,model,sites,minimum)
                if(saved){
                    dlg.dismiss();ensureNotificationAccess();navigate("models")
                }else toast("This performer already exists, or settings are invalid")
            }
        }
        dlg.show()
    }
    private fun torSettings() {
        val prefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val box=vbox().apply{pad(this,18,10)}
        box.addView(text("CONNECT TO TOR",12f,secondary,true))
        inside(box,text("1. Open Orbot and enable Power User Mode in Settings.\n2. Tap Connect in Orbot and confirm it says Connected.\n3. Return here and select TEST TOR CONNECTION.",13f,white),11)
        inside(box,text("Orbot normally uses SOCKS 9050. Some configurations use another port. This test checks a real Tor circuit—not just an open port.",12f,muted),10)
        val field=input("Orbot SOCKS port (default 9050)",
            prefs.getInt("tor_socks_port",9050).toString()).apply{
            inputType=InputType.TYPE_CLASS_NUMBER
        }
        inside(box,field,14)
        val status=text("Not tested. Tor searches require a connected Orbot proxy.",13f,muted)
        inside(box,status,14)
        inside(box,button("TEST TOR CONNECTION",true){
            val port=field.text.toString().toIntOrNull()
            if(port==null||port !in 1..65535) {
                status.text="Enter a valid port (1–65535)."
            } else {
                status.text="Checking SOCKS ports and Tor circuit…"
                io.execute {
                    val report=TorAccess.diagnose(port)
                    runOnUiThread {
                        status.text=report.message
                        status.setTextColor(if(report.verified)green else amber)
                        if(report.verified && report.detectedPort!=null){
                            field.setText(report.detectedPort.toString())
                            prefs.edit().putInt("tor_socks_port",report.detectedPort)
                                .putBoolean("tor_enabled",true).apply()
                        }
                    }
                }
            }
        },12)
        inside(box,button("OPEN ORBOT",false){
            val launch=packageManager.getLaunchIntentForPackage("org.torproject.android")
            if(launch!=null)startActivity(launch)
            else runCatching{
                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://orbot.app/")))
            }.onFailure{toast("Install Orbot to use Tor sources")}
        },9)
        inside(box,text("If the test fails: check Orbot's Power User Mode, SOCKS setting and connected state. Another Android VPN or background restrictions can prevent Tor from starting. The app will never send .onion requests directly.",12f,muted),13)
        inside(box,text("Adult-related search terms are filtered by Ahmia. Even with Tor connected, broad adult onion discovery will not be complete. Add known onion video pages in Sites.",12f,amber),12)
        AlertDialog.Builder(this).setTitle("Tor connection setup")
            .setView(ScrollView(this).apply{addView(box)})
            .setNegativeButton("CLOSE",null)
            .setPositiveButton("SAVE PORT"){_,_->
                val port=field.text.toString().toIntOrNull()
                if(port==null||port !in 1..65535)toast("Invalid SOCKS port")
                else {
                    prefs.edit().putInt("tor_socks_port",port).apply()
                    toast("SOCKS port saved. Test Tor connection before searching.")
                }
            }.show()
    }
    private fun openVideo(v:Video){
        val saved=v.savedUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(Uri.parse(saved),"video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure{toast("No application could open this video")}
    }
    private fun settings(){
        val options=getSharedPreferences("video_options",MODE_PRIVATE)
        val box=vbox().apply{pad(this,18,10)}
        val entry=panel()
        entry.addView(text("DOWNLOAD SETTINGS",12f,secondary,true))
        inside(entry,text("Max new videos per page check",13f,white),11)
        val max=input("3",options.getInt("max_new",3).toString()).apply{
            inputType=InputType.TYPE_CLASS_NUMBER
        }
        inside(entry,max,6)
        inside(entry,text("Preferred format",13f,white),14)
        val formats=listOf("Compatible MP4 first","Best single-file available")
        val spinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,formats)
            setSelection(if(options.getString("quality","")=="best")1 else 0)
            background=drawable(elevated,12,stroke)
        }
        inside(entry,spinner,6)
        box.addView(entry)
        val automate=panel()
        val aut=Switch(this).apply{
            text="Periodic checks";setTextColor(white)
            isChecked=options.getBoolean("auto",true)
            setOnCheckedChangeListener{_,yes->Scheduling.configure(this@MainActivity,yes)}
        }
        automate.addView(aut)
        automate.addView(text("Android controls the schedule. A 15-minute interval is a minimum, not a guaranteed exact time.",12f,muted))
        inside(box,automate,12)
        inside(box,button("IMPORT COOKIE FILE",false){selectCookies()},12)
        inside(box,button("ROUND SYNC / TERABOX",false){cloudSettings()},9)
        inside(box,text("Saved files: Downloads/VideoFlow. Protected media is not decrypted. Cookie files remain in private app storage.",12f,muted),16)
        val dialog=AlertDialog.Builder(this).setTitle("Preferences").setView(ScrollView(this).apply{addView(box)})
            .setNegativeButton("Close",null)
            .setPositiveButton("SAVE SETTINGS"){_,_->
                options.edit().putInt("max_new",max.text.toString().toIntOrNull()?.coerceIn(1,20)?:3)
                    .putString("quality",if(spinner.selectedItemPosition==1)"best" else "best[ext=mp4]/best").apply()
                toast("Settings saved")
            }.create()
        dialog.show()
    }
    private fun selectCookies(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
        },551)
    }
    @Deprecated("Legacy file picker callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==551 && resultCode==RESULT_OK){
            val uri=data?.data ?: return
            runCatching{
                val target=File(filesDir,"cookies.txt")
                contentResolver.openInputStream(uri)!!.use{input->
                    target.outputStream().use{input.copyTo(it)}
                }
                require(target.length()>0){"The cookie file is empty"}
            }.onSuccess{toast("Cookies imported")}.onFailure{toast("Import failed: "+it.message)}
        }
    }
    private fun cloudSettings(){
        val p=getSharedPreferences("video_options",MODE_PRIVATE)
        val box=vbox().apply{pad(this,18,9)}
        box.addView(text("Use a Round Sync COPY task from Downloads/VideoFlow to your cloud destination. The task starts after queue processing; cloud completion must be verified in Round Sync.",13f,muted))
        val enabled=Switch(this).apply{
            text="Request cloud backup";setTextColor(white)
            isChecked=p.getBoolean("cloud_after",false)
        }
        inside(box,enabled,14)
        val task=input("Round Sync task ID",
            p.getInt("cloud_task",-1).takeIf{it>=0}?.toString() ?: "").apply{
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        inside(box,task,10)
        AlertDialog.Builder(this).setTitle("Cloud backup").setView(box)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("SAVE"){_,_->
                p.edit().putBoolean("cloud_after",enabled.isChecked)
                    .putInt("cloud_task",task.text.toString().toIntOrNull()?:-1).apply()
                toast("Cloud settings saved")
            }.show()
    }
    @Deprecated("Back navigation compatibility")
    override fun onBackPressed() {
        if(screen!="home") navigate("home") else super.onBackPressed()
    }
    override fun onResume(){
        super.onResume();foreground=true
        main.removeCallbacks(pulse)
        main.postDelayed(pulse,5000)
    }
    override fun onPause(){
        foreground=false
        main.removeCallbacks(pulse)
        super.onPause()
    }
    private val pulse=object:Runnable{
        override fun run(){
            if(foreground){
                if(screen=="downloads"){
                    val pos=scroller.scrollY
                    renderCurrent()
                    scroller.post{scroller.scrollTo(0,pos)}
                }
                main.postDelayed(this,5000)
            }
        }
    }
}