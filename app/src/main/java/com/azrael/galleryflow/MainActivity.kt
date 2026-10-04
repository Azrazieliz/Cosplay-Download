package com.azrael.galleryflow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var sourceList: LinearLayout
    private lateinit var providerStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val snapshot = SyncControl.snapshot()
            status.text = if (snapshot.running) snapshot.message else "Idle"
            if (::providerStatus.isInitialized) {
                val pending = runCatching {
                    GalleryDb(this@MainActivity).use { it.firstProviderBlockedMedia() }
                }.getOrNull()
                providerStatus.text = if (pending == null) {
                    "No provider action required"
                } else {
                    "Provider action required for " + sourceName(pending.source)
                }
            }
            handler.postDelayed(this, 800L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        GalleryDb(this).use { it.ensureEntities(AdapterRegistry.defaultEntities()) }
        setContentView(buildUi())
        requestNotificationPermission()
        Scheduler.ensure(this)
    }

    override fun onResume() {
        super.onResume()
        GalleryDb(this).use { it.ensureEntities(AdapterRegistry.defaultEntities()) }
        renderSources()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(40))
        }
        scroll.addView(root)

        root.addView(label("GalleryFlow", 28f, Color.WHITE))
        root.addView(label("Whole-site image, video and archive sync", 13f, MUTED))
        status = label("Idle", 14f, ACCENT).apply { setPadding(0, dp(10), 0, dp(10)) }
        root.addView(status)

        val syncRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        syncRow.addView(Button(this).apply {
            text = "Live Sync"
            setOnClickListener { startSync(SyncMode.LIVE) }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        syncRow.addView(Button(this).apply {
            text = "Archive All"
            setOnClickListener { startSync(SyncMode.BACKFILL) }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        root.addView(syncRow)

        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transport.addView(Button(this).apply {
            text = "Pause / Resume"
            setOnClickListener {
                val action = if (SyncControl.snapshot().paused) {
                    SyncForegroundService.ACTION_RESUME
                } else {
                    SyncForegroundService.ACTION_PAUSE
                }
                startService(Intent(this@MainActivity, SyncForegroundService::class.java).setAction(action))
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        transport.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, SyncForegroundService::class.java)
                        .setAction(SyncForegroundService.ACTION_STOP)
                )
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(
            transport,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoRow.addView(
            label("Automatic Live checks", 14f, Color.WHITE),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        autoRow.addView(Switch(this).apply {
            isChecked = Prefs.autoSync(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoSync(this@MainActivity, checked)
                if (checked) Scheduler.ensure(this@MainActivity) else Scheduler.cancel(this@MainActivity)
            }
        })
        root.addView(
            autoRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )

        root.addView(label("SOURCES", 12f, MUTED).apply { setPadding(0, dp(18), 0, dp(6)) })
        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourceList)

        root.addView(label("ARCHIVE PROVIDERS", 12f, MUTED).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        providerStatus = label("No provider action required", 12f, MUTED)
        root.addView(providerStatus)

        val providerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        providerRow.addView(Button(this).apply {
            text = "Retry Provider"
            setOnClickListener {
                GalleryDb(this@MainActivity).use { it.retryProviderFailures() }
                startSync(SyncMode.BACKFILL)
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        providerRow.addView(Button(this).apply {
            text = "Open Blocked"
            setOnClickListener {
                val pending = GalleryDb(this@MainActivity).use { it.firstProviderBlockedMedia() }
                if (pending == null) {
                    Toast.makeText(this@MainActivity, "No blocked provider item", Toast.LENGTH_SHORT).show()
                } else {
                    openProvider(pending.url)
                }
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(
            providerRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )
        root.addView(
            label(
                "Public TeraBox shares are resolved automatically. Provider interaction is only used when a mirror truly cannot be resolved. Files are stored under Downloads/Cosplay/GalleryFlow.",
                11f,
                MUTED
            ).apply { setPadding(0, dp(4), 0, 0) }
        )

        return scroll
    }

    private fun renderSources() {
        if (!::sourceList.isInitialized) return
        val db = GalleryDb(this)
        try {
            db.ensureEntities(AdapterRegistry.defaultEntities())
            val entities = db.listEntities()
            sourceList.removeAllViews()

            for (adapter in AdapterRegistry.adapters) {
                val sourceEntities = entities.filter { it.source == adapter.source }
                val stats = db.sourceStats(adapter.source)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(SURFACE)
                }
                card.addView(label(sourceName(adapter.source), 17f, Color.WHITE))
                card.addView(label(adapter.statusLabel, 11f, ACCENT))
                card.addView(
                    label(
                        "Galleries " + stats.galleries +
                            " • complete " + stats.complete +
                            " • partial/retry " + stats.partial +
                            " • files " + stats.mediaComplete,
                        11f,
                        MUTED
                    ).apply { setPadding(0, dp(3), 0, dp(5)) }
                )

                sourceEntities.forEach { entity ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(CheckBox(this).apply {
                        text = entity.displayName.removePrefix(sourceName(entity.source) + " — ")
                        setTextColor(Color.WHITE)
                        isChecked = entity.selected
                        setOnCheckedChangeListener { _, checked ->
                            GalleryDb(this@MainActivity).use {
                                it.setSelected(entity.source, entity.entityId, checked)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(Switch(this).apply {
                        text = "Live"
                        setTextColor(Color.WHITE)
                        isChecked = entity.liveEnabled
                        setOnCheckedChangeListener { _, checked ->
                            GalleryDb(this@MainActivity).use {
                                it.setLiveEnabled(entity.source, entity.entityId, checked)
                            }
                        }
                    })
                    card.addView(row)
                    if (!entity.lastError.isNullOrBlank()) {
                        card.addView(label(entity.lastError, 11f, ERROR))
                    }
                }

                sourceList.addView(
                    card,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    }
                )
            }
        } finally {
            db.close()
        }
    }

    private fun sourceName(source: SourceId): String = when (source) {
        SourceId.KIUTAKU -> "Kiutaku"
        SourceId.FOUR_K_HD -> "4KHD"
        SourceId.BUONDUA -> "BuonDua"
        SourceId.COSPLAYTELE -> "CosplayTele"
    }

    private fun startSync(mode: SyncMode) {
        startForegroundService(
            Intent(this, SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_START)
                .putExtra(SyncForegroundService.EXTRA_MODE, mode.name)
        )
    }

    private fun openProvider(url: String) {
        startActivity(
            Intent(this, ProviderWebActivity::class.java)
                .putExtra(ProviderWebActivity.EXTRA_URL, url)
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 30)
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(17, 18, 22)
        private val SURFACE = Color.rgb(31, 33, 40)
        private val MUTED = Color.rgb(166, 171, 184)
        private val ACCENT = Color.rgb(116, 196, 255)
        private val ERROR = Color.rgb(255, 125, 125)
    }
}
