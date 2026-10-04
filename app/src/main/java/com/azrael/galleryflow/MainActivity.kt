package com.azrael.galleryflow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusPill: TextView
    private lateinit var summaryText: TextView
    private lateinit var sourceList: LinearLayout
    private lateinit var recoveryCard: LinearLayout
    private lateinit var recoveryText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pollTicks = 0

    private val poll = object : Runnable {
        override fun run() {
            val snapshot = SyncControl.snapshot()
            statusPill.text = when {
                snapshot.running && snapshot.paused -> "Paused"
                snapshot.running -> snapshot.message
                else -> "Ready"
            }
            statusPill.background = pill(
                when {
                    snapshot.running && snapshot.paused -> WARNING
                    snapshot.running -> ACCENT
                    else -> SUCCESS
                },
                alpha = 32
            )

            updateRecoveryState()
            pollTicks++
            if (pollTicks % 5 == 0) renderSources()
            handler.postDelayed(this, 900L)
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
        updateRecoveryState()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        scroll.addView(root)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val markWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = gradientCard(intArrayOf(ACCENT, ACCENT_2), 18f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        markWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_kyora_mark)
            setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        brandRow.addView(markWrap, LinearLayout.LayoutParams(dp(58), dp(58)))

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        brandText.addView(label("KYORA", 29f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        brandText.addView(label("COSPLAY ARCHIVE", 11f, MUTED).apply {
            letterSpacing = 0.16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        brandRow.addView(label("v" + appVersion(), 11f, MUTED).apply {
            background = pill(SURFACE_2, 255)
            setPadding(dp(9), dp(5), dp(9), dp(5))
        })
        root.addView(brandRow)

        val hero = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heroTitles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitles.addView(label("Archive control", 18f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        summaryText = label("Preparing sources…", 12f, MUTED)
        heroTitles.addView(summaryText)
        heroTop.addView(heroTitles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = label("Ready", 12f, SUCCESS).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = pill(SUCCESS, 32)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        heroTop.addView(statusPill)
        hero.addView(heroTop)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        actions.addView(actionButton("Archive all", primary = true) {
            startSync(SyncMode.BACKFILL)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(actionButton("Sync new", primary = false) {
            startSync(SyncMode.LIVE)
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(9) })
        hero.addView(actions)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        transport.addView(actionButton("Pause / resume", compact = true) {
            val action = if (SyncControl.snapshot().paused) {
                SyncForegroundService.ACTION_RESUME
            } else {
                SyncForegroundService.ACTION_PAUSE
            }
            startService(Intent(this@MainActivity, SyncForegroundService::class.java).setAction(action))
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        transport.addView(actionButton("Stop", compact = true, danger = true) {
            startService(
                Intent(this@MainActivity, SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_STOP)
            )
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(9) })
        hero.addView(transport)

        root.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        val automation = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(12), dp(13))
        }
        val autoText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoText.addView(label("Background sync", 15f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        autoText.addView(label("Check enabled sources automatically", 11f, MUTED))
        automation.addView(autoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        automation.addView(Switch(this).apply {
            isChecked = Prefs.autoSync(this@MainActivity)
            thumbTintList = switchThumb()
            trackTintList = switchTrack()
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoSync(this@MainActivity, checked)
                if (checked) Scheduler.ensure(this@MainActivity) else Scheduler.cancel(this@MainActivity)
            }
        })
        root.addView(automation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(sectionTitle("SOURCES", "Direct-first • physical files are authoritative"))
        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourceList)

        recoveryCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        recoveryCard.addView(label("Needs attention", 16f, ERROR).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        recoveryText = label("", 12f, MUTED).apply { setPadding(0, dp(4), 0, dp(10)) }
        recoveryCard.addView(recoveryText)
        val recoveryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recoveryActions.addView(actionButton("Retry", compact = true) {
            GalleryDb(this@MainActivity).use { it.retryProviderFailures() }
            startSync(SyncMode.BACKFILL)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        recoveryActions.addView(actionButton("Open source", compact = true) {
            val pending = GalleryDb(this@MainActivity).use { it.firstProviderBlockedMedia() }
            if (pending == null) {
                Toast.makeText(this@MainActivity, "Nothing is blocked", Toast.LENGTH_SHORT).show()
            } else {
                openProvider(pending.url)
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(9) })
        recoveryCard.addView(recoveryActions)
        root.addView(recoveryCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(label(
            "Storage  •  Downloads/Cosplay/GalleryFlow\nKyora keeps the existing storage path so your current archive stays intact.",
            11f,
            MUTED
        ).apply {
            setPadding(dp(2), dp(20), dp(2), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        return scroll
    }

    private fun renderSources() {
        if (!::sourceList.isInitialized) return
        GalleryDb(this).use { db ->
            db.ensureEntities(AdapterRegistry.defaultEntities())
            val entities = db.listEntities()
            sourceList.removeAllViews()

            var totalFiles = 0
            var totalComplete = 0
            for (adapter in AdapterRegistry.adapters) {
                val stats = db.sourceStats(adapter.source)
                totalFiles += stats.mediaComplete
                totalComplete += stats.complete
                val sourceEntities = entities.filter { it.source == adapter.source }
                sourceList.addView(
                    sourceCard(adapter, sourceEntities, stats),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                )
            }
            summaryText.text =
                AdapterRegistry.adapters.size.toString() + " sources  •  " +
                    totalComplete + " galleries complete  •  " + totalFiles + " files"
        }
    }

    private fun sourceCard(
        adapter: SourceAdapter,
        entities: List<EntityRecord>,
        stats: GalleryDb.SourceStats
    ): LinearLayout {
        val accent = sourceAccent(adapter.source)
        return card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(13))

            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = label(sourceGlyph(adapter.source), 12f, accent).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = pill(accent, 30)
            }
            header.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))

            val titleBlock = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            titleBlock.addView(label(sourceName(adapter.source), 18f, TEXT).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            titleBlock.addView(label(adapter.statusLabel, 11f, accent))
            header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(header)

            addView(label(
                stats.galleries.toString() + " galleries   •   " +
                    stats.complete + " complete   •   " +
                    stats.mediaComplete + " files",
                12f,
                MUTED
            ).apply { setPadding(0, dp(10), 0, dp(8)) })

            if (stats.partial > 0 || stats.inaccessible > 0) {
                addView(label(
                    (stats.partial + stats.inaccessible).toString() + " item(s) need retry",
                    11f,
                    WARNING
                ).apply {
                    background = pill(WARNING, 22)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                })
            }

            entities.forEachIndexed { index, entity ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    if (index > 0) setPadding(0, dp(5), 0, 0)
                }
                val check = CheckBox(this@MainActivity).apply {
                    text = scopeName(entity, entities.size)
                    textSize = 13f
                    setTextColor(TEXT)
                    isChecked = entity.selected
                    buttonTintList = checkTint(accent)
                    setOnCheckedChangeListener { _, checked ->
                        GalleryDb(this@MainActivity).use {
                            it.setSelected(entity.source, entity.entityId, checked)
                        }
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.addView(label("Live", 11f, MUTED))
                row.addView(Switch(this@MainActivity).apply {
                    isChecked = entity.liveEnabled
                    thumbTintList = switchThumb(accent)
                    trackTintList = switchTrack(accent)
                    setOnCheckedChangeListener { _, checked ->
                        GalleryDb(this@MainActivity).use {
                            it.setLiveEnabled(entity.source, entity.entityId, checked)
                        }
                    }
                })
                addView(row)

                if (!entity.lastError.isNullOrBlank()) {
                    addView(label(entity.lastError, 11f, ERROR).apply {
                        background = pill(ERROR, 20)
                        setPadding(dp(9), dp(7), dp(9), dp(7))
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(5) })
                }
            }
        }
    }

    private fun updateRecoveryState() {
        if (!::recoveryCard.isInitialized) return
        val pending = runCatching {
            GalleryDb(this).use { it.firstProviderBlockedMedia() }
        }.getOrNull()
        if (pending == null) {
            recoveryCard.visibility = View.GONE
        } else {
            recoveryCard.visibility = View.VISIBLE
            recoveryText.text = sourceName(pending.source) + " has a provider item that could not be resolved automatically."
        }
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

    private fun sourceName(source: SourceId): String = when (source) {
        SourceId.BUONDUA -> "BuonDua"
        SourceId.KIUTAKU -> "Kiutaku"
        SourceId.FOUR_K_GIRL -> "4KGirl"
        SourceId.EVERIA -> "Everia"
        SourceId.FOUR_K_HD -> "4KHD"
        SourceId.COSPLAYTELE -> "CosplayTele"
    }

    private fun sourceGlyph(source: SourceId): String = when (source) {
        SourceId.BUONDUA -> "BD"
        SourceId.KIUTAKU -> "KT"
        SourceId.FOUR_K_GIRL -> "4G"
        SourceId.EVERIA -> "EV"
        SourceId.FOUR_K_HD -> "4K"
        SourceId.COSPLAYTELE -> "CT"
    }

    private fun sourceAccent(source: SourceId): Int = when (source) {
        SourceId.BUONDUA -> Color.rgb(91, 219, 180)
        SourceId.KIUTAKU -> Color.rgb(132, 145, 255)
        SourceId.FOUR_K_GIRL -> Color.rgb(245, 120, 188)
        SourceId.EVERIA -> Color.rgb(239, 187, 88)
        SourceId.FOUR_K_HD -> Color.rgb(99, 180, 255)
        SourceId.COSPLAYTELE -> Color.rgb(255, 144, 104)
    }

    private fun scopeName(entity: EntityRecord, count: Int): String {
        if (count == 1) return "Archive source"
        return entity.displayName.substringAfter("—", entity.displayName).trim()
            .ifBlank { entity.entityId }
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(22), 0, dp(9))
            addView(label(title, 12f, TEXT).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.12f
            })
            addView(label(subtitle, 11f, MUTED))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        background = rounded(SURFACE, 18f, STROKE)
    }

    private fun actionButton(
        textValue: String,
        primary: Boolean = false,
        compact: Boolean = false,
        danger: Boolean = false,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = if (compact) 12f else 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(
            when {
                primary -> BG
                danger -> ERROR
                else -> TEXT
            }
        )
        background = when {
            primary -> gradientCard(intArrayOf(ACCENT, Color.rgb(116, 205, 255)), 14f)
            danger -> rounded(SURFACE_2, 14f, Color.argb(110, 255, 123, 139))
            else -> rounded(SURFACE_2, 14f, STROKE)
        }
        stateListAnimator = null
        minHeight = 0
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun gradientCard(colors: IntArray, radiusDp: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun pill(color: Int, alpha: Int): GradientDrawable =
        rounded(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)), 99f)

    private fun checkTint(accent: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(accent, MUTED)
    )

    private fun switchThumb(accent: Int = ACCENT): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(accent, Color.rgb(111, 119, 136))
    )

    private fun switchTrack(accent: Int = ACCENT): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(
            Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.rgb(55, 61, 73)
        )
    )

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.6"
    }.getOrDefault("0.6")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(11, 13, 18)
        private val SURFACE = Color.rgb(20, 24, 33)
        private val SURFACE_2 = Color.rgb(27, 32, 48)
        private val STROKE = Color.rgb(39, 46, 61)
        private val TEXT = Color.rgb(244, 246, 251)
        private val MUTED = Color.rgb(155, 164, 182)
        private val ACCENT = Color.rgb(115, 230, 213)
        private val ACCENT_2 = Color.rgb(139, 124, 255)
        private val SUCCESS = Color.rgb(109, 224, 168)
        private val WARNING = Color.rgb(245, 191, 87)
        private val ERROR = Color.rgb(255, 123, 139)
    }
}
