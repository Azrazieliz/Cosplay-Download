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
import android.text.TextUtils
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
    private lateinit var statusBadge: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusPanel: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var sourceList: LinearLayout
    private lateinit var recoveryCard: LinearLayout
    private lateinit var recoveryText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pollTicks = 0

    private val poll = object : Runnable {
        override fun run() {
            val snapshot = SyncControl.snapshot()
            val badgeText = when {
                snapshot.running && snapshot.paused -> "Paused"
                snapshot.running -> "Running"
                else -> "Ready"
            }
            val badgeColor = when {
                snapshot.running && snapshot.paused -> WARNING
                snapshot.running -> GOLD
                else -> SUCCESS
            }

            statusBadge.text = badgeText
            statusBadge.setTextColor(badgeColor)
            statusBadge.background = pill(badgeColor, 28)

            statusDetail.text = when {
                snapshot.running && snapshot.paused -> "Archive operation paused"
                snapshot.running -> snapshot.message
                else -> "Archive engine ready"
            }
            statusPanel.background = rounded(
                when {
                    snapshot.running && snapshot.paused -> SURFACE_2
                    snapshot.running -> ACTIVE_SURFACE
                    else -> SURFACE_2
                },
                14f,
                when {
                    snapshot.running && snapshot.paused -> Color.argb(100, Color.red(WARNING), Color.green(WARNING), Color.blue(WARNING))
                    snapshot.running -> Color.argb(120, Color.red(GOLD), Color.green(GOLD), Color.blue(GOLD))
                    else -> STROKE
                }
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
            setPadding(dp(18), dp(20), dp(18), dp(42))
        }
        scroll.addView(root)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val markWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(OBSIDIAN, 17f, GOLD_DARK)
        }
        markWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_kyora_mark)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        brandRow.addView(markWrap, LinearLayout.LayoutParams(dp(62), dp(62)))

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, 0, 0)
        }
        brandText.addView(label("KYORA", 28f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.18f
        })
        brandText.addView(label("PRIVATE COSPLAY ARCHIVE", 10f, GOLD_LIGHT).apply {
            letterSpacing = 0.14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        brandRow.addView(label("v" + appVersion(), 10f, MUTED).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = rounded(SURFACE_2, 99f, STROKE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        root.addView(brandRow)

        val hero = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
        }

        val heroHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Archive control", 19f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        summaryText = label("Preparing sources…", 11f, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(summaryText)
        heroHeader.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statusBadge = label("Ready", 11f, SUCCESS).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = pill(SUCCESS, 28)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minWidth = dp(72)
        }
        heroHeader.addView(statusBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        hero.addView(heroHeader)

        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE_2, 14f, STROKE)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        statusPanel.addView(label("NOW", 9f, GOLD).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.14f
        })
        statusDetail = label("Archive engine ready", 12f, TEXT).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = false
        }
        statusPanel.addView(statusDetail)
        hero.addView(statusPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(13) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(13), 0, 0)
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
        ).apply { topMargin = dp(22) })

        val automation = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
        }
        val autoText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoText.addView(label("Background sync", 15f, TEXT).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        autoText.addView(label("Quietly check enabled sources", 11f, MUTED).apply {
            setPadding(0, dp(2), 0, 0)
        })
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

        root.addView(sectionTitle("SOURCES", "Curated direct-first collectors"))
        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourceList)

        recoveryCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        recoveryCard.addView(label("Needs attention", 15f, ERROR).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        recoveryText = label("", 12f, MUTED).apply { setPadding(0, dp(5), 0, dp(10)) }
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
            "DOWNLOADS / COSPLAY / GALLERYFLOW\nExisting archive path retained for continuity.",
            10f,
            MUTED
        ).apply {
            setPadding(dp(2), dp(22), dp(2), 0)
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.06f
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
                AdapterRegistry.adapters.size.toString() + " collectors  •  " +
                    totalComplete + " complete  •  " + totalFiles + " files"
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
            val badge = label(sourceGlyph(adapter.source), 10f, accent).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                background = rounded(SURFACE_2, 11f, Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            header.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))

            val titleBlock = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            titleBlock.addView(label(sourceName(adapter.source), 17f, TEXT).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            titleBlock.addView(label(adapter.statusLabel, 10f, MUTED).apply {
                setPadding(0, dp(2), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(header)

            val statsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(13), 0, dp(10))
            }
            statsRow.addView(statBlock(stats.galleries.toString(), "GALLERIES"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statsRow.addView(statBlock(stats.complete.toString(), "COMPLETE"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statsRow.addView(statBlock(stats.mediaComplete.toString(), "FILES"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statsRow)

            if (stats.partial > 0 || stats.inaccessible > 0) {
                addView(label(
                    (stats.partial + stats.inaccessible).toString() + " item(s) queued for retry",
                    10f,
                    WARNING
                ).apply {
                    background = pill(WARNING, 18)
                    setPadding(dp(9), dp(6), dp(9), dp(6))
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

                row.addView(label("Live", 10f, MUTED))
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
                    addView(label(entity.lastError, 10f, ERROR).apply {
                        background = pill(ERROR, 17)
                        setPadding(dp(9), dp(7), dp(9), dp(7))
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(5) })
                }
            }
        }
    }

    private fun statBlock(value: String, caption: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(value, 17f, TEXT).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(label(caption, 8f, MUTED).apply {
                letterSpacing = 0.12f
                setPadding(0, dp(2), 0, 0)
            })
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
            recoveryText.text = sourceName(pending.source) + " has an item that could not be resolved automatically."
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
        SourceId.BUONDUA -> GOLD
        SourceId.KIUTAKU -> GOLD_LIGHT
        SourceId.FOUR_K_GIRL -> ROSE_GOLD
        SourceId.EVERIA -> GOLD
        SourceId.FOUR_K_HD -> PLATINUM
        SourceId.COSPLAYTELE -> COPPER
    }

    private fun scopeName(entity: EntityRecord, count: Int): String {
        if (count == 1) return "Archive source"
        return entity.displayName.substringAfter("—", entity.displayName).trim()
            .ifBlank { entity.entityId }
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(23), 0, dp(10))
            addView(label(title, 11f, GOLD_LIGHT).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.16f
            })
            addView(label(subtitle, 10f, MUTED).apply {
                setPadding(0, dp(3), 0, 0)
            })
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
                primary -> OBSIDIAN
                danger -> ERROR
                else -> TEXT
            }
        )
        background = when {
            primary -> gradientCard(intArrayOf(GOLD_LIGHT, GOLD), 14f)
            danger -> rounded(SURFACE_2, 14f, Color.argb(105, Color.red(ERROR), Color.green(ERROR), Color.blue(ERROR)))
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

    private fun switchThumb(accent: Int = GOLD): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(accent, Color.rgb(106, 108, 116))
    )

    private fun switchTrack(accent: Int = GOLD): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(
            Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.rgb(49, 51, 59)
        )
    )

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.6"
    }.getOrDefault("0.6")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(9, 10, 13)
        private val OBSIDIAN = Color.rgb(12, 13, 16)
        private val SURFACE = Color.rgb(17, 18, 22)
        private val SURFACE_2 = Color.rgb(23, 25, 31)
        private val ACTIVE_SURFACE = Color.rgb(28, 27, 24)
        private val STROKE = Color.rgb(43, 45, 53)

        private val TEXT = Color.rgb(245, 242, 235)
        private val MUTED = Color.rgb(148, 151, 162)

        private val GOLD = Color.rgb(215, 181, 109)
        private val GOLD_LIGHT = Color.rgb(242, 217, 160)
        private val GOLD_DARK = Color.rgb(128, 103, 58)
        private val ROSE_GOLD = Color.rgb(209, 153, 139)
        private val PLATINUM = Color.rgb(194, 199, 208)
        private val COPPER = Color.rgb(204, 143, 95)

        private val SUCCESS = Color.rgb(114, 207, 157)
        private val WARNING = Color.rgb(229, 178, 90)
        private val ERROR = Color.rgb(240, 111, 123)
    }
}
