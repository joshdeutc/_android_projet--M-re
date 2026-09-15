package com.jo.selfcontrol.ultimate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.*
import java.util.Calendar
class MainActivity : Activity() {

    companion object {
        private const val TAG = "SelfControl.Main"
        private const val REQ_IMPORT_CSV = 2001
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rootContainer: FrameLayout
    private lateinit var setupScreenManager: SetupScreenManager
    private lateinit var setupView: View
    private var isSetupShowing = false
    private var isManualGuideOpen = false
    private lateinit var dashboardContainer: LinearLayout
    private lateinit var delayContainer: LinearLayout
    private lateinit var nuclearStatusContainer: LinearLayout
    private lateinit var nuclearSetupContainer: LinearLayout
    private lateinit var nuclearCountdownText: TextView
    private lateinit var nuclearBlockedListText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var deviceOwnerStatusText: TextView
    private lateinit var periodBlocksContainer: LinearLayout
    private lateinit var installBlocksContainer: LinearLayout
    private lateinit var partialAccessContainer: LinearLayout

    private lateinit var zoomCanvas: com.jo.selfcontrol.ultimate.ui.ZoomableCanvasView
    private lateinit var securityCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView
    private lateinit var appLimitsCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView
    private lateinit var curfewCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView
    private lateinit var installBlocklistCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView
    private lateinit var nuclearCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView
    private lateinit var partialAccessCircle: com.jo.selfcontrol.ultimate.ui.FeatureCircleView

    private val selectedNuclearApps = mutableSetOf<String>()
    private var selectedDurationMs = 30 * 60 * 1000L

    private var lastRenderedAllowed = emptySet<String>()
    private var lastRenderedPending = emptyList<WhitelistManager.PendingRequest>()
    private var lastRenderedDelayHours = -1
    private var lastRenderedUseGlobal = false
    private var lastRenderedPendingDelayAt = 0L
    private var lastPendingUpdateSecond = 0L

    private val appNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val appIconCache = java.util.concurrent.ConcurrentHashMap<String, Drawable>()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshPermissions()
            refreshDashboard()
            refreshDelayUI()
            refreshNuclearStatus()
            refreshPeriodBlocksUI()
            refreshInstallBlocksUI()
            refreshPartialAccessUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        Log.i(TAG, "MainActivity started")
        LimitService.start(this)
        setContentView(buildUI())
        handler.postDelayed(refreshRunnable, 500)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == "com.jo.selfcontrol.ultimate.ACTION_WHITELIST_PROMPT") {
            val pkg = intent.getStringExtra("extra_package") ?: return
            val label = intent.getStringExtra("extra_label") ?: getAppName(pkg)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(200000 + pkg.hashCode())
            if (::zoomCanvas.isInitialized) {
                zoomCanvas.resetToOverview()
            }
            confirmRequestAddition(pkg, label)
        } else if (action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val pkg = WhitelistManager.sanitizePackageName(text)
            if (pkg.isNotBlank()) {
                if (::zoomCanvas.isInitialized) {
                    zoomCanvas.resetToOverview()
                }
                confirmRequestAddition(pkg, getAppName(pkg))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        if (::zoomCanvas.isInitialized) {
            zoomCanvas.startFloatingAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::zoomCanvas.isInitialized) {
            zoomCanvas.stopFloatingAnimation(animateToZero = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onBackPressed() {
        if (isSetupShowing) {
            if (isManualGuideOpen) {
                dismissSetupScreen()
                return
            }
            super.onBackPressed()
            return
        }
        if (::zoomCanvas.isInitialized && zoomCanvas.zoomedCircle != null) {
            zoomCanvas.zoomOut()
        } else {
            super.onBackPressed()
        }
    }

    // ──────────────────────────────────────
    //  Build UI
    // ──────────────────────────────────────

    private fun buildUI(): View {
        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        zoomCanvas = com.jo.selfcontrol.ultimate.ui.ZoomableCanvasView(this)

        val createContainer = { -> LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }

        // =============================================
        //  CENTRAL HUB: Delay & Limits (the big one)
        // =============================================
        securityCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("Delay & Limits")
            setSummaryText("Protection active")
            onClickListener = { zoomCanvas.zoomInto(this) }
        }
        delayContainer = createContainer()
        securityCircle.addContent(sectionTitleWithHelp("Security & Delay", HELP_DELAY))
        securityCircle.addContent(delayContainer)

        // =============================================
        //  SATELLITE 1: App Limits (top-left)
        // =============================================
        appLimitsCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("App Limits")
            setSummaryText("Manage apps")
            onClickListener = { zoomCanvas.zoomInto(this) }
        }
        val limitsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        limitsHeader.addView(sectionTitleWithHelp("App Limits", HELP_APP_LIMITS))
        limitsHeader.addView(android.widget.Button(this@MainActivity).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK)
            setOnClickListener { showAddAppDialog() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(16) }
        })
        appLimitsCircle.addContent(limitsHeader)
        dashboardContainer = createContainer()
        appLimitsCircle.addContent(dashboardContainer)

        // =============================================
        //  SATELLITE 2: Curfew (top-right)
        // =============================================
        curfewCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("Curfew")
            setSummaryText("Sleep hours")
            onClickListener = { zoomCanvas.zoomInto(this) }
        }
        val curfewHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        curfewHeader.addView(android.widget.Button(this@MainActivity).apply {
            text = "+ Add curfew rule"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK)
            setOnClickListener { showAddCurfewRuleDialog() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        curfewCircle.addContent(sectionTitleWithHelp("Curfew", HELP_CURFEW))
        curfewCircle.addContent(TextView(this).apply {
            text = "Block selected apps during sleeping hours."
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        curfewCircle.addContent(curfewHeader)
        periodBlocksContainer = createContainer()
        curfewCircle.addContent(periodBlocksContainer)

        // =============================================
        //  SATELLITE 3: Whitelist (bottom-left)
        // =============================================
        installBlocklistCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("Whitelist")
            setSummaryText("Zero-Trust apps")
            onClickListener = {
                zoomCanvas.zoomInto(this)
                refreshInstallBlocksUI(force = true)
            }
        }
        val installHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installHeader.addView(android.widget.Button(this@MainActivity).apply {
            text = "+ Demander une app (24h)"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK)
            setOnClickListener { showRequestWhitelistAppDialog() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        installBlocklistCircle.addContent(sectionTitleWithHelp("App Whitelist", HELP_WHITELIST))
        installBlocklistCircle.addContent(installHeader)
        installBlocksContainer = createContainer()
        installBlocklistCircle.addContent(installBlocksContainer)

        // =============================================
        //  SATELLITE 4: Nuclear Mode (bottom-right)
        // =============================================
        nuclearCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("Nuclear")
            setSummaryText("Total block")
            onClickListener = { zoomCanvas.zoomInto(this) }
        }
        nuclearCircle.addContent(sectionTitleWithHelp("Nuclear Mode", HELP_NUCLEAR))
        nuclearStatusContainer = createContainer().apply { visibility = View.GONE }
        nuclearCountdownText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.RED)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        nuclearStatusContainer.addView(nuclearCountdownText)
        nuclearBlockedListText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        nuclearStatusContainer.addView(nuclearBlockedListText)
        nuclearCircle.addContent(nuclearStatusContainer)

        nuclearSetupContainer = createContainer().apply { visibility = View.VISIBLE }
        nuclearSetupContainer.addView(android.widget.Button(this@MainActivity).apply {
            text = "Activate Nuclear Mode"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.RED)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { startNuclearActivationFlow() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        nuclearSetupContainer.addView(android.widget.Button(this@MainActivity).apply {
            text = "Manage Presets"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { showManagePresetsDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        nuclearCircle.addContent(nuclearSetupContainer)

        // =============================================
        //  SATELLITE 5: Partial Access
        // =============================================
        partialAccessCircle = com.jo.selfcontrol.ultimate.ui.FeatureCircleView(this).apply {
            setFeatureTitle("Partial Access")
            setSummaryText("In-app channels")
            onClickListener = { zoomCanvas.zoomInto(this) }
        }
        val partialAccessHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        partialAccessHeader.addView(sectionTitleWithHelp("Partial Access", HELP_PARTIAL_ACCESS))
        partialAccessHeader.addView(android.widget.Button(this@MainActivity).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.BLACK)
            setOnClickListener { startScreenRuleLearning() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(16) }
        })
        partialAccessCircle.addContent(partialAccessHeader)
        partialAccessContainer = createContainer()
        partialAccessCircle.addContent(partialAccessContainer)

        // =============================================
        //  LAYOUT: Central hub + 5 satellites (Pentagon)
        //  Position relative to screen size
        // =============================================
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val baseW = minOf(screenW, screenH)

        val hubSize = (baseW * 0.38f).toInt()   
        val satSize = (baseW * 0.24f).toInt()    

        val hubCenterX = screenW / 2f
        val hubCenterY = screenH / 2f - dp(30)
        
        val hubX = (hubCenterX - hubSize / 2f).toInt()
        val hubY = (hubCenterY - hubSize / 2f).toInt()

        // Distance from hub center to satellite center
        val distance = (hubSize / 2f) + (satSize / 2f) + dp(15)

        // Calculate 5 points of a pentagon. Angles: 270 (top), 342, 54, 126, 198
        // Using standard trig: x = cx + dist * cos(a), y = cy + dist * sin(a)
        fun calcX(angleDeg: Float): Int = (hubCenterX + distance * Math.cos(Math.toRadians(angleDeg.toDouble()))).toInt() - satSize / 2
        fun calcY(angleDeg: Float): Int = (hubCenterY + distance * Math.sin(Math.toRadians(angleDeg.toDouble()))).toInt() - satSize / 2

        val sat1X = calcX(270f)
        val sat1Y = calcY(270f)
        val sat2X = calcX(342f)
        val sat2Y = calcY(342f)
        val sat3X = calcX(54f)
        val sat3Y = calcY(54f)
        val sat4X = calcX(126f)
        val sat4Y = calcY(126f)
        val sat5X = calcX(198f)
        val sat5Y = calcY(198f)

        // Add hub FIRST (it's circles[0] = the connection hub)
        zoomCanvas.addView(securityCircle, FrameLayout.LayoutParams(hubSize, hubSize).apply {
            leftMargin = hubX; topMargin = hubY
        })
        zoomCanvas.addView(appLimitsCircle, FrameLayout.LayoutParams(satSize, satSize).apply {
            leftMargin = sat1X; topMargin = sat1Y
        })
        zoomCanvas.addView(curfewCircle, FrameLayout.LayoutParams(satSize, satSize).apply {
            leftMargin = sat2X; topMargin = sat2Y
        })
        zoomCanvas.addView(installBlocklistCircle, FrameLayout.LayoutParams(satSize, satSize).apply {
            leftMargin = sat3X; topMargin = sat3Y
        })
        zoomCanvas.addView(nuclearCircle, FrameLayout.LayoutParams(satSize, satSize).apply {
            leftMargin = sat4X; topMargin = sat4Y
        })
        zoomCanvas.addView(partialAccessCircle, FrameLayout.LayoutParams(satSize, satSize).apply {
            leftMargin = sat5X; topMargin = sat5Y
        })

        val circles = listOf(securityCircle, appLimitsCircle, curfewCircle, installBlocklistCircle, nuclearCircle, partialAccessCircle)

        // Hidden status texts (still needed by refresh methods)
        serviceStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        deviceOwnerStatusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { showDeviceOwnerInfoDialog() }
        }
        zoomCanvas.addView(serviceStatusText)
        zoomCanvas.addView(deviceOwnerStatusText)

        rootContainer.addView(zoomCanvas)

        setupScreenManager = SetupScreenManager(this) {
            dismissSetupScreen()
        }
        setupView = setupScreenManager.buildView()
        rootContainer.addView(setupView)

        val isMandatoryDone = PermissionHelper.isMandatorySetupComplete(this)
        if (isMandatoryDone) {
            setupView.visibility = View.GONE
            zoomCanvas.visibility = View.VISIBLE
            isSetupShowing = false
            zoomCanvas.post { zoomCanvas.startEntryAnimation() }
        } else {
            setupView.visibility = View.VISIBLE
            zoomCanvas.visibility = View.GONE
            isSetupShowing = true
        }

        return rootContainer
    }

    private fun dismissSetupScreen() {
        if (!isSetupShowing) return
        isSetupShowing = false
        isManualGuideOpen = false
        zoomCanvas.visibility = View.VISIBLE
        zoomCanvas.startEntryAnimation()
        zoomCanvas.startFloatingAnimation()
        setupView.animate()
            .alpha(0f)
            .setDuration(350)
            .withEndAction {
                setupView.visibility = View.GONE
                setupView.alpha = 1f
            }
    }

    // ──────────────────────────────────────
    //  Permissions Refresh
    // ──────────────────────────────────────

    private fun refreshPermissions() {
        if (::setupScreenManager.isInitialized) {
            setupScreenManager.refresh()
        }

        val isMandatoryDone = PermissionHelper.isMandatorySetupComplete(this)
        if (!isMandatoryDone) {
            if (!isSetupShowing && ::setupView.isInitialized) {
                isSetupShowing = true
                isManualGuideOpen = false
                setupView.alpha = 1f
                setupView.visibility = View.VISIBLE
                zoomCanvas.visibility = View.GONE
            }
        } else {
            if (isSetupShowing && !isManualGuideOpen) {
                dismissSetupScreen()
            }
        }
    }

    // ──────────────────────────────────────
    //  Delay Configuration & Unlock Settings
    // ──────────────────────────────────────

    private fun refreshDelayUI() {
        delayContainer.removeAllViews()
        val delayState = DelayManager.loadState(this)
        val effectiveDelay = DelayManager.getCurrentEffectiveDelaySeconds(this)
        val now = System.currentTimeMillis()

        if (delayState.unlockSettingsUnlockTime > 0) {
            if (now >= delayState.unlockSettingsUnlockTime) {
                securityCircle.setSummaryText("🔓 Unlocked", Color.parseColor("#4CAF50"))
            } else {
                val remaining = ((delayState.unlockSettingsUnlockTime - now) / 1000).toInt().coerceAtLeast(0)
                securityCircle.setSummaryText("Unlock: ${formatShortDuration(remaining)}", Color.parseColor("#FF9800"))
            }
        } else if (effectiveDelay > 0) {
            securityCircle.setSummaryText("Delay: ${formatShortDuration(effectiveDelay)}")
        } else {
            securityCircle.setSummaryText("No delay")
        }
        
        // Delay Config
        val delayText = TextView(this).apply {
            text = "Current Delay: ${formatTime(effectiveDelay)} (base ${formatTime(delayState.globalDelaySeconds)})"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        delayContainer.addView(delayText)

        val editDelayBtn = Button(this).apply {
            text = "Change Delay"
            background = roundedBackground(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener { showChangeDelayDialog() }
        }
        delayContainer.addView(editDelayBtn)

        delayContainer.addView(Button(this).apply {
            text = "Add Scheduled Delay Rule"
            background = roundedBackground(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener { showAddDelayScheduleRuleDialog() }
        })

        delayContainer.addView(Button(this).apply {
            text = "📋 Guide Paramètres & Permissions"
            background = roundedBackground(Color.parseColor("#1C202A"))
            setTextColor(Color.parseColor("#8E92A4"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
            layoutParams = lp
            setOnClickListener {
                if (::zoomCanvas.isInitialized) zoomCanvas.zoomOut()
                setupScreenManager.refresh()
                setupView.alpha = 1f
                setupView.visibility = View.VISIBLE
                zoomCanvas.visibility = View.GONE
                isSetupShowing = true
                isManualGuideOpen = true
            }
        })

        if (delayState.delaySchedule.isNotEmpty()) {
            delayContainer.addView(TextView(this).apply {
                text = "Scheduled delay rules"
                setTextColor(Color.parseColor("#FFCA28"))
                textSize = 14f
                setPadding(0, dp(12), 0, dp(8))
            })
            for (rule in delayState.delaySchedule) {
                val start = "%02d:%02d".format(rule.startMinutes / 60, rule.startMinutes % 60)
                val end = "%02d:%02d".format((rule.endMinutes % 1440) / 60, (rule.endMinutes % 1440) % 60)
                val days = formatDays(rule.allowedDays)
                delayContainer.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = roundedBackground(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    addView(TextView(this@MainActivity).apply {
                        text = "$days | $start -> $end | ${formatTime(rule.delaySeconds)}"
                        setTextColor(Color.BLACK)
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Delete delay rule"
                        setTextColor(Color.WHITE)
                        background = roundedBackground(Color.parseColor("#D32F2F"))
                        setOnClickListener { DelayManager.removeScheduleRule(this@MainActivity, rule.id) }
                    })
                })
            }
        }

        // Pending Configs UI
        if (delayState.requestedConfigUpdates.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val pending = delayState.requestedConfigUpdates
                .filter { it.executeAt > now }
                .sortedBy { it.executeAt }
            if (pending.isNotEmpty()) {
                delayContainer.addView(TextView(this).apply {
                    text = "Pending config changes"
                    setTextColor(Color.BLACK)
                    textSize = 14f
                    setPadding(0, dp(16), 0, dp(8))
                })
                for (update in pending) {
                    val remaining = ((update.executeAt - now) / 1000).toInt()
                    delayContainer.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = roundedBackground(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }

                        addView(TextView(this@MainActivity).apply {
                            text = "📝 Pending Change — ${formatTime(remaining)}"
                            setTextColor(Color.BLACK)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                        })

                        // Show detailed diff if possible
                        val pendingConfig = ConfigManager.fromJsonString(update.newLimitsJson)
                        val currentConfig = ConfigManager.loadConfig(this@MainActivity)
                        if (pendingConfig != null) {
                            val diffLines = describeConfigDiff(currentConfig, pendingConfig)
                            if (diffLines.isNotEmpty()) {
                                addView(TextView(this@MainActivity).apply {
                                    text = diffLines.joinToString("\n")
                                    setTextColor(Color.BLACK)
                                    textSize = 13f
                                    setPadding(0, dp(6), 0, dp(6))
                                })
                            } else {
                                addView(TextView(this@MainActivity).apply {
                                    text = update.description
                                    setTextColor(Color.BLACK)
                                    textSize = 13f
                                    setPadding(0, dp(4), 0, dp(4))
                                })
                            }
                        } else {
                            addView(TextView(this@MainActivity).apply {
                                text = update.description
                                setTextColor(Color.BLACK)
                                textSize = 13f
                                setPadding(0, dp(4), 0, dp(4))
                            })
                        }

                        addView(Button(this@MainActivity).apply {
                            text = "Cancel this change"
                            setTextColor(Color.WHITE)
                            background = roundedBackground(Color.parseColor("#D32F2F"))
                            setOnClickListener { DelayManager.cancelConfigUpdate(this@MainActivity, update.id) }
                        })
                    })
                }

                delayContainer.addView(Button(this).apply {
                    text = "Cancel all pending changes"
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#5C1E1E"))
                    setOnClickListener { DelayManager.cancelConfigUpdates(this@MainActivity) }
                })
            }
        }

        if (delayState.pendingDelayExecuteAt > 0L && delayState.pendingDelaySeconds != null) {
            val nowPending = System.currentTimeMillis()
            if (delayState.pendingDelayExecuteAt > nowPending) {
                val remaining = ((delayState.pendingDelayExecuteAt - nowPending) / 1000).toInt()
                delayContainer.addView(TextView(this).apply {
                    text =
                        "Delay change pending... ${formatTime(remaining)} (→ ${formatTime(delayState.pendingDelaySeconds)})"
                    setTextColor(Color.parseColor("#FFCA28"))
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(8))
                })
                delayContainer.addView(Button(this).apply {
                    text = "Cancel delay change"
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#D32F2F"))
                    setOnClickListener { DelayManager.cancelPendingDelayChange(this@MainActivity) }
                })
            }
        }

        // Pending Whitelist quarantine requests
        val whitelistState = WhitelistManager.loadState(this)
        if (whitelistState.pendingRequests.isNotEmpty()) {
            delayContainer.addView(TextView(this).apply {
                text = "⏳ Quarantaine Whitelist (${whitelistState.pendingRequests.size})"
                setTextColor(Color.parseColor("#FF9800"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(16), 0, dp(8))
            })

            for (req in whitelistState.pendingRequests) {
                val remainingSec = ((req.availableAt - now) / 1000).toInt().coerceAtLeast(0)
                val appLabel = getAppName(req.packageName)

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundedBackground(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }

                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val iconView = ImageView(this@MainActivity).apply {
                            setImageDrawable(getAppIcon(req.packageName))
                            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
                        }
                        addView(iconView)

                        val txt = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            addView(TextView(this@MainActivity).apply {
                                text = appLabel
                                textSize = 14f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.BLACK)
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = "Déblocage dans ${formatTime(remainingSec)}"
                                textSize = 12f
                                setTextColor(Color.parseColor("#E65100"))
                            })
                        }
                        addView(txt)
                    }
                    addView(row)

                    val cancelBtn = Button(this@MainActivity).apply {
                        text = "Annuler la demande"
                        setTextColor(Color.WHITE)
                        background = roundedBackground(Color.parseColor("#D32F2F"))
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                        layoutParams = lp
                        setOnClickListener {
                            WhitelistManager.cancelPendingRequest(this@MainActivity, req.packageName)
                            Toast.makeText(this@MainActivity, "Demande annulée", Toast.LENGTH_SHORT).show()
                            refreshDelayUI()
                            refreshInstallBlocksUI(force = true)
                        }
                    }
                    addView(cancelBtn)
                }
                delayContainer.addView(card)
            }
        }

        // Pending Whitelist delay reduction
        if (whitelistState.pendingDelayExecuteAt > 0L && whitelistState.pendingDelayHours != null) {
            val nowPending = System.currentTimeMillis()
            if (whitelistState.pendingDelayExecuteAt > nowPending) {
                val remainingSec = ((whitelistState.pendingDelayExecuteAt - nowPending) / 1000).toInt()
                val targetText = if (whitelistState.pendingDelayUseGlobal) "Aligné sur délai général" else "${whitelistState.pendingDelayHours}h"
                delayContainer.addView(TextView(this).apply {
                    text = "⏳ Modification délai Whitelist en attente : ${formatTime(remainingSec)} (→ $targetText)"
                    setTextColor(Color.parseColor("#FFCA28"))
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(8))
                })
                delayContainer.addView(Button(this).apply {
                    text = "Annuler le changement de délai Whitelist"
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#D32F2F"))
                    setOnClickListener {
                        WhitelistManager.cancelPendingDelayChange(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Modification annulée", Toast.LENGTH_SHORT).show()
                        refreshDelayUI()
                        refreshInstallBlocksUI(force = true)
                    }
                })
            }
        }

        // Settings Unlock UI
        if (delayState.unlockSettingsUnlockTime > 0) {
            if (now >= delayState.unlockSettingsUnlockTime) {
                // Unlocked!
                delayContainer.addView(TextView(this).apply {
                    text = "🔓 Settings & Uninstall are currently UNLOCKED."
                    setTextColor(Color.parseColor("#4CAF50"))
                    setPadding(0, dp(16), 0, dp(8))
                })
                delayContainer.addView(Button(this).apply {
                    text = "Lock Settings"
                    background = roundedBackground(Color.parseColor("#333333"))
                    setTextColor(Color.WHITE)
                    setOnClickListener { DelayManager.cancelSettingsUnlock(this@MainActivity) }
                })
            } else {
                // Pending Unlock
                val remaining = ((delayState.unlockSettingsUnlockTime - now) / 1000).toInt()
                delayContainer.addView(TextView(this).apply {
                    text = "Unlock Pending... ${formatTime(remaining)}"
                    setTextColor(Color.parseColor("#FFCA28"))
                    setPadding(0, dp(16), 0, dp(8))
                })
                delayContainer.addView(Button(this).apply {
                    text = "Cancel Unlock"
                    background = roundedBackground(Color.parseColor("#D32F2F"))
                    setTextColor(Color.WHITE)
                    setOnClickListener { DelayManager.cancelSettingsUnlock(this@MainActivity) }
                })
            }
        } else {
            // Locked
            delayContainer.addView(Button(this).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(16)
                layoutParams = lp
                text = "Request Settings Unlock (to Uninstall)"
                background = roundedBackground(Color.parseColor("#333333"))
                setTextColor(Color.WHITE)
                setOnClickListener { DelayManager.requestSettingsUnlock(this@MainActivity) }
            })
        }
    }

    private fun showChangeDelayDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 1440
            value = DelayManager.loadState(this@MainActivity).globalDelaySeconds / 60
            wrapSelectorWheel = false
        }
        layout.addView(picker)
        layout.addView(TextView(this).apply {
            text = " minutes"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Set Global Protection Delay")
            .setView(layout)
            .setMessage("All configuration changes and uninstalls will require waiting this amount of time.")
            .setPositiveButton("Save") { _, _ ->
                val newSeconds = picker.value * 60
                val currentSeconds = DelayManager.loadState(this).globalDelaySeconds
                when {
                    newSeconds == currentSeconds -> {
                        Toast.makeText(this, "Delay unchanged.", Toast.LENGTH_SHORT).show()
                    }
                    newSeconds > currentSeconds -> {
                        // Increasing delay → immediate (stricter)
                        DelayManager.setGlobalDelay(this, newSeconds)
                        Toast.makeText(this, "Delay increased.", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Decreasing delay → must wait (loosening control)
                        if (currentSeconds == 0 || DelayManager.isSettingsUnlocked(this)) {
                            DelayManager.setGlobalDelay(this, newSeconds)
                            Toast.makeText(this, "Delay updated.", Toast.LENGTH_SHORT).show()
                        } else {
                            DelayManager.requestDelayChange(this, newSeconds)
                            Toast.makeText(
                                this,
                                "Delay decrease will apply after the current waiting period.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showAddDelayScheduleRuleDialog() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        fun addTimeRow(label: String, defaultHour: Int, defaultMinute: Int): Pair<NumberPicker, NumberPicker> {
            wrap.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val h = NumberPicker(this).apply {
                minValue = 0
                maxValue = 23
                value = defaultHour
            }
            val m = NumberPicker(this).apply {
                minValue = 0
                maxValue = 59
                value = defaultMinute
            }
            row.addView(h)
            row.addView(TextView(this).apply {
                text = " : "
                setTextColor(Color.WHITE)
            })
            row.addView(m)
            wrap.addView(row)
            return h to m
        }

        val (startH, startM) = addTimeRow("Apply delay from", 17, 0)
        val (endH, endM) = addTimeRow("Until", 9, 0)

        val delayMinutesPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 720
            value = 300
        }
        wrap.addView(TextView(this).apply {
            text = "Delay minutes during this window"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
        })
        wrap.addView(delayMinutesPicker)

        val selectedDays = mutableSetOf<Int>()
        val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dayChecked = BooleanArray(dayLabels.size) { true }
        selectedDays.addAll(0..6)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Delay schedule days")
            .setMultiChoiceItems(dayLabels, dayChecked) { _, which, isChecked ->
                if (isChecked) selectedDays.add(which) else selectedDays.remove(which)
            }
            .setPositiveButton("Next") { _, _ ->
                if (selectedDays.isEmpty()) {
                    Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val innerDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("Add scheduled delay")
                    .setView(wrap)
                    .setPositiveButton("Save") { _, _ ->
                        val start = startH.value * 60 + startM.value
                        val end = endH.value * 60 + endM.value
                        if (start == end) {
                            Toast.makeText(this, "Start and end must differ.", Toast.LENGTH_SHORT).show()
                        } else {
                            DelayManager.addScheduleRule(
                                this,
                                start,
                                end,
                                delayMinutesPicker.value * 60,
                                selectedDays.sorted()
                            )
                            Toast.makeText(this, "Scheduled delay rule added.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                styleDialogForDarkTheme(innerDialog)
                innerDialog.show()
            }
            .setNegativeButton("Cancel", null)
            .create().also { styleDialogForDarkTheme(it); it.show() }
    }

    // ──────────────────────────────────────
    //  In-App Config Editor 
    // ──────────────────────────────────────

    private fun showAddAppDialog() {
        val apps = getInstalledLaunchableApps()
        var selectedIdx = 0

        val listView = buildAppRadioListView(apps) { idx -> selectedIdx = idx }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Select App to Limit")
            .setView(listView)
            .setPositiveButton("Next") { _, _ ->
                showEditAppDialog(apps[selectedIdx].packageName)
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showEditAppDialog(pkg: String) {
        val config = loadEditableConfig()
        val existingLimit = config.limits.find { it.packageName == pkg }

        val mins = existingLimit?.maxMinutesPerDay ?: 30
        val existingSession = existingLimit?.session
        val initialSessionMin = (existingSession?.sessionDurationSec ?: 300) / 60
        val initialCooldownMin = (existingSession?.cooldownSec ?: 3600) / 60
        val initialMaxSessions = existingSession?.maxSessionsPerDay ?: 5

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        // ── Daily quota ──────────────────────────────
        root.addView(TextView(this).apply {
            text = "Daily quota"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, dp(4))
        })
        val dailyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dailyPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 1440
            value = mins
            wrapSelectorWheel = false
        }
        dailyRow.addView(dailyPicker)
        dailyRow.addView(TextView(this).apply {
            text = " mins/day"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(dailyRow)

        // ── Session limit (optional) ─────────────────
        val sessionToggle = CheckBox(this).apply {
            text = "Session limit (Discord/NoTube style)"
            setTextColor(Color.WHITE)
            isChecked = existingSession != null
            setPadding(0, dp(20), 0, dp(4))
        }
        root.addView(sessionToggle)

        root.addView(TextView(this).apply {
            text = "Each app open = one session. After session duration → cooldown. Lower priority than curfew + daily quota."
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(8))
        })

        val sessionFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (existingSession != null) View.VISIBLE else View.GONE
        }
        val durationPicker = NumberPicker(this).apply { minValue = 1; maxValue = 180; value = initialSessionMin; wrapSelectorWheel = false }
        val cooldownPicker = NumberPicker(this).apply { minValue = 1; maxValue = 1440; value = initialCooldownMin; wrapSelectorWheel = false }
        val maxSessionsPicker = NumberPicker(this).apply { minValue = 1; maxValue = 100; value = initialMaxSessions; wrapSelectorWheel = false }
        sessionFields.addView(buildSessionRow("Session duration:", durationPicker, "min"))
        sessionFields.addView(buildSessionRow("Cooldown:", cooldownPicker, "min"))
        sessionFields.addView(buildSessionRow("Max sessions/day:", maxSessionsPicker, ""))
        root.addView(sessionFields)
        sessionToggle.setOnCheckedChangeListener { _, checked ->
            sessionFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── Per-rule protection timer ────────────────
        var protectionDelaySec = existingLimit?.protectionDelaySec
        root.addView(buildProtectionTimerRow(protectionDelaySec) { protectionDelaySec = it })
        root.addView(TextView(this).apply {
            text = "Overrides the global delay for this app only. A huge value makes this limit " +
                "practically impossible to loosen on impulse."
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(8))
        })

        val scroll = ScrollView(this).apply { addView(root) }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Set Limit for ${getAppName(pkg)}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val newMins = dailyPicker.value
                val session = if (sessionToggle.isChecked) {
                    ConfigManager.SessionConfig(
                        sessionDurationSec = durationPicker.value * 60,
                        cooldownSec = cooldownPicker.value * 60,
                        maxSessionsPerDay = maxSessionsPicker.value
                    )
                } else null
                val newLimits = config.limits.filter { it.packageName != pkg }.toMutableList()
                newLimits.add(ConfigManager.AppLimit(
                    packageName = pkg,
                    maxMinutesPerDay = newMins,
                    maxSecondsPerDay = newMins * 60,
                    // Preserve existing day/hour restrictions if any (UI doesn't expose them yet).
                    allowedDays = existingLimit?.allowedDays ?: listOf(0,1,2,3,4,5,6),
                    allowedHoursStart = existingLimit?.allowedHoursStart ?: 0,
                    allowedHoursEnd = existingLimit?.allowedHoursEnd ?: 24 * 60,
                    allDay = existingLimit?.allDay ?: true,
                    session = session,
                    protectionDelaySec = protectionDelaySec,
                    channelBlocks = existingLimit?.channelBlocks ?: emptyList()
                ))
                val newConfig = ConfigManager.Config(newLimits, config.periodBlocks, config.installBlocks)
                saveConfigWithDelay(newConfig)
            }
            .setNegativeButton("Cancel", null)

        if (existingLimit != null) {
            builder.setNeutralButton("Delete timer") { _, _ ->
                val newLimits = config.limits.filter { it.packageName != pkg }
                val newConfig = ConfigManager.Config(newLimits, config.periodBlocks, config.installBlocks)
                saveConfigWithDelay(newConfig)
            }
        }

        val dialog = builder.create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun buildSessionRow(label: String, picker: NumberPicker, suffix: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(picker)
            if (suffix.isNotEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = " $suffix"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(dp(4), 0, 0, 0)
                })
            }
        }
    }

    private fun saveConfigWithDelay(newConfig: ConfigManager.Config) {
        val old = loadEditableConfig()

        // Use the EFFECTIVE delay, not the base one. The old gate tested globalDelaySeconds > 0,
        // so with a base delay of 0 every relaxation slipped through instantly even while a
        // "Scheduled Delay Rule" was active — the rule only ever influenced executeAt.
        val globalDelay = DelayManager.getCurrentEffectiveDelaySeconds(this)
        val requirement = ConfigManager.requiredDefer(old, newConfig, globalDelay)

        // A rule's own protection timer outranks the global delay *and* the settings unlock —
        // that is what "priority over the general timer" means. A 30-day curfew must not be
        // defusable through a 1h unlock. Rules without an explicit timer keep the old behaviour.
        val unlockApplies = !requirement.fromExplicitTimer && DelayManager.isSettingsUnlocked(this)
        val mustDefer = requirement.mustDefer && !unlockApplies

        if (!mustDefer) {
            ConfigManager.saveConfig(this, newConfig)
            Toast.makeText(this, "Config updated.", Toast.LENGTH_SHORT).show()
        } else {
            DelayManager.requestConfigUpdate(
                this,
                ConfigManager.configToJsonString(newConfig),
                describeConfigChange(old, newConfig),
                overrideDelaySeconds = requirement.seconds
            )
            val scope = if (requirement.fromExplicitTimer) "rule timer" else "global delay"
            Toast.makeText(
                this,
                "Change queued for ${formatLongDuration(requirement.seconds)} " +
                    "($scope — ${requirement.reason}).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshPeriodBlocksUI() {
        periodBlocksContainer.removeAllViews()
        val cfg = ConfigManager.loadConfig(this)
        if (cfg.periodBlocks.isEmpty()) {
            curfewCircle.setSummaryText("No curfew")
            periodBlocksContainer.addView(TextView(this).apply {
                text = "No curfew rules."
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val isNowActive = cfg.periodBlocks.any { rule ->
            dayOfWeek in rule.allowedDays && ConfigManager.isInBlockedWindow(nowMin, rule.blockedStartMinutes, rule.blockedEndMinutes)
        }

        if (isNowActive) {
            curfewCircle.setSummaryText("Active now", Color.parseColor("#E53935"))
        } else {
            val r = cfg.periodBlocks[0]
            val start = "%02d:%02d".format(r.blockedStartMinutes / 60, r.blockedStartMinutes % 60)
            val end = "%02d:%02d".format(r.blockedEndMinutes / 60, r.blockedEndMinutes % 60)
            if (cfg.periodBlocks.size == 1) {
                curfewCircle.setSummaryText("$start - $end")
            } else {
                curfewCircle.setSummaryText("${cfg.periodBlocks.size} rules · $start")
            }
        }

        cfg.periodBlocks.forEachIndexed { index, rule ->
            periodBlocksContainer.addView(buildPeriodRuleRow(rule, index))
        }
    }

    private fun buildPeriodRuleRow(
        rule: ConfigManager.PeriodBlockRule,
        index: Int
    ): LinearLayout {
        val start = "%02d:%02d".format(
            rule.blockedStartMinutes / 60,
            rule.blockedStartMinutes % 60
        )
        val end = "%02d:%02d".format(
            rule.blockedEndMinutes / 60,
            rule.blockedEndMinutes % 60
        )
        val names = rule.packages.joinToString(", ") { getAppName(it) }
        val days = formatDays(rule.allowedDays)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            val muteLabel = if (rule.muteNotifications) "Notifications: muted" else "Notifications: on"
            addView(TextView(this@MainActivity).apply {
                text = "$days\n$start -> $end\n$names\n$muteLabel"
                textSize = 14f
                setTextColor(Color.BLACK)
            })
            rule.protectionDelaySec?.let { sec ->
                addView(TextView(this@MainActivity).apply {
                    text = "🔒 Protected — ${formatLongDuration(sec)} to change"
                    textSize = 12f
                    setTextColor(Color.BLACK)
                })
            }
            val buttonRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            buttonRow.addView(Button(this@MainActivity).apply {
                text = "Edit"
                setTextColor(Color.WHITE)
                background = roundedBackground(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener { showEditCurfewRuleDialog(index, rule) }
            })
            buttonRow.addView(Button(this@MainActivity).apply {
                text = "Delete"
                setTextColor(Color.WHITE)
                background = roundedBackground(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    val cur = loadEditableConfig()
                    val newList = cur.periodBlocks.toMutableList().also { it.removeAt(index) }
                    saveConfigWithDelay(ConfigManager.Config(cur.limits, newList, cur.installBlocks))
                }
            })
            addView(buttonRow)
        }
    }

    private fun showAddCurfewRuleDialog() {
        val apps = getInstalledLaunchableApps()
        val selected = mutableSetOf<String>()

        val listView = buildAppCheckListView(apps, selected)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Apps for this curfew")
            .setView(listView)
            .setPositiveButton("Next") { _, _ ->
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showCurfewHoursDialog(selected.toList())
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showEditCurfewRuleDialog(index: Int, existingRule: ConfigManager.PeriodBlockRule) {
        val apps = getInstalledLaunchableApps()
        val selected = mutableSetOf<String>()

        val listView = buildAppCheckListView(apps, selected, preChecked = existingRule.packages.toSet())

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Edit curfew apps")
            .setView(listView)
            .setPositiveButton("Next") { _, _ ->
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showCurfewHoursDialog(
                    packages = selected.toList(),
                    editIndex = index,
                    existingStartH = existingRule.blockedStartMinutes / 60,
                    existingStartM = existingRule.blockedStartMinutes % 60,
                    existingEndH = existingRule.blockedEndMinutes / 60,
                    existingEndM = existingRule.blockedEndMinutes % 60,
                    existingDays = existingRule.allowedDays.toSet(),
                    existingMute = existingRule.muteNotifications,
                    existingProtectionDelaySec = existingRule.protectionDelaySec
                )
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showCurfewHoursDialog(
        packages: List<String>,
        editIndex: Int = -1,
        existingStartH: Int = 22, existingStartM: Int = 0,
        existingEndH: Int = 7, existingEndM: Int = 0,
        existingDays: Set<Int> = (0..6).toSet(),
        existingMute: Boolean = false,
        existingProtectionDelaySec: Int? = null
    ) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val selectedDays = mutableSetOf<Int>()
        val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dayChecked = BooleanArray(dayLabels.size) { it in existingDays }
        selectedDays.addAll(existingDays)

        wrap.addView(Button(this).apply {
            text = "Select days"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#333333"))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("Curfew days")
                    .setMultiChoiceItems(dayLabels, dayChecked) { _, which, isChecked ->
                        dayChecked[which] = isChecked
                        if (isChecked) selectedDays.add(which) else selectedDays.remove(which)
                    }
                    .setPositiveButton("OK", null)
                    .create().also { styleDialogForDarkTheme(it); it.show() }
            }
        })

        val daysHint = if (existingDays.size == 7) "All days selected." else "${formatDays(existingDays.sorted())} selected."
        wrap.addView(TextView(this).apply {
            text = daysHint
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        fun addRow(label: String, defH: Int, defM: Int): Pair<NumberPicker, NumberPicker> {
            wrap.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val h = NumberPicker(this).apply {
                minValue = 0
                maxValue = 23
                value = defH
            }
            val m = NumberPicker(this).apply {
                minValue = 0
                maxValue = 59
                value = defM
            }
            row.addView(h)
            row.addView(TextView(this).apply {
                text = " : "
                setTextColor(Color.WHITE)
            })
            row.addView(m)
            wrap.addView(row)
            return h to m
        }

        val (startH, startM) = addRow("Block from (24h)", existingStartH, existingStartM)
        val (endH, endM) = addRow("Until (next day if earlier, e.g. 07:00)", existingEndH, existingEndM)

        var muteNotifications = existingMute
        val muteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val muteSwitch = Switch(this).apply {
            text = "Mute notifications during curfew"
            setTextColor(Color.WHITE)
            isChecked = existingMute
            setOnCheckedChangeListener { _, checked -> muteNotifications = checked }
        }
        muteRow.addView(muteSwitch)
        wrap.addView(muteRow)

        var protectionDelaySec = existingProtectionDelaySec
        wrap.addView(buildProtectionTimerRow(protectionDelaySec) { protectionDelaySec = it })
        wrap.addView(TextView(this).apply {
            text = "Set a huge timer here to make this curfew practically permanent."
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(8))
        })

        val dialogTitle = if (editIndex >= 0) "Edit curfew hours" else "Curfew hours"
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(dialogTitle)
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val startMin = startH.value * 60 + startM.value
                val endMin = endH.value * 60 + endM.value
                if (startMin == endMin) {
                    Toast.makeText(this, "Start and end must differ.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (selectedDays.isEmpty()) {
                    Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cur = loadEditableConfig()
                val newRule = ConfigManager.PeriodBlockRule(
                    packages = packages,
                    blockedStartMinutes = startMin,
                    blockedEndMinutes = endMin,
                    allowedDays = selectedDays.sorted(),
                    muteNotifications = muteNotifications,
                    protectionDelaySec = protectionDelaySec
                )
                val newList = if (editIndex >= 0) {
                    cur.periodBlocks.toMutableList().also { it[editIndex] = newRule }
                } else {
                    cur.periodBlocks + newRule
                }
                saveConfigWithDelay(ConfigManager.Config(cur.limits, newList, cur.installBlocks))
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    // ──────────────────────────────────────
    //  Whitelist (Zero-Trust)
    // ──────────────────────────────────────

    private fun refreshInstallBlocksUI(force: Boolean = false) {
        val state = WhitelistManager.loadState(this)
        val allowedCount = state.allowedPackages.size
        val pendingCount = state.pendingRequests.size

        if (pendingCount > 0) {
            installBlocklistCircle.setSummaryText("$allowedCount autorisées · $pendingCount en attente")
        } else {
            installBlocklistCircle.setSummaryText("$allowedCount apps autorisées")
        }

        // Performance critical: do NOT build 400+ UI views if user is not looking inside this circle
        if (::zoomCanvas.isInitialized && zoomCanvas.zoomedCircle != installBlocklistCircle) {
            return
        }

        val currentSecond = System.currentTimeMillis() / 1000
        val needTimeRefresh = pendingCount > 0 && (currentSecond - lastPendingUpdateSecond >= 30)
        val stateChanged = state.allowedPackages != lastRenderedAllowed ||
            state.pendingRequests != lastRenderedPending ||
            state.quarantineDelayHours != lastRenderedDelayHours ||
            state.useGlobalDelay != lastRenderedUseGlobal ||
            state.pendingDelayExecuteAt != lastRenderedPendingDelayAt

        if (!force && !stateChanged && !needTimeRefresh) {
            return
        }

        lastRenderedAllowed = state.allowedPackages
        lastRenderedPending = state.pendingRequests
        lastRenderedDelayHours = state.quarantineDelayHours
        lastRenderedUseGlobal = state.useGlobalDelay
        lastRenderedPendingDelayAt = state.pendingDelayExecuteAt
        lastPendingUpdateSecond = currentSecond

        installBlocksContainer.removeAllViews()
        val now = System.currentTimeMillis()

        // 0. Configuration du Délai de Quarantaine
        val effectiveHours = WhitelistManager.getEffectiveQuarantineDelayHours(this)
        val delayDesc = if (state.useGlobalDelay) {
            "${effectiveHours}h (Aligné sur délai général)"
        } else {
            "${effectiveHours}h"
        }

        val delayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.parseColor("#1E2430"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                val txt = TextView(this@MainActivity).apply {
                    text = "⏱️ Délai de quarantaine : $delayDesc"
                    setTextColor(Color.parseColor("#E0E6ED"))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(txt)

                val editBtn = Button(this@MainActivity).apply {
                    text = "Modifier"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#3A4558"))
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setOnClickListener { showChangeWhitelistDelayDialog() }
                }
                addView(editBtn)
            }
            addView(row)

            if (state.pendingDelayExecuteAt > now && state.pendingDelayHours != null) {
                val remainSec = ((state.pendingDelayExecuteAt - now) / 1000).toInt()
                val targetText = if (state.pendingDelayUseGlobal) "Aligné sur délai général" else "${state.pendingDelayHours}h"
                val pendingRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    addView(TextView(this@MainActivity).apply {
                        text = "⏳ Réduction vers $targetText dans ${formatTime(remainSec)}"
                        setTextColor(Color.parseColor("#FFCA28"))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Annuler"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        background = roundedBackground(Color.parseColor("#C62828"))
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                        setOnClickListener {
                            WhitelistManager.cancelPendingDelayChange(this@MainActivity)
                            Toast.makeText(this@MainActivity, "Modification annulée", Toast.LENGTH_SHORT).show()
                            refreshInstallBlocksUI(force = true)
                            refreshDelayUI()
                        }
                    })
                }
                addView(pendingRow)
            }
        }
        installBlocksContainer.addView(delayCard)

        // 1. Quarantaine (Demandes en attente)
        if (state.pendingRequests.isNotEmpty()) {
            installBlocksContainer.addView(TextView(this).apply {
                text = "⏳ En quarantaine ($pendingCount)"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E65100"))
                setPadding(dp(8), dp(8), dp(8), dp(4))
            })

            for (req in state.pendingRequests) {
                val remainMs = (req.availableAt - now).coerceAtLeast(0L)
                val remainHours = remainMs / 3600_000L
                val remainMins = (remainMs % 3600_000L) / 60_000L
                val timeStr = if (remainHours > 0) "${remainHours}h ${remainMins}m" else "${remainMins}m"
                val appLabel = getAppName(req.packageName)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = roundedBackground(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }

                    val icon = getAppIcon(req.packageName)
                    val iconView = ImageView(this@MainActivity).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
                    }
                    addView(iconView)

                    val textLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(this@MainActivity).apply {
                            text = appLabel
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.BLACK)
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "Débloquée dans $timeStr"
                            textSize = 12f
                            setTextColor(Color.parseColor("#E65100"))
                        })
                    }
                    addView(textLayout)

                    addView(Button(this@MainActivity).apply {
                        text = "Annuler"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        background = roundedBackground(Color.parseColor("#C62828"))
                        setOnClickListener {
                            WhitelistManager.cancelPendingRequest(this@MainActivity, req.packageName)
                            Toast.makeText(this@MainActivity, "Demande annulée", Toast.LENGTH_SHORT).show()
                            refreshInstallBlocksUI()
                        }
                    })
                }
                installBlocksContainer.addView(row)
            }
        }

        // 2. Applications autorisées (Whitelist)
        installBlocksContainer.addView(TextView(this).apply {
            text = "✅ Applications autorisées ($allowedCount)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setPadding(dp(8), dp(10), dp(8), dp(4))
        })

        val sortedList = state.allowedPackages.sortedBy { pkg ->
            getAppName(pkg).lowercase()
        }

        for (pkg in sortedList) {
            val appLabel = getAppName(pkg)
            val icon = getAppIcon(pkg)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedBackground(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }

                val iconView = ImageView(this@MainActivity).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
                }
                addView(iconView)

                val textLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = appLabel
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.BLACK)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = pkg
                        textSize = 11f
                        setTextColor(Color.parseColor("#757575"))
                    })
                }
                addView(textLayout)

                if (pkg != packageName) {
                    addView(Button(this@MainActivity).apply {
                        text = "Bloquer"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        background = roundedBackground(Color.BLACK)
                        setOnClickListener {
                            showConfirmRemoveFromWhitelistDialog(pkg, appLabel)
                        }
                    })
                }
            }
            installBlocksContainer.addView(row)
        }
    }

    private fun showRequestWhitelistAppDialog() {
        val state = WhitelistManager.loadState(this)
        val hiddenPackages = WhitelistManager.loadHiddenState(this)
        val pm = packageManager
        val candidates = try {
            pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isHiddenByUs = app.packageName in hiddenPackages
                    val isLaunchable = pm.getLaunchIntentForPackage(app.packageName) != null
                    app.packageName != packageName &&
                        app.packageName !in state.allowedPackages &&
                        (isHiddenByUs || !isSystem || isLaunchable)
                }
                .sortedWith(
                    compareByDescending<ApplicationInfo> { it.packageName in hiddenPackages }
                        .thenBy { getAppName(it.packageName).lowercase() }
                )
        } catch (e: Exception) {
            emptyList()
        }

        val items = mutableListOf<String>()
        val packageMap = mutableListOf<String>()

        items.add("✏️ Saisir un nom de package...")
        packageMap.add("")

        for (app in candidates) {
            val label = getAppName(app.packageName)
            val prefix = if (app.packageName in hiddenPackages) "⚡ Récemment installée / Bloquée : " else ""
            items.add("$prefix$label (${app.packageName})")
            packageMap.add(app.packageName)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Demander une application")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCustomPackageInputDialog()
                } else {
                    val pkg = packageMap[which]
                    confirmRequestAddition(pkg, getAppName(pkg))
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showCustomPackageInputDialog() {
        val input = EditText(this).apply {
            hint = "com.exemple.application"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Nom exact du package")
            .setView(wrap)
            .setPositiveButton("Suivant") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    confirmRequestAddition(pkg, getAppName(pkg))
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun confirmRequestAddition(pkg: String, label: String) {
        val delaySec = WhitelistManager.getEffectiveQuarantineDelaySeconds(this)
        val delayHours = WhitelistManager.getEffectiveQuarantineDelayHours(this)
        val delayText = if (delaySec >= 3600) "${delayHours} heure(s)" else "${delaySec / 60} minute(s)"
        val unlockTime = System.currentTimeMillis() + delaySec * 1000L
        val unlockDateStr = java.text.SimpleDateFormat("dd/MM/yyyy à HH:mm", java.util.Locale.FRANCE).format(java.util.Date(unlockTime))

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Mettre en quarantaine ?")
            .setMessage(
                "L'application \"$label\" sera placée dans un sas de quarantaine de $delayText.\n\n" +
                    "Pendant cette période, elle reste complètement bloquée et masquée.\n" +
                    "Elle rejoindra automatiquement la Whitelist le $unlockDateStr."
            )
            .setPositiveButton("Confirmer ($delayText)") { _, _ ->
                val ok = WhitelistManager.requestAppAddition(this, pkg, delaySec)
                if (ok) {
                    Toast.makeText(this, "\"$label\" mise en quarantaine ($delayText)", Toast.LENGTH_LONG).show()
                    refreshInstallBlocksUI(force = true)
                    refreshDelayUI()
                } else {
                    Toast.makeText(this, "Application déjà dans la whitelist ou en attente", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showChangeWhitelistDelayDialog() {
        val globalDelaySec = DelayManager.getCurrentEffectiveDelaySeconds(this)
        val globalDelayHours = (globalDelaySec / 3600).coerceAtLeast(1)
        val globalDesc = if (globalDelaySec >= 3600) "${globalDelayHours}h" else "${globalDelaySec / 60}m"
        val options = arrayOf(
            "Délai dédié : 12 heures",
            "Délai dédié : 24 heures (Défaut)",
            "Délai dédié : 48 heures",
            "Délai dédié : 72 heures",
            "Délai dédié personnalisé...",
            "🔗 Aligner sur le délai général ($globalDesc)"
        )

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Délai de quarantaine Whitelist")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyWhitelistDelayChange(12, false)
                    1 -> applyWhitelistDelayChange(24, false)
                    2 -> applyWhitelistDelayChange(48, false)
                    3 -> applyWhitelistDelayChange(72, false)
                    4 -> showCustomWhitelistDelayDialog()
                    5 -> applyWhitelistDelayChange(globalDelayHours, true)
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun applyWhitelistDelayChange(hours: Int, useGlobal: Boolean) {
        val result = WhitelistManager.setQuarantineDelay(this, hours, useGlobal)
        Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        refreshInstallBlocksUI(force = true)
        refreshDelayUI()
    }

    private fun showCustomWhitelistDelayDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 720
            value = WhitelistManager.getEffectiveQuarantineDelayHours(this@MainActivity)
            wrapSelectorWheel = false
        }
        layout.addView(picker)

        layout.addView(TextView(this).apply {
            text = " heure(s)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Délai personnalisé (heures)")
            .setView(layout)
            .setPositiveButton("Confirmer") { _, _ ->
                applyWhitelistDelayChange(picker.value, false)
            }
            .setNegativeButton("Retour") { _, _ -> showChangeWhitelistDelayDialog() }
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showConfirmRemoveFromWhitelistDialog(pkg: String, label: String) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Bloquer \"$label\" ?")
            .setMessage(
                "L'application sera immédiatement retirée de la Whitelist.\n\n" +
                    "Elle sera masquée du lanceur et son accès sera instantanément verrouillé."
            )
            .setPositiveButton("Bloquer immédiatement") { _, _ ->
                WhitelistManager.removePackageFromWhitelist(this, pkg)
                Toast.makeText(this, "\"$label\" a été bloquée", Toast.LENGTH_SHORT).show()
                refreshInstallBlocksUI(force = true)
                refreshDelayUI()
            }
            .setNegativeButton("Annuler", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    // ── CSV import ──────────────────────────────────────

    private fun launchCsvPicker() {
        // Providers report CSV as text/csv, text/comma-separated-values, text/plain or
        // application/octet-stream depending on the source — filtering would hide real files.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_IMPORT_CSV)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_CSV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "CSV read failed: ${e.message}")
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Could not read that file.", Toast.LENGTH_LONG).show()
            return
        }
        confirmCsvImport(text)
    }

    private fun confirmCsvImport(text: String) {
        val result = InstallBlockManager.parseCsv(text)
        if (result.entries.isEmpty()) {
            val hint = if (result.rejected.isEmpty()) {
                "The file is empty."
            } else {
                "${result.rejected.size} line(s) rejected. Expected columns: group,package"
            }
            Toast.makeText(this, "No valid row found. $hint", Toast.LENGTH_LONG).show()
            return
        }

        val perGroup = result.entries.groupBy({ it.first }, { it.second })
            .entries.sortedBy { it.key }
            .joinToString("\n") { "  • ${it.key}: ${it.value.distinct().size} package(s)" }
        val rejectedNote = if (result.rejected.isEmpty()) "" else
            "\n\n${result.rejected.size} line(s) ignored (not a package name):\n" +
                result.rejected.take(5).joinToString("\n") { "  ✗ $it" } +
                if (result.rejected.size > 5) "\n  …" else ""

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Import ${result.packageCount} package(s)?")
            .setMessage(
                "$perGroup$rejectedNote\n\n" +
                    "Merge is add-only: existing groups gain packages, nothing is removed and no " +
                    "timer changes. That makes it a hardening, so it applies immediately."
            )
            .setPositiveButton("Import") { _, _ ->
                val cur = loadEditableConfig()
                saveConfigWithDelay(InstallBlockManager.mergeIntoConfig(cur, result))
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun loadEditableConfig(): ConfigManager.Config {
        val pendingJson = DelayManager.getLatestPendingConfigJson(this)
        val pendingConfig = if (pendingJson != null) ConfigManager.fromJsonString(pendingJson) else null
        return pendingConfig ?: ConfigManager.loadConfig(this)
    }

    private fun describeConfigChange(old: ConfigManager.Config, newConfig: ConfigManager.Config): String {
        val diffLines = describeConfigDiff(old, newConfig)
        if (diffLines.isNotEmpty()) {
            return diffLines.joinToString("; ")
        }

        val oldPkgs = old.limits.map { it.packageName }.toSet()
        val newPkgs = newConfig.limits.map { it.packageName }.toSet()

        val removed = oldPkgs - newPkgs
        if (removed.size == 1) return "Delete timer: ${getAppName(removed.first())}"
        if (removed.size > 1) return "Delete ${removed.size} timers"

        val added = newPkgs - oldPkgs
        if (added.size == 1) return "Add timer: ${getAppName(added.first())}"

        if (newConfig.periodBlocks.size > old.periodBlocks.size) return "Add curfew rule"
        if (newConfig.periodBlocks.size < old.periodBlocks.size) return "Delete curfew rule"

        return "Update config"
    }

    private fun describeConfigDiff(current: ConfigManager.Config, pending: ConfigManager.Config): List<String> {
        val lines = mutableListOf<String>()

        // App Limits diff
        val oldLimits = current.limits.associateBy { it.packageName }
        val newLimits = pending.limits.associateBy { it.packageName }
        val allPkgs = (oldLimits.keys + newLimits.keys).toSortedSet()
        val limitChanges = mutableListOf<String>()
        for (pkg in allPkgs) {
            val old = oldLimits[pkg]
            val new = newLimits[pkg]
            val name = getAppName(pkg)
            when {
                old == null && new != null -> {
                    limitChanges.add("  \u2022 [NEW] $name: ${new.maxMinutesPerDay}m/day")
                    new.session?.let { limitChanges.add("    \u21b3 session: ${it.sessionDurationSec/60}m, cooldown ${it.cooldownSec/60}m, max ${it.maxSessionsPerDay}/day") }
                }
                old != null && new == null ->
                    limitChanges.add("  \u2022 [REMOVED] $name")
                old != null && new != null -> {
                    if (old.maxMinutesPerDay != new.maxMinutesPerDay) {
                        limitChanges.add("  \u2022 $name: ${old.maxMinutesPerDay}m/day \u2192 ${new.maxMinutesPerDay}m/day")
                    }
                    val sessionDesc = describeSessionDiff(name, old.session, new.session)
                    if (sessionDesc != null) limitChanges.add(sessionDesc)
                }
            }
        }
        if (limitChanges.isNotEmpty()) {
            lines.add("App Limits:")
            lines.addAll(limitChanges)
        }

        // Curfew Rules diff
        val oldRules = current.periodBlocks
        val newRules = pending.periodBlocks
        val curfewChanges = mutableListOf<String>()

        // Check for modified rules at same indices
        for (i in 0 until minOf(oldRules.size, newRules.size)) {
            val o = oldRules[i]
            val n = newRules[i]
            if (o != n) {
                val apps = n.packages.joinToString(", ") { getAppName(it) }
                val start = "%02d:%02d".format(n.blockedStartMinutes / 60, n.blockedStartMinutes % 60)
                val end = "%02d:%02d".format(n.blockedEndMinutes / 60, n.blockedEndMinutes % 60)
                curfewChanges.add("  \u2022 [MODIFIED] $apps $start\u2192$end (${formatDays(n.allowedDays)})")
            }
        }
        // Added rules
        if (newRules.size > oldRules.size) {
            for (i in oldRules.size until newRules.size) {
                val r = newRules[i]
                val apps = r.packages.joinToString(", ") { getAppName(it) }
                val start = "%02d:%02d".format(r.blockedStartMinutes / 60, r.blockedStartMinutes % 60)
                val end = "%02d:%02d".format(r.blockedEndMinutes / 60, r.blockedEndMinutes % 60)
                curfewChanges.add("  \u2022 [NEW] $apps blocked $start\u2192$end (${formatDays(r.allowedDays)})")
            }
        }
        // Removed rules
        if (newRules.size < oldRules.size) {
            for (i in newRules.size until oldRules.size) {
                val r = oldRules[i]
                val apps = r.packages.joinToString(", ") { getAppName(it) }
                curfewChanges.add("  \u2022 [REMOVED] $apps curfew")
            }
        }

        if (curfewChanges.isNotEmpty()) {
            lines.add("Curfew Rules:")
            lines.addAll(curfewChanges)
        }

        return lines
    }

    private fun describeSessionDiff(
        appName: String,
        old: ConfigManager.SessionConfig?,
        new: ConfigManager.SessionConfig?
    ): String? {
        fun fmt(s: ConfigManager.SessionConfig) =
            "${s.sessionDurationSec/60}m / cooldown ${s.cooldownSec/60}m / max ${s.maxSessionsPerDay}/day"
        return when {
            old == null && new != null -> "  • $appName session: [NEW] ${fmt(new)}"
            old != null && new == null -> "  • $appName session: [REMOVED]"
            old != null && new != null && old != new -> "  • $appName session: ${fmt(old)} → ${fmt(new)}"
            else -> null
        }
    }

    private fun formatDays(days: List<Int>): String {
        val sorted = days.distinct().sorted()
        if (sorted.size == 7) return "Every day"
        val labels = mapOf(0 to "Sun", 1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat")
        return sorted.joinToString(", ") { labels[it] ?: it.toString() }
    }

    // ──────────────────────────────────────
    //  Dashboard refresh
    // ──────────────────────────────────────

    private fun refreshDashboard() {
        val usage = LimitService.getUsageData()
        val limits = LimitService.getLimits()
        val blocked = LimitService.getBlockedApps()

        serviceStatusText.text = if (LimitService.isRunning)
            "Service Active — ${limits.size} app(s) monitored"
        else
            "Service starting..."

        deviceOwnerStatusText.text = DeviceOwnerHelper.statusLabel(this)
        val isDO = DeviceOwnerHelper.isDeviceOwner(this)
        deviceOwnerStatusText.setBackgroundColor(
            Color.parseColor(if (isDO) "#1B3D1B" else "#3D2F1B")
        )
        deviceOwnerStatusText.setTextColor(
            Color.parseColor(if (isDO) "#A5D6A7" else "#FFCC80")
        )

        val blockedCount = limits.keys.count { it in blocked }
        if (limits.isEmpty()) {
            appLimitsCircle.setSummaryText("No limits")
            dashboardContainer.removeAllViews()
            dashboardContainer.addView(TextView(this).apply {
                text = "No limits configured."
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            return
        } else if (blockedCount > 0) {
            appLimitsCircle.setSummaryText("${limits.size} apps · $blockedCount blocked", Color.parseColor("#E53935"))
        } else {
            appLimitsCircle.setSummaryText("${limits.size} apps")
        }

        // Rebuild the rows whenever the set of monitored packages changes. Comparing by
        // childCount alone was buggy: an empty list leaves a "No limits configured."
        // placeholder (childCount == 1), so adding the FIRST app (limits.size == 1) matched
        // and the rebuild was skipped — the app only appeared once a second was added.
        val currentTags = (0 until dashboardContainer.childCount).mapNotNull {
            (dashboardContainer.getChildAt(it) as? LinearLayout)?.tag as? String
        }.toSet()
        if (currentTags != limits.keys) {
            dashboardContainer.removeAllViews()
            for ((pkg, limit) in limits) {
                dashboardContainer.addView(buildAppRow(pkg, limit))
            }
        }

        for (i in 0 until dashboardContainer.childCount) {
            val row = dashboardContainer.getChildAt(i) as? LinearLayout ?: continue
            val pkg = row.tag as? String ?: continue
            val limit = limits[pkg] ?: continue
            val usedSeconds = usage[pkg] ?: 0
            val maxSeconds = limit.maxSecondsPerDay
            val isBlocked = pkg in blocked

            val progressBar = row.findViewWithTag<ProgressBar>("progress_$pkg")
            val usageText = row.findViewWithTag<TextView>("usage_$pkg")
            val statusText = row.findViewWithTag<TextView>("status_$pkg")
            val sessionText = row.findViewWithTag<TextView>("session_$pkg")

            progressBar?.progress = if (maxSeconds > 0) ((usedSeconds * 100) / maxSeconds).coerceAtMost(100) else 0
            usageText?.text = "${formatTime(usedSeconds)} / ${formatTime(maxSeconds)}"

            if (isBlocked) {
                statusText?.text = "BLOCKED"
                statusText?.setTextColor(Color.parseColor("#FF5252"))
                statusText?.visibility = View.VISIBLE
            } else {
                statusText?.visibility = View.GONE
            }

            val sessionCfg = limit.session
            if (sessionCfg != null && sessionText != null) {
                val st = SessionManager.statusOf(this, pkg, sessionCfg, System.currentTimeMillis())
                sessionText.text = formatSessionStatus(st)
                sessionText.visibility = View.VISIBLE
            } else {
                sessionText?.visibility = View.GONE
            }
        }
    }

    private fun formatSessionStatus(s: SessionManager.Status): String {
        return when (s.state) {
            SessionManager.Status.State.ACTIVE -> {
                val m = s.sessionRemainingSec / 60
                val sec = s.sessionRemainingSec % 60
                "▶ session ${m}m${"%02d".format(sec)}s left · ${s.sessionsUsed}/${s.maxSessionsPerDay} today"
            }
            SessionManager.Status.State.COOLDOWN -> {
                "⏸ cooldown until ${formatClockTime(s.cooldownEndsAtMs)} · ${s.sessionsUsed}/${s.maxSessionsPerDay} today"
            }
            SessionManager.Status.State.DAILY_EXHAUSTED -> {
                "✗ ${s.sessionsUsed}/${s.maxSessionsPerDay} sessions · resets at 02:00"
            }
            SessionManager.Status.State.READY -> {
                "○ ${s.sessionsUsed}/${s.maxSessionsPerDay} sessions today · ready"
            }
        }
    }

    private fun formatClockTime(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun buildAppRow(pkg: String, limit: ConfigManager.AppLimit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            tag = pkg
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = roundedBackground(Color.WHITE)
            setOnClickListener { showEditAppDialog(pkg) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        val icon = ImageView(this).apply {
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12)
            }
            setImageDrawable(getAppIcon(pkg))
        }
        row.addView(icon)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        nameRow.addView(TextView(this).apply {
            text = getAppName(pkg)
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })

        nameRow.addView(TextView(this).apply {
            tag = "status_$pkg"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
            visibility = View.GONE
        })

        infoCol.addView(nameRow)

        infoCol.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "progress_$pkg"
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(2)
            }
        })

        infoCol.addView(TextView(this).apply {
            tag = "usage_$pkg"
            textSize = 12f
            setTextColor(Color.BLACK)
        })

        infoCol.addView(TextView(this).apply {
            tag = "session_$pkg"
            textSize = 11f
            setTextColor(Color.BLACK)
            visibility = View.GONE
        })

        row.addView(infoCol)
        return row
    }

    private fun refreshNuclearStatus() {
        val state = LimitService.getNuclearState()
        if (state != null && state.active && !NuclearManager.isExpired(state)) {
            nuclearStatusContainer.visibility = View.VISIBLE
            nuclearSetupContainer.visibility = View.GONE

            val remaining = state.endTimestamp - System.currentTimeMillis()
            val remainSec = (remaining / 1000).toInt().coerceAtLeast(0)
            nuclearCircle.setSummaryText(formatTime(remainSec), Color.parseColor("#E53935"))
            nuclearCountdownText.text = "Time Remaining: ${formatTime(remainSec)}"

            val appNames = state.blockedPackages.joinToString("\n") { "  • ${getAppName(it)}" }
            nuclearBlockedListText.text = "Blocked Apps:\n$appNames"

            val existingCancelUi = nuclearStatusContainer.findViewWithTag<View>("nuclear_cancel_controls")
            if (existingCancelUi != null) {
                nuclearStatusContainer.removeView(existingCancelUi)
            }

            val controls = LinearLayout(this).apply {
                tag = "nuclear_cancel_controls"
                orientation = LinearLayout.VERTICAL
            }

            if (state.cancelExecuteAt > 0L) {
                val cancelRemainSec = ((state.cancelExecuteAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                controls.addView(TextView(this).apply {
                    text = "Cancel pending: ${formatTime(cancelRemainSec)}"
                    setTextColor(Color.parseColor("#FFCA28"))
                    setPadding(0, dp(4), 0, dp(6))
                })
                controls.addView(Button(this).apply {
                    text = "Keep Nuclear Mode"
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#333333"))
                    setOnClickListener { LimitService.cancelPendingNuclearCancel() }
                })
            } else {
                controls.addView(Button(this).apply {
                    text = "Request Nuclear Cancel (Delayed)"
                    setTextColor(Color.WHITE)
                    background = roundedBackground(Color.parseColor("#D32F2F"))
                    setOnClickListener {
                        LimitService.requestCancelNuclearMode()
                        Toast.makeText(
                            this@MainActivity,
                            "Cancel requested with current protection delay.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
            nuclearStatusContainer.addView(controls)
        } else {
            nuclearCircle.setSummaryText("Inactive")
            nuclearStatusContainer.visibility = View.GONE
            nuclearSetupContainer.visibility = View.VISIBLE
        }
    }

    private fun showNuclearSetupDialog() {
        selectedNuclearApps.clear()

        val apps = getInstalledLaunchableApps()
        val monitoredApps = loadEditableConfig().limits.map { it.packageName }.toSet()

        val listView = buildAppCheckListView(apps, selectedNuclearApps, preChecked = monitoredApps)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Select Apps to Block")
            .setView(listView)
            .setPositiveButton("Next") { _, _ ->
                if (selectedNuclearApps.isEmpty()) {
                    Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showDurationDialog()
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showDurationDialog() {
        val durations = arrayOf("30 minutes", "1 hour", "2 hours", "4 hours", "Custom...")
        val durationMs = longArrayOf(
            30 * 60_000L,
            60 * 60_000L,
            2 * 60 * 60_000L,
            4 * 60 * 60_000L,
            0L
        )

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Block Duration")
            .setItems(durations) { _, which ->
                if (which < 4) {
                    selectedDurationMs = durationMs[which]
                    confirmNuclearActivation()
                } else {
                    showCustomDurationDialog()
                }
            }
            .setNegativeButton("Back") { _, _ -> showNuclearSetupDialog() }
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showCustomDurationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val picker = NumberPicker(this).apply {
            minValue = 5
            maxValue = 480
            value = 60
            wrapSelectorWheel = false
        }
        layout.addView(picker)

        layout.addView(TextView(this).apply {
            text = " minutes"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })

         val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Custom Duration")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                selectedDurationMs = picker.value * 60_000L
                confirmNuclearActivation()
            }
            .setNegativeButton("Back") { _, _ -> showDurationDialog() }
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun confirmNuclearActivation() {
        val appNames = selectedNuclearApps.joinToString("\n") { "  • ${getAppName(it)}" }
        val durationText = formatTime((selectedDurationMs / 1000).toInt())

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Confirm Nuclear Mode")
            .setMessage(
                "Block ${selectedNuclearApps.size} app(s) for $durationText?\n\n" +
                "$appNames\n\n" +
                "⚠️ Do Not Disturb will be LOCKED ON for the whole duration — you won't be able to turn it off.\n\n" +
                "Configure your DND exceptions (priority contacts, alarms, calls…) NOW in Android Settings > Sound > Do Not Disturb before confirming.\n\n" +
                "This action is IRREVERSIBLE."
            )
            .setPositiveButton("BLOCK") { _, _ ->
                LimitService.startNuclearMode(
                    selectedNuclearApps.toList(),
                    selectedDurationMs
                )
                Toast.makeText(this, "Nuclear Mode Activated!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("💾 Save as preset", null)
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
        // Override default dismiss-on-click so saving keeps the confirm dialog open.
        // Must be set after show() because the buttons are only created then.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            showSavePresetDialog(selectedNuclearApps.toList(), selectedDurationMs)
        }
    }

    // ──────────────────────────────────────
    //  Nuclear Mode Presets
    // ──────────────────────────────────────

    private fun startNuclearActivationFlow() {
        val presets = NuclearPresetsManager.loadPresets(this)
        if (presets.isEmpty()) {
            showNuclearSetupDialog()
            return
        }

        val labels = mutableListOf<String>()
        labels.add("➕ Configure new…")
        for (p in presets) {
            labels.add("• ${p.name}  (${p.packages.size} apps, ${formatTime((p.durationMs / 1000).toInt())})")
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Activate Nuclear Mode")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    showNuclearSetupDialog()
                } else {
                    val preset = presets[which - 1]
                    applyPresetAndConfirm(preset)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun applyPresetAndConfirm(preset: NuclearPresetsManager.Preset) {
        val installed = getInstalledLaunchableApps().map { it.packageName }.toSet()
        val available = preset.packages.filter { it in installed }
        val missing = preset.packages.size - available.size

        selectedNuclearApps.clear()
        selectedNuclearApps.addAll(available)
        selectedDurationMs = preset.durationMs

        if (selectedNuclearApps.isEmpty()) {
            Toast.makeText(this, "Preset apps are not installed on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (missing > 0) {
            Toast.makeText(this, "$missing app(s) from preset not installed — skipped", Toast.LENGTH_SHORT).show()
        }
        confirmNuclearActivation()
    }

    private fun showSavePresetDialog(packages: List<String>, durationMs: Long) {
        val input = EditText(this).apply {
            hint = "Preset name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Save as preset")
            .setMessage("Save this selection (${packages.size} apps, ${formatTime((durationMs / 1000).toInt())}) for one-tap reuse.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                NuclearPresetsManager.upsertPreset(
                    this,
                    NuclearPresetsManager.Preset(name, packages, durationMs)
                )
                Toast.makeText(this, "Preset \"$name\" saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showManagePresetsDialog() {
        val presets = NuclearPresetsManager.loadPresets(this)
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets saved yet", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = presets.map {
            "${it.name}  (${it.packages.size} apps, ${formatTime((it.durationMs / 1000).toInt())})"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Manage Presets")
            .setItems(labels) { _, which ->
                showPresetActionsDialog(presets[which])
            }
            .setNegativeButton("Close", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showPresetActionsDialog(preset: NuclearPresetsManager.Preset) {
        val actions = arrayOf("Rename", "Delete")
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(preset.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenamePresetDialog(preset)
                    1 -> confirmDeletePreset(preset)
                }
            }
            .setNegativeButton("Back") { _, _ -> showManagePresetsDialog() }
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showRenamePresetDialog(preset: NuclearPresetsManager.Preset) {
        val input = EditText(this).apply {
            setText(preset.name)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Rename preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == preset.name) return@setPositiveButton
                NuclearPresetsManager.renamePreset(this, preset.name, newName)
                Toast.makeText(this, "Renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun confirmDeletePreset(preset: NuclearPresetsManager.Preset) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Delete preset?")
            .setMessage("Remove \"${preset.name}\" permanently?")
            .setPositiveButton("Delete") { _, _ ->
                NuclearPresetsManager.deletePreset(this, preset.name)
                Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    // ──────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────

    private fun getAppName(pkg: String): String {
        return appNameCache.getOrPut(pkg) {
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg.substringAfterLast('.')
            }
        }
    }

    private fun getAppIcon(pkg: String): Drawable {
        return appIconCache.getOrPut(pkg) {
            try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                getDrawable(android.R.drawable.sym_def_app_icon)!!
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h${m.toString().padStart(2, '0')}m${s.toString().padStart(2, '0')}s"
            m > 0 -> "${m}m${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }

    private fun formatShortDuration(seconds: Int): String {
        if (seconds <= 0) return "0s"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /**
     * Coarse, readable duration for protection timers, which run to weeks — [formatTime] would
     * render 30 days as "720h00m00s".
     */
    private fun formatLongDuration(seconds: Int): String {
        if (seconds <= 0) return "no delay"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3600
        val m = (seconds % 3600) / 60
        val parts = mutableListOf<String>()
        if (d > 0) parts.add("${d}d")
        if (h > 0) parts.add("${h}h")
        if (m > 0 && d == 0) parts.add("${m}min")
        if (parts.isEmpty()) parts.add("${seconds}s")
        return parts.joinToString(" ")
    }

    /** Label for a rule's own timer — null means "inherit the global delay". */
    private fun protectionTimerLabel(seconds: Int?): String =
        if (seconds == null) "global delay" else formatLongDuration(seconds)

    /**
     * Pick a rule's own protection timer. Presets rather than a NumberPicker: the useful range
     * runs from an hour to a month, which no scroll wheel handles gracefully.
     *
     * Lowering the value is itself a relaxation, so it goes back through the delay gate at save
     * time (ConfigManager.requiredDefer) — the dialog says so rather than silently deferring.
     */
    private fun showProtectionTimerDialog(current: Int?, onPicked: (Int?) -> Unit) {
        val presets = listOf<Pair<String, Int?>>(
            "Use global delay" to null,
            "1 hour" to 3_600,
            "6 hours" to 21_600,
            "24 hours" to 86_400,
            "3 days" to 259_200,
            "7 days" to 604_800,
            "30 days" to 2_592_000
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        column.addView(TextView(this).apply {
            text = "Currently: ${protectionTimerLabel(current)}.\n\n" +
                "This timer replaces the global delay for this rule only. Raising it applies " +
                "immediately; lowering or removing it has to wait out the current timer."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, dp(12))
        })

        // The choices live in a custom view, not in setItems(): AlertController gives the message
        // and the setItems() list the same slot, so a builder with both renders the text and
        // silently drops every choice — the dialog came up with nothing but "Cancel".
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Protection timer")
            .setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton("Cancel", null)
            .create()

        fun addChoice(label: String, highlighted: Boolean, onClick: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                background = roundedBackground(
                    if (highlighted) Color.parseColor("#BB86FC") else Color.parseColor("#333333")
                )
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })
        }

        for ((label, value) in presets) {
            addChoice(label, highlighted = value == current) { onPicked(value) }
        }
        addChoice("Custom (hours)…", highlighted = false) {
            showCustomProtectionTimerDialog(current, onPicked)
        }

        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showCustomProtectionTimerDialog(current: Int?, onPicked: (Int?) -> Unit) {
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 8_760          // one year
            value = ((current ?: 3_600) / 3_600).coerceIn(1, 8_760)
            wrapSelectorWheel = false
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "Hours"
                setTextColor(Color.WHITE)
            })
            addView(picker)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Custom protection timer")
            .setView(wrap)
            .setPositiveButton("OK") { _, _ -> onPicked(picker.value * 3_600) }
            .setNegativeButton("Back") { _, _ -> showProtectionTimerDialog(current, onPicked) }
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    /**
     * "Protection timer: X" plus a button that reassigns [holder] — used by the app-limit and
     * curfew editors, which both keep the pending value in a local var until Save.
     */
    private fun buildProtectionTimerRow(
        initial: Int?,
        onChanged: (Int?) -> Unit
    ): LinearLayout {
        var current = initial
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        val label = TextView(this).apply {
            text = "Protection timer: ${protectionTimerLabel(current)}"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        row.addView(Button(this).apply {
            text = "Change"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#333333"))
            setOnClickListener {
                showProtectionTimerDialog(current) { picked ->
                    current = picked
                    label.text = "Protection timer: ${protectionTimerLabel(picked)}"
                    onChanged(picked)
                }
            }
        })
        return row
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.parseColor("#BB86FC"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(16), 0, dp(8))
        }
    }

    /**
     * Section title with a small ⓘ help button that opens a styled explanation dialog.
     */
    private fun sectionTitleWithHelp(text: String, helpContent: HelpContent): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 18f
                setTextColor(Color.parseColor("#BB86FC"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(16), 0, dp(8))
            })

            addView(TextView(this@MainActivity).apply {
                this.text = "ⓘ"
                textSize = 16f
                setTextColor(Color.parseColor("#666666"))
                setPadding(dp(8), dp(14), dp(4), dp(8))
                setOnClickListener { showFeatureHelpDialog(helpContent) }
            })
        }
    }

    // ──────────────────────────────────────
    //  In-App Help & Documentation
    // ──────────────────────────────────────

    private data class HelpContent(
        val title: String,
        val emoji: String,
        val summary: String,
        val steps: List<String>,
        val tip: String
    )

    private val HELP_DELAY = HelpContent(
        title = "Security & Delay",
        emoji = "⏳",
        summary = "The delay is your safety net. It prevents impulsive changes to your settings.",
        steps = listOf(
            "Choose a delay duration (e.g. 30 minutes, 1 hour).",
            "Once set, ANY change you make (adding/removing apps, editing curfews, uninstalling) will require waiting for the full delay before it takes effect.",
            "This gives you time to reconsider — by the time the delay expires, the urge to change your settings will likely have passed.",
            "Think of it like a cooling-off period for your digital willpower.",
            "Any single rule can also carry its OWN timer, which overrides this global one — see " +
                "the 'Protection timer' row when editing a limit, a curfew or an install group.",
            "A rule's own timer also survives a settings unlock, so a heavily protected curfew " +
                "cannot be defused through the unlock shortcut."
        ),
        tip = "Start with a short delay (30min) and increase it as you get comfortable. Save a " +
            "huge per-rule timer for the one or two rules you never want to negotiate with."
    )

    private val HELP_APP_LIMITS = HelpContent(
        title = "App Limits",
        emoji = "📱",
        summary = "Set a daily time budget for any app. Once your time is up, the app is blocked for the rest of the day.",
        steps = listOf(
            "Tap the \u002B button to add an app.",
            "Choose the app you want to limit.",
            "Set a daily time allowance (e.g. 30 minutes for Instagram).",
            "The timer counts only while the app is in the foreground.",
            "When your time runs out, the app will be blocked until midnight.",
            "Your timer resets to zero every day at midnight."
        ),
        tip = "Notifications from blocked apps will be temporarily hidden and will reappear when the app is unblocked."
    )

    private val HELP_CURFEW = HelpContent(
        title = "Curfew",
        emoji = "🌙",
        summary = "Block apps during specific hours — perfect for bedtime or study sessions. Curfew overrides your daily limits.",
        steps = listOf(
            "Tap '+ Add curfew rule' to create a new rule.",
            "Choose which app to block.",
            "Set a start time and end time (e.g. 22:00 → 07:00).",
            "Select which days of the week the curfew applies.",
            "During curfew hours, the app is completely blocked — even if you have daily time left.",
            "You can create multiple curfew rules for different apps and schedules."
        ),
        tip = "Great for social media at night — set a curfew from 10pm to 7am on weekdays."
    )

    private val HELP_WHITELIST = HelpContent(
        title = "App Whitelist (Zero-Trust)",
        emoji = "🛡️",
        summary = "Seules les applications inscrites sur cette Whitelist sont autorisées à tourner sur l'appareil. Toute application non approuvée est immédiatement verrouillée et masquée.",
        steps = listOf(
            "Chaque application tierce doit figurer dans la Whitelist pour être accessible.",
            "Pour ajouter une nouvelle app, appuyez sur '+ Demander une app'.",
            "L'application est placée en quarantaine pendant 24h avant d'être débloquée (sas anti-impulsion).",
            "Pendant ces 24 heures, l'application reste complètement inaccessible et masquée.",
            "Retirer une application de la Whitelist est immédiat : elle est verrouillée sur-le-champ."
        ),
        tip = "Ce système Zero-Trust offre une protection totale contre les contournements par ADB ou Play Store : même si une application est installée, elle est instantanément neutralisée tant qu'elle n'est pas approuvée."
    )

    private val HELP_NUCLEAR = HelpContent(
        title = "Nuclear Mode",
        emoji = "☢️",
        summary = "The ultimate focus mode. Block selected apps for a fixed duration with NO way to undo it.",
        steps = listOf(
            "Tap 'Activate Nuclear Mode'.",
            "Select the apps you want to block.",
            "Choose how long (30min, 1h, 2h, 4h, or custom).",
            "Confirm — this action is IRREVERSIBLE.",
            "All selected apps are immediately blocked.",
            "Do Not Disturb is activated — all notifications are silenced.",
            "You CANNOT cancel it early. Just wait it out."
        ),
        tip = "Use this when you really need to focus: exams, deep work, or when you just need a digital detox."
    )

    private val HELP_PARTIAL_ACCESS = HelpContent(
        title = "Partial Access",
        emoji = "🎯",
        summary = "Block specific pages or features inside apps without blocking the entire app.",
        steps = listOf(
            "Tap the + button to select an app you want to restrict.",
            "The app will open with a red overlay 'Capture Mode'.",
            "Navigate to the forbidden section (e.g. YouTube Shorts) and tap the red overlay.",
            "It turns green. Now tap the button you want to be redirected to (e.g. Home tab).",
            "The rule is saved. Whenever you open the forbidden page, you'll be redirected instantly."
        ),
        tip = "Perfect for blocking mindless scrolling feeds while keeping useful parts of the app."
    )

    private fun showFeatureHelpDialog(help: HelpContent) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        // Emoji header
        content.addView(TextView(this).apply {
            text = help.emoji
            textSize = 40f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })

        // Summary
        content.addView(TextView(this).apply {
            text = help.summary
            textSize = 15f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(0, 0, 0, dp(16))
        })

        // Steps header
        content.addView(TextView(this).apply {
            text = "How it works:"
            textSize = 14f
            setTextColor(Color.parseColor("#BB86FC"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        // Numbered steps
        for ((i, step) in help.steps.withIndex()) {
            content.addView(TextView(this).apply {
                val num = "${i + 1}."
                val span = SpannableString("$num $step")
                span.setSpan(ForegroundColorSpan(Color.parseColor("#BB86FC")), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                this.text = span
                textSize = 14f
                setTextColor(Color.parseColor("#CCCCCC"))
                setPadding(dp(4), dp(3), 0, dp(3))
            })
        }

        // Tip box
        val tipBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(Color.parseColor("#1A3A1A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(16)
            layoutParams = lp
        }
        tipBox.addView(TextView(this).apply {
            text = "💡"
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        })
        tipBox.addView(TextView(this).apply {
            text = help.tip
            textSize = 13f
            setTextColor(Color.parseColor("#81C784"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        content.addView(tipBox)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(help.title)
            .setView(content)
            .setPositiveButton("Got it", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun showDeviceOwnerInfoDialog() {
        val isDO = DeviceOwnerHelper.isDeviceOwner(this)
        val title = if (isDO) "Device Owner — Actif" else "Device Owner — Inactif"
        val pkg = packageName
        val message = if (isDO) buildString {
            appendLine("L'app est Device Owner. Protections actives :")
            appendLine()
            appendLine("• setUninstallBlocked → désinstaller via Settings impossible")
            appendLine("• setPackagesSuspended → blocage OS-level (vs HOME spam A11Y)")
            appendLine("• ENABLED_ACCESSIBILITY_SERVICES forcé toutes les 30s")
            appendLine("• Installation depuis sources inconnues bloquée")
            appendLine()
            appendLine("Pour désactiver (urgence) :")
            appendLine("adb shell am broadcast \\")
            appendLine("  -a $pkg.REMOVE_OWNER")
        } else buildString {
            appendLine("L'app n'est PAS Device Owner. Mode actuel : AccessibilityService.")
            appendLine()
            appendLine("Pour activer la protection maximale, exécute (Magisk root requis) :")
            appendLine()
            appendLine("adb shell \"su -c 'dpm set-device-owner \\")
            appendLine("  $pkg/.AdminReceiver'\"")
            appendLine()
            appendLine("Prérequis : aucun compte Google / Samsung sur l'appareil.")
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFullWalkthroughDialog() {
        val allHelp = listOf(HELP_DELAY, HELP_APP_LIMITS, HELP_CURFEW, HELP_NUCLEAR)
        var currentPage = 0

        fun buildPage(help: HelpContent): LinearLayout {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }

            // Emoji + title
            page.addView(TextView(this).apply {
                text = "${help.emoji}  ${help.title}"
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(16))
            })

            // Summary
            page.addView(TextView(this).apply {
                text = help.summary
                textSize = 15f
                setTextColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 0, 0, dp(12))
            })

            // Steps
            for ((i, step) in help.steps.withIndex()) {
                page.addView(TextView(this).apply {
                    val num = "${i + 1}."
                    val span = SpannableString("$num $step")
                    span.setSpan(ForegroundColorSpan(Color.parseColor("#BB86FC")), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(StyleSpan(Typeface.BOLD), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    this.text = span
                    textSize = 14f
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setPadding(dp(4), dp(3), 0, dp(3))
                })
            }

            // Tip
            val tipBox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundedBackground(Color.parseColor("#1A3A1A"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(12)
                layoutParams = lp
            }
            tipBox.addView(TextView(this).apply {
                text = "💡"
                textSize = 16f
                setPadding(0, 0, dp(8), 0)
            })
            tipBox.addView(TextView(this).apply {
                text = help.tip
                textSize = 13f
                setTextColor(Color.parseColor("#81C784"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            page.addView(tipBox)

            // Page indicator
            page.addView(TextView(this).apply {
                val pageNum = allHelp.indexOf(help) + 1
                text = "$pageNum / ${allHelp.size}"
                textSize = 13f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(4))
            })

            return page
        }

        val container = FrameLayout(this)
        container.addView(buildPage(allHelp[0]))

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("How Custos Works")
            .setView(container)
            .setPositiveButton("Next") { _, _ -> }
            .setNegativeButton("Close", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()

        // Override the positive button to cycle pages instead of dismissing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            currentPage++
            if (currentPage >= allHelp.size) {
                dialog.dismiss()
            } else {
                container.removeAllViews()
                container.addView(buildPage(allHelp[currentPage]))
                if (currentPage == allHelp.size - 1) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Done ✓"
                }
            }
        }
    }

    private fun separator(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            }
            setBackgroundColor(Color.parseColor("#333333"))
        }
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            if (color == Color.WHITE) {
                setStroke((2f * resources.displayMetrics.density).toInt(), Color.BLACK)
            }
            cornerRadius = 12f * resources.displayMetrics.density
        }
    }

    // ──────────────────────────────────────
    //  Dialog Styling (fix black-on-black)
    // ──────────────────────────────────────

    /**
     * Force all NumberPicker wheel text to WHITE — including the scrolling
     * items above/below the selected value that Android paints via
     * mSelectorWheelPaint. We also attach a scroll listener so colors
     * are re-applied after every fling/scroll animation.
     */
    private fun forceNumberPickerWhite(picker: NumberPicker) {
        // 1. API 29+ has a public setTextColor method
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.textColor = Color.WHITE
        }
        // 2. Set the private mTextColor int field (source of truth for the paint on AOSP)
        try {
            val f = NumberPicker::class.java.getDeclaredField("mTextColor")
            f.isAccessible = true
            f.setInt(picker, Color.WHITE)
        } catch (_: Exception) {}
        // 3. Set mSelectorWheelPaint directly (the Paint used to draw wheel items)
        try {
            val f = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            f.isAccessible = true
            (f.get(picker) as? android.graphics.Paint)?.color = Color.WHITE
        } catch (_: Exception) {}
        // 4. Set mInputText (the EditText showing the selected value)
        try {
            val f = NumberPicker::class.java.getDeclaredField("mInputText")
            f.isAccessible = true
            (f.get(picker) as? TextView)?.setTextColor(Color.WHITE)
        } catch (_: Exception) {}
        // 5. Style all child views directly
        for (i in 0 until picker.childCount) {
            when (val child = picker.getChildAt(i)) {
                is EditText -> {
                    child.setTextColor(Color.WHITE)
                    child.setHintTextColor(Color.parseColor("#888888"))
                }
                is TextView -> child.setTextColor(Color.WHITE)
            }
        }
        picker.invalidate()

        // 6. Attach a scroll listener (once) that re-applies after every scroll
        if (picker.tag != "np_styled") {
            picker.tag = "np_styled"
            picker.setOnScrollListener { _, scrollState ->
                if (scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE) {
                    picker.post { forceNumberPickerWhite(picker) }
                }
            }
        }
    }

    /**
     * Recursively set text color to white on all views inside a dialog.
     */
    private fun styleViewForDarkTheme(view: View) {
        when (view) {
            is NumberPicker -> forceNumberPickerWhite(view)
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    styleViewForDarkTheme(view.getChildAt(i))
                }
            }
            is EditText -> {
                view.setTextColor(Color.WHITE)
                view.setHintTextColor(Color.parseColor("#888888"))
            }
            is TextView -> {
                view.setTextColor(Color.WHITE)
            }
        }
    }

    private fun styleDialogForDarkTheme(dialog: AlertDialog) {
        dialog.setOnShowListener {
            // Style the message text
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.parseColor("#DDDDDD"))
            // Style the title
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId != 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            // Style buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#BB86FC"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#BB86FC"))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#FF5252"))
            // Recursively style all views including NumberPickers
            dialog.window?.decorView?.let { root ->
                styleViewForDarkTheme(root)
                // Re-apply after layout pass (some pickers render wheel text late)
                root.post { styleViewForDarkTheme(root) }
            }
        }
    }

    // ──────────────────────────────────────
    //  App List Helpers (icons in dialogs)
    // ──────────────────────────────────────

    private data class AppInfo(
        val packageName: String,
        val label: String
    )

    /**
     * Lazily-loaded, cached app icons. Decoding every installed app's icon up front on the
     * UI thread was the main cause of the slow app picker (much worse on Samsung, which ships
     * far more preinstalled apps than a Pixel). Icons are now resolved only for the rows the
     * ListView actually renders, and cached so scrolling stays smooth.
     */
    private val iconCache = HashMap<String, Drawable>()
    private fun appIcon(pkg: String): Drawable = iconCache.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) }
        catch (_: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
    }

    private fun getInstalledLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                val pkg = app.packageName
                pkg != packageName &&
                    !pkg.startsWith("com.android.providers.") &&
                    pm.getLaunchIntentForPackage(pkg) != null
            }
            .map { app ->
                AppInfo(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Build a custom multi-select or single-select ListView with app icons.
     */
    private fun buildAppCheckListView(
        apps: List<AppInfo>,
        checkedSet: MutableSet<String>,
        preChecked: Set<String> = emptySet()
    ): ListView {
        checkedSet.clear()
        checkedSet.addAll(preChecked.filter { pkg -> apps.any { it.packageName == pkg } })

        val listView = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            divider = null
            dividerHeight = 0
        }

        val adapter = object : BaseAdapter() {
            private val checked = BooleanArray(apps.size) { apps[it].packageName in preChecked }

            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                row.removeAllViews()

                val icon = ImageView(this@MainActivity).apply {
                    val sz = dp(32)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = dp(12)
                    }
                    setImageDrawable(appIcon(app.packageName))
                }

                val label = TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val cb = CheckBox(this@MainActivity).apply {
                    isChecked = checked[position]
                    setOnCheckedChangeListener(null)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BB86FC"))
                }

                row.addView(icon)
                row.addView(label)
                row.addView(cb)

                row.setOnClickListener {
                    checked[position] = !checked[position]
                    cb.isChecked = checked[position]
                    if (checked[position]) {
                        checkedSet.add(app.packageName)
                    } else {
                        checkedSet.remove(app.packageName)
                    }
                }
                cb.setOnCheckedChangeListener { _, isChecked ->
                    checked[position] = isChecked
                    if (isChecked) checkedSet.add(app.packageName) else checkedSet.remove(app.packageName)
                }

                return row
            }
        }
        listView.adapter = adapter
        return listView
    }

    /**
     * Build a custom single-select ListView with app icons.
     */
    private fun buildAppRadioListView(
        apps: List<AppInfo>,
        onSelected: (Int) -> Unit
    ): ListView {
        var selectedPosition = 0

        val listView = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            divider = null
            dividerHeight = 0
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                row.removeAllViews()

                val icon = ImageView(this@MainActivity).apply {
                    val sz = dp(32)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = dp(12)
                    }
                    setImageDrawable(appIcon(app.packageName))
                }

                val label = TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val rb = RadioButton(this@MainActivity).apply {
                    isChecked = position == selectedPosition
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BB86FC"))
                }

                row.addView(icon)
                row.addView(label)
                row.addView(rb)

                row.setOnClickListener {
                    selectedPosition = position
                    onSelected(position)
                    notifyDataSetChanged()
                }
                rb.setOnClickListener {
                    selectedPosition = position
                    onSelected(position)
                    notifyDataSetChanged()
                }

                return row
            }
        }
        listView.adapter = adapter
        return listView
    }

    // ==========================================
    //  PARTIAL ACCESS (SCREEN RULES)
    // ==========================================
    private fun refreshPartialAccessUI() {
        if (!::partialAccessContainer.isInitialized) return
        partialAccessContainer.removeAllViews()
        val rules = ScreenRuleManager.load(this)
        if (rules.isEmpty()) {
            partialAccessCircle.setSummaryText("0 rules")
            partialAccessContainer.addView(TextView(this).apply {
                text = "No partial access rules configured."
                setTextColor(Color.GRAY)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        } else if (rules.size == 1) {
            partialAccessCircle.setSummaryText(rules[0].name)
        } else {
            partialAccessCircle.setSummaryText("${rules.size} rules")
        }

        for (rule in rules) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(rule.packageName, 0)).toString()
            } catch (_: Exception) {
                rule.packageName
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(Color.parseColor("#F5F5F5"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }

                addView(TextView(this@MainActivity).apply {
                    text = rule.name
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    val health = "✓ ${rule.successes}   ✗ ${rule.failures}"
                    text = "$label · ${rule.blockedIds.size} marker(s) · $health"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                })

                val pendingMs = ScreenRuleManager.pendingRemovalRemainingMs(this@MainActivity, rule.name)
                addView(TextView(this@MainActivity).apply {
                    text = if (ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) {
                        "🔒 Permanent sur ce build (retrait ADB uniquement)"
                    } else {
                        "🛡️ Protection timer: ${protectionTimerLabel(rule.protectionDelaySec)}"
                    }
                    textSize = 12f
                    setTextColor(if (ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) Color.parseColor("#7B1FA2") else Color.parseColor("#666666"))
                    setPadding(0, dp(4), 0, 0)
                })

                if (!rule.blockedHours.isNullOrBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = "⏰ Horaires actifs: ${rule.blockedHours}"
                        textSize = 12f
                        setTextColor(Color.parseColor("#666666"))
                        setPadding(0, dp(2), 0, 0)
                    })
                }

                when {
                    ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR -> {
                        // Permanent on this build: no removal button via UI
                    }
                    pendingMs != null -> {
                        addView(TextView(this@MainActivity).apply {
                            text = "Retrait dans ${formatLongDuration((pendingMs / 1000).toInt())}"
                            textSize = 12f
                            setTextColor(Color.RED)
                            setPadding(0, dp(6), 0, dp(4))
                        })
                        addView(Button(this@MainActivity).apply {
                            text = "Cancel removal"
                            setTextColor(Color.WHITE)
                            background = roundedBackground(Color.BLACK)
                            setOnClickListener {
                                ScreenRuleManager.cancelRemoval(this@MainActivity, rule.name)
                                refreshPartialAccessUI()
                            }
                        })
                    }
                    else -> {
                        val actionsRow = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(8), 0, 0)
                        }
                        actionsRow.addView(Button(this@MainActivity).apply {
                            text = "Timer"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            background = roundedBackground(Color.parseColor("#333333"))
                            setOnClickListener {
                                showProtectionTimerDialog(rule.protectionDelaySec) { picked ->
                                    ScreenRuleManager.updateProtectionDelay(this@MainActivity, rule.name, picked)
                                    refreshPartialAccessUI()
                                }
                            }
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(8)
                            }
                        })
                        actionsRow.addView(Button(this@MainActivity).apply {
                            text = "Remove"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            background = roundedBackground(Color.BLACK)
                            setOnClickListener { onRemoveScreenRule(rule) }
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(actionsRow)
                    }
                }
            }
            partialAccessContainer.addView(card)
        }
    }

    private fun onRemoveScreenRule(rule: ScreenRuleManager.ScreenRule) {
        when (val outcome = ScreenRuleManager.requestRemoval(this, rule.name)) {
            is ScreenRuleManager.RemovalOutcome.Permanent -> {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("Permanent rule")
                    .setMessage(
                        "On this build a partial-access rule can no longer be removed from the " +
                            "phone once created — only via adb from a PC. That is deliberate."
                    )
                    .setPositiveButton("Got it", null)
                    .show()
            }
            is ScreenRuleManager.RemovalOutcome.Immediate ->
                Toast.makeText(this, "Rule removed.", Toast.LENGTH_SHORT).show()
            is ScreenRuleManager.RemovalOutcome.Deferred -> {
                val scope = if (outcome.fromRuleTimer) "rule delay" else "general delay"
                Toast.makeText(
                    this,
                    "Removal scheduled in ${formatLongDuration(outcome.seconds)} ($scope).",
                    Toast.LENGTH_LONG
                ).show()
            }
            is ScreenRuleManager.RemovalOutcome.AlreadyPending ->
                Toast.makeText(
                    this,
                    "Removal already pending (${formatLongDuration((outcome.remainingMs / 1000).toInt())}).",
                    Toast.LENGTH_LONG
                ).show()
            is ScreenRuleManager.RemovalOutcome.NotFound ->
                Toast.makeText(this, "Rule not found.", Toast.LENGTH_SHORT).show()
        }
        refreshPartialAccessUI()
    }

    private fun startScreenRuleLearning() {
        if (AppWatcherService.serviceInstance == null) {
            Toast.makeText(
                this,
                "First enable Custos's accessibility service in Settings.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val apps = getInstalledLaunchableApps()
        val selected = mutableSetOf<String>()
        val listView = buildAppCheckListView(apps, selected)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Which app?")
            .setView(listView)
            .setPositiveButton("Next") { _, _ ->
                val pkg = selected.firstOrNull()
                if (pkg == null) {
                    Toast.makeText(this, "Pick an app.", Toast.LENGTH_SHORT).show()
                } else {
                    askScreenRuleDetails(pkg, apps.firstOrNull { it.packageName == pkg }?.label ?: pkg)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun askScreenRuleDetails(pkg: String, label: String) {
        val existingRules = ScreenRuleManager.load(this)
        val existingForPkg = existingRules.filter { it.packageName == pkg }

        // Find next unused rule index for this app
        var ruleIndex = existingForPkg.size + 1
        var defaultName = "$label — part $ruleIndex"
        while (existingRules.any { it.name.equals(defaultName, ignoreCase = true) }) {
            ruleIndex++
            defaultName = "$label — part $ruleIndex"
        }

        val nameInput = EditText(this).apply {
            setText(defaultName)
            hint = "e.g. $label — Stories, $label — Actus..."
            setTextColor(Color.BLACK)
            selectAll()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Rule name"
                setTextColor(Color.parseColor("#666666"))
                textSize = 12f
            })
            addView(nameInput)
            addView(TextView(this@MainActivity).apply {
                text = if (existingForPkg.isNotEmpty()) {
                    "💡 You already have ${existingForPkg.size} rule(s) for $label. You can create multiple rules per app (e.g. to block different tabs or screens)."
                } else {
                    "💡 You can create multiple rules per app (e.g. to block different tabs or screens). Give each rule a distinct name."
                }
                setTextColor(Color.parseColor("#888888"))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(6))
            })
        }

        var pickedTimer: Int? = if (ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) null else 360 * 60

        if (!ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) {
            val timerRow = buildProtectionTimerRow(pickedTimer) { picked ->
                pickedTimer = picked
            }
            layout.addView(timerRow)
        } else {
            layout.addView(TextView(this).apply {
                text = "🔒 Permanent sur le flavor me — le retrait se fait uniquement par commande adb."
                setTextColor(Color.parseColor("#D32F2F"))
                textSize = 12f
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        fun launchCapture(finalName: String, delaySec: Int) {
            val svc = AppWatcherService.serviceInstance
            if (svc == null) {
                Toast.makeText(this@MainActivity, "Accessibility service unavailable.", Toast.LENGTH_LONG).show()
                return
            }
            ScreenLearnSession.start(svc, pkg, finalName, delaySec)
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("New rule")
            .setView(layout)
            .setPositiveButton("Start capture") { _, _ ->
                val chosenName = nameInput.text.toString().trim().ifBlank { defaultName }
                val delaySec = if (ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) 0
                    else (pickedTimer ?: 0)

                val collision = existingRules.firstOrNull { it.name.equals(chosenName, ignoreCase = true) }
                if (collision != null) {
                    var suffix = 2
                    var autoUnique = "$chosenName ($suffix)"
                    while (existingRules.any { it.name.equals(autoUnique, ignoreCase = true) }) {
                        suffix++
                        autoUnique = "$chosenName ($suffix)"
                    }
                    val conflictBuilder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog)
                        .setTitle("Rule already exists")
                        .setMessage("A rule named '$chosenName' already exists. Do you want to keep both rules?")
                        .setPositiveButton("Keep both ($autoUnique)") { _, _ ->
                            launchCapture(autoUnique, delaySec)
                        }
                        .setNeutralButton("Cancel", null)

                    if (!ScreenRuleManager.PERMANENT_ON_THIS_FLAVOR) {
                        conflictBuilder.setNegativeButton("Replace existing") { _, _ ->
                            launchCapture(chosenName, delaySec)
                        }
                    }
                    val conflictDialog = conflictBuilder.create()
                    styleDialogForDarkTheme(conflictDialog)
                    conflictDialog.show()
                } else {
                    launchCapture(chosenName, delaySec)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }
}
