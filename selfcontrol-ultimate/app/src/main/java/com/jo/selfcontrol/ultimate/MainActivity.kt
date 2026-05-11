package com.jo.selfcontrol.ultimate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var permissionsContainer: LinearLayout
    private lateinit var dashboardContainer: LinearLayout
    private lateinit var delayContainer: LinearLayout
    private lateinit var nuclearStatusContainer: LinearLayout
    private lateinit var nuclearSetupContainer: LinearLayout
    private lateinit var nuclearCountdownText: TextView
    private lateinit var nuclearBlockedListText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var deviceOwnerStatusText: TextView
    private lateinit var periodBlocksContainer: LinearLayout

    private val selectedNuclearApps = mutableSetOf<String>()
    private var selectedDurationMs = 30 * 60 * 1000L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshPermissions()
            refreshDashboard()
            refreshDelayUI()
            refreshNuclearStatus()
            refreshPeriodBlocksUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity started")
        LimitService.start(this)
        setContentView(buildUI())
        handler.postDelayed(refreshRunnable, 500)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }

    // ──────────────────────────────────────
    //  Build UI
    // ──────────────────────────────────────

    private fun buildUI(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // Header with title + help button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        headerRow.addView(TextView(this).apply {
            text = "SelfControl Free"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(Button(this).apply {
            text = "❓"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#2A2A2A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showFullWalkthroughDialog() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        mainLayout.addView(headerRow)

        serviceStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        mainLayout.addView(serviceStatusText)

        deviceOwnerStatusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showDeviceOwnerInfoDialog() }
        }
        mainLayout.addView(deviceOwnerStatusText)

        // Permissions Section
        permissionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        mainLayout.addView(permissionsContainer)

        // Settings Unlock Section
        mainLayout.addView(sectionTitleWithHelp("Security & Delay", HELP_DELAY))
        delayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(delayContainer)

        mainLayout.addView(separator())

        // App Limits Section
        val limitsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        limitsHeader.addView(sectionTitleWithHelp("App Limits", HELP_APP_LIMITS))
        
        limitsHeader.addView(Button(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#BB86FC"))
            setOnClickListener { showAddAppDialog() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(16)
            }
        })
        mainLayout.addView(limitsHeader)

        dashboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(dashboardContainer)

        mainLayout.addView(separator())

        mainLayout.addView(sectionTitleWithHelp("Curfew", HELP_CURFEW))
        mainLayout.addView(TextView(this).apply {
            text = "Block selected apps during sleeping hours. Curfew wins over remaining daily time."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val curfewHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        curfewHeader.addView(Button(this).apply {
            text = "+ Add curfew rule"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#333333"))
            setOnClickListener { showAddCurfewRuleDialog() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        mainLayout.addView(curfewHeader)
        periodBlocksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(periodBlocksContainer)

        mainLayout.addView(separator())

        // Nuclear Mode Section
        mainLayout.addView(sectionTitleWithHelp("Nuclear Mode", HELP_NUCLEAR))

        nuclearStatusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        nuclearCountdownText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.parseColor("#FF5252"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        nuclearStatusContainer.addView(nuclearCountdownText)
        nuclearBlockedListText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        nuclearStatusContainer.addView(nuclearBlockedListText)
        mainLayout.addView(nuclearStatusContainer)

        nuclearSetupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        val nuclearButton = Button(this).apply {
            text = "Activate Nuclear Mode"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#D32F2F"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { startNuclearActivationFlow() }
        }
        nuclearSetupContainer.addView(nuclearButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val managePresetsButton = Button(this).apply {
            text = "Manage Presets"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.parseColor("#424242"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { showManagePresetsDialog() }
        }
        nuclearSetupContainer.addView(managePresetsButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        mainLayout.addView(nuclearSetupContainer)

        root.addView(mainLayout)
        return root
    }

    // ──────────────────────────────────────
    //  Permissions Refresh
    // ──────────────────────────────────────

    private fun refreshPermissions() {
        val needsUsage = !PermissionHelper.hasUsageStatsPermission(this)
        val needsA11y = !PermissionHelper.hasAccessibilityPermission(this)
        val needsDnd = !PermissionHelper.hasNotificationPolicyPermission(this)
        val needsNotifListener = !PermissionHelper.hasNotificationListenerPermission(this)
        val needsPostNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (!needsUsage && !needsA11y && !needsDnd && !needsNotifListener && !needsPostNotif) {
            permissionsContainer.visibility = View.GONE
            return
        }

        permissionsContainer.visibility = View.VISIBLE
        permissionsContainer.removeAllViews()
        permissionsContainer.addView(TextView(this).apply {
            text = "Required Permissions Missing!"
            setTextColor(Color.parseColor("#FF5252"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })

        if (needsUsage) {
            permissionsContainer.addView(Button(this).apply {
                text = "Grant Usage Stats Access"
                setOnClickListener { PermissionHelper.requestUsageStatsPermission(this@MainActivity) }
            })
        }
        if (needsA11y) {
            permissionsContainer.addView(Button(this).apply {
                text = "Grant Accessibility Service"
                setOnClickListener { PermissionHelper.requestAccessibilityPermission(this@MainActivity) }
            })
        }
        if (needsDnd) {
            permissionsContainer.addView(Button(this).apply {
                text = "Enable DND priority mode"
                background = roundedBackground(Color.parseColor("#2F3BFF"))
                setTextColor(Color.WHITE)
                setOnClickListener { PermissionHelper.requestNotificationPolicyPermission(this@MainActivity) }
            })
        }

        if (needsNotifListener) {
            permissionsContainer.addView(Button(this).apply {
                text = "Grant Notification Access (mute blocked apps)"
                background = roundedBackground(Color.parseColor("#2F3BFF"))
                setTextColor(Color.WHITE)
                setOnClickListener { PermissionHelper.requestNotificationListenerPermission(this@MainActivity) }
            })
        }

        if (needsPostNotif) {
            permissionsContainer.addView(Button(this).apply {
                text = "Allow Notifications"
                background = roundedBackground(Color.parseColor("#2F3BFF"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                }
            })
        }
    }

    // ──────────────────────────────────────
    //  Delay Configuration & Unlock Settings
    // ──────────────────────────────────────

    private fun refreshDelayUI() {
        delayContainer.removeAllViews()
        val delayState = DelayManager.loadState(this)
        val effectiveDelay = DelayManager.getCurrentEffectiveDelaySeconds(this)
        
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
                    background = roundedBackground(Color.parseColor("#222222"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    addView(TextView(this@MainActivity).apply {
                        text = "$days | $start -> $end | ${formatTime(rule.delaySeconds)}"
                        setTextColor(Color.WHITE)
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
                    setTextColor(Color.parseColor("#FFCA28"))
                    textSize = 14f
                    setPadding(0, dp(16), 0, dp(8))
                })
                for (update in pending) {
                    val remaining = ((update.executeAt - now) / 1000).toInt()
                    delayContainer.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = roundedBackground(Color.parseColor("#1A1A2E"))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }

                        addView(TextView(this@MainActivity).apply {
                            text = "📝 Pending Change — ${formatTime(remaining)}"
                            setTextColor(Color.parseColor("#FFCA28"))
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
                                    setTextColor(Color.parseColor("#DDDDDD"))
                                    textSize = 13f
                                    setPadding(0, dp(6), 0, dp(6))
                                })
                            } else {
                                addView(TextView(this@MainActivity).apply {
                                    text = update.description
                                    setTextColor(Color.WHITE)
                                    textSize = 13f
                                    setPadding(0, dp(4), 0, dp(4))
                                })
                            }
                        } else {
                            addView(TextView(this@MainActivity).apply {
                                text = update.description
                                setTextColor(Color.WHITE)
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

        // Settings Unlock UI
        val now = System.currentTimeMillis()
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
        
        var mins = existingLimit?.maxMinutesPerDay ?: 30

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 1440
            value = mins
            wrapSelectorWheel = false
        }
        layout.addView(picker)
        layout.addView(TextView(this).apply {
            text = " mins/day"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Set Limit for ${getAppName(pkg)}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newMins = picker.value
                val newLimits = config.limits.filter { it.packageName != pkg }.toMutableList()
                newLimits.add(ConfigManager.AppLimit(
                    packageName = pkg,
                    maxMinutesPerDay = newMins,
                    maxSecondsPerDay = newMins * 60,
                    allowedDays = listOf(0,1,2,3,4,5,6),
                    allowedHoursStart = 0,
                    allowedHoursEnd = 24 * 60,
                    allDay = true
                ))
                val newConfig = ConfigManager.Config(newLimits, config.periodBlocks)
                saveConfigWithDelay(newConfig)
            }
            .setNegativeButton("Cancel", null)

        if (existingLimit != null) {
            builder.setNeutralButton("Delete timer") { _, _ ->
                val newLimits = config.limits.filter { it.packageName != pkg }
                val newConfig = ConfigManager.Config(newLimits, config.periodBlocks)
                saveConfigWithDelay(newConfig)
            }
        }

        val dialog = builder.create()
        styleDialogForDarkTheme(dialog)
        dialog.show()
    }

    private fun saveConfigWithDelay(newConfig: ConfigManager.Config) {
        val old = loadEditableConfig()
        val delayState = DelayManager.loadState(this)
        val mustDefer = delayState.globalDelaySeconds > 0 &&
            !DelayManager.isSettingsUnlocked(this) &&
            (ConfigManager.shouldDeferLimitsChange(old, newConfig.limits) ||
                ConfigManager.shouldDeferPeriodBlocksChange(old.periodBlocks, newConfig.periodBlocks))

        val jsonString = ConfigManager.configToJsonString(newConfig)
        if (!mustDefer) {
            ConfigManager.saveConfig(this, newConfig)
            Toast.makeText(this, "Config updated.", Toast.LENGTH_SHORT).show()
        } else {
            val description = describeConfigChange(old, newConfig)
            DelayManager.requestConfigUpdate(this, jsonString, description)
            val effectiveDelay = DelayManager.getCurrentEffectiveDelaySeconds(this)
            Toast.makeText(
                this,
                "Change queued. Waiting ${effectiveDelay}s (effective protection delay).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshPeriodBlocksUI() {
        periodBlocksContainer.removeAllViews()
        val cfg = ConfigManager.loadConfig(this)
        if (cfg.periodBlocks.isEmpty()) {
            periodBlocksContainer.addView(TextView(this).apply {
                text = "No curfew rules."
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
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
            background = roundedBackground(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            val muteLabel = if (rule.muteNotifications) "Notifications: muted" else "Notifications: on"
            addView(TextView(this@MainActivity).apply {
                text = "$days\n$start -> $end\n$names\n$muteLabel"
                textSize = 14f
                setTextColor(Color.WHITE)
            })
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
                background = roundedBackground(Color.parseColor("#BB86FC"))
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
                    saveConfigWithDelay(ConfigManager.Config(cur.limits, newList))
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
                    existingMute = existingRule.muteNotifications
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
        existingMute: Boolean = false
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
                    muteNotifications = muteNotifications
                )
                val newList = if (editIndex >= 0) {
                    cur.periodBlocks.toMutableList().also { it[editIndex] = newRule }
                } else {
                    cur.periodBlocks + newRule
                }
                saveConfigWithDelay(ConfigManager.Config(cur.limits, newList))
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
                old == null && new != null ->
                    limitChanges.add("  \u2022 [NEW] $name: ${new.maxMinutesPerDay}m/day")
                old != null && new == null ->
                    limitChanges.add("  \u2022 [REMOVED] $name")
                old != null && new != null && old.maxMinutesPerDay != new.maxMinutesPerDay ->
                    limitChanges.add("  \u2022 $name: ${old.maxMinutesPerDay}m/day \u2192 ${new.maxMinutesPerDay}m/day")
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

        if (limits.isEmpty()) {
            dashboardContainer.removeAllViews()
            dashboardContainer.addView(TextView(this).apply {
                text = "No limits configured."
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            return
        }

        if (dashboardContainer.childCount != limits.size) {
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

            progressBar?.progress = if (maxSeconds > 0) ((usedSeconds * 100) / maxSeconds).coerceAtMost(100) else 0
            usageText?.text = "${formatTime(usedSeconds)} / ${formatTime(maxSeconds)}"

            if (isBlocked) {
                statusText?.text = "BLOCKED"
                statusText?.setTextColor(Color.parseColor("#FF5252"))
                statusText?.visibility = View.VISIBLE
            } else {
                statusText?.visibility = View.GONE
            }
        }
    }

    private fun buildAppRow(pkg: String, limit: ConfigManager.AppLimit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            tag = pkg
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = roundedBackground(Color.parseColor("#222222"))
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
            setTextColor(Color.WHITE)
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
            setTextColor(Color.parseColor("#AAAAAA"))
        })

        row.addView(infoCol)
        return row
    }

    // ──────────────────────────────────────
    //  Nuclear Mode
    // ──────────────────────────────────────

    private fun refreshNuclearStatus() {
        val state = LimitService.getNuclearState()
        if (state != null && state.active && !NuclearManager.isExpired(state)) {
            nuclearStatusContainer.visibility = View.VISIBLE
            nuclearSetupContainer.visibility = View.GONE

            val remaining = state.endTimestamp - System.currentTimeMillis()
            val remainSec = (remaining / 1000).toInt().coerceAtLeast(0)
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
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.')
        }
    }

    private fun getAppIcon(pkg: String): Drawable {
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            getDrawable(android.R.drawable.sym_def_app_icon)!!
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
            "Think of it like a cooling-off period for your digital willpower."
        ),
        tip = "Start with a short delay (30min) and increase it as you get comfortable."
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
            .setTitle("How SelfControl Works")
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
        val label: String,
        val icon: Drawable
    )

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
                    label = pm.getApplicationLabel(app).toString(),
                    icon = try { pm.getApplicationIcon(app) }
                           catch (_: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
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
                    setImageDrawable(app.icon)
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
                    setImageDrawable(app.icon)
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
}
