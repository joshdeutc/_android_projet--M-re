package com.jo.selfcontrol.ultimate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.usage.UsageEvents
import java.util.Calendar

/**
 * Foreground Service.
 * Central hub for monitoring usage and enforcing limits.
 */
class LimitService : Service() {

    companion object {
        private const val TAG = "SelfControl.Service"
        private const val CHANNEL_ID = "selfcontrol_monitor"
        private const val CHOICE_CHANNEL_ID = "selfcontrol_choices"
        private const val NOTIFICATION_ID = 1
        private const val ENFORCE_INTERVAL_MS = 1_000L   // 1 second

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: LimitService? = null

        fun getUsageData(): Map<String, Int> = instance?.usageToday?.toMap() ?: emptyMap()
        fun getLimits(): Map<String, ConfigManager.AppLimit> = instance?.limitsByPackage?.toMap() ?: emptyMap()
        fun getBlockedApps(): Set<String> = instance?.suspendedApps?.toSet() ?: emptySet()
        fun getNuclearState(): NuclearManager.NuclearState? = instance?.nuclearState

        fun startNuclearMode(
            packages: List<String>,
            durationMs: Long
        ) {
            instance?.activateNuclearMode(packages, durationMs)
        }

        fun requestCancelNuclearMode() {
            instance?.requestCancelNuclearModeInternal()
        }

        fun cancelPendingNuclearCancel() {
            instance?.cancelPendingNuclearCancelInternal()
        }

        /** Debug-only: force a quota block for automated tests. */
        fun forceBlockForTest(packageName: String) {
            val inst = instance ?: return
            // Set usage to 1s — enough to exceed a 0s limit, but low enough
            // that raising the limit will correctly unblock the app.
            inst.usageToday[packageName] = 1
            inst.blockApp(packageName, "quota")
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, LimitService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FGS: ${e.message}")
            }
        }

        fun handleBlockedNotificationChoice(context: Context, packageName: String, mute: Boolean) {
            BlockedNotificationManager.setMuteWhenBlockedPreference(context, packageName, mute)
            instance?.onBlockedNotificationChoiceApplied(packageName, mute)
        }
    }

    private lateinit var config: ConfigManager.Config
    private val limitsByPackage = mutableMapOf<String, ConfigManager.AppLimit>()
    private val periodBlockRules = mutableListOf<ConfigManager.PeriodBlockRule>()

    // Usage tracking per day in seconds
    private val usageToday = mutableMapOf<String, Int>()
    private var trackingDay = -1

    // Simulated "suspended" apps list representing apps blocked by HOME spam
    private val suspendedApps = mutableSetOf<String>()
    private val promptedBlockedNotificationChoice = mutableSetOf<String>()
    private val currentlyMutedBySelfControl = mutableSetOf<String>()
    private val curfewMutedApps = mutableSetOf<String>() // apps muted specifically by curfew rules

    @Volatile
    private var nuclearState: NuclearManager.NuclearState? = null

    private var lastSystemQueryTime = 0L
    private var lastEnforceTime = 0L
    private var lastA11YReEnableTime = 0L
    private val SYSTEM_SYNC_INTERVAL_MS = 3_000L
    private val A11Y_REENABLE_INTERVAL_MS = 30_000L

    @Volatile
    private var startupComplete = false

    private var configLastModified = 0L

    @Volatile
    private var nuclearDndApplied: Boolean = false

    private val checkLimitsAndDelayHandler = Handler(Looper.getMainLooper())
    private val enforceRunnable = object : Runnable {
        override fun run() {
            // Regularly check if pending delay actions should be applied
            DelayManager.applyPendingConfigsIfReady(this@LimitService)
            DelayManager.applyPendingDelayIfReady(this@LimitService)
            checkConfigReload()
            checkA11YReEnable()

            enforceLimit()
            checkLimitsAndDelayHandler.postDelayed(this, ENFORCE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 LimitService onCreate")
        instance = this

        // Re-apply Device Owner policies on every service start. Idempotent + heals any drift.
        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            DeviceOwnerHelper.applyInitialPolicies(this)
            Log.i(TAG, "🔐 Device Owner policies applied")
            // suspendedApps is in-memory only; on service restart, lift any leftover
            // OS-level suspensions so apps don't stay stuck unblockable.
            // enforceLimit() will re-suspend any app that's still over quota.
            DeviceOwnerHelper.clearAllStuckSuspensions(this)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        isRunning = true
        WatchdogReceiver.schedule(this)
        currentlyMutedBySelfControl.clear()
        currentlyMutedBySelfControl.addAll(BlockedNotificationManager.getCurrentlyMutedBySelfControl(this))
        // Restore muted packages into the NotificationListener in-memory set
        SelfControlNotificationListener.mutedPackages.addAll(currentlyMutedBySelfControl)

        loadNuclearState()

        config = ConfigManager.loadConfig(this)
        configLastModified = ConfigManager.getConfigLastModified(this)
        limitsByPackage.clear()
        periodBlockRules.clear()
        for (limit in config.limits) {
            limitsByPackage[limit.packageName] = limit
            Log.i(TAG, "   ${limit.packageName} → ${limit.maxSecondsPerDay}s/day")
        }
        periodBlockRules.addAll(config.periodBlocks)
        if (config.limits.isEmpty()) {
            Log.i(TAG, "📋 No limits configured (empty config)")
        } else {
            Log.i(TAG, "📋 Config loaded : ${config.limits.size} rule(s)")
        }
        if (periodBlockRules.isNotEmpty()) {
            Log.i(TAG, "🌙 Period blocks: ${periodBlockRules.size} rule(s)")
        }

        updateNotification("Active — ${config.limits.size} app(s) monitored")

        if (limitsByPackage.isNotEmpty()) {
            Thread {
                Thread.sleep(1000)
                Handler(Looper.getMainLooper()).post {
                    trackingDay = Calendar.getInstance().apply {
                        add(Calendar.HOUR_OF_DAY, -2)
                    }.get(Calendar.DAY_OF_YEAR)

                    loadTodayUsageFromSystem()
                    lastEnforceTime = System.currentTimeMillis()
                    startupComplete = true
                    Log.i(TAG, "✅ Startup complete — enforcing immediately")
                    enforceLimit()
                }
            }.start()
        } else {
            startupComplete = true
        }

        checkLimitsAndDelayHandler.postDelayed(enforceRunnable, ENFORCE_INTERVAL_MS)
        Log.i(TAG, "⏱️ Enforcement started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🔄 LimitService onStartCommand")
        return START_STICKY
    }

    private fun dayStartMillis(): Long {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 2) {
            cal.add(Calendar.DATE, -1)
        }
        cal.apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun syncUsageFromSystem(pkg: String) {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val stats = usm.queryAndAggregateUsageStats(dayStartMillis(), System.currentTimeMillis())
            val usageStats = stats[pkg] ?: return
            val systemSeconds = (usageStats.totalTimeInForeground / 1000).toInt()
            if (systemSeconds > 0) {
                val localSeconds = usageToday[pkg] ?: 0
                val best = maxOf(systemSeconds, localSeconds)
                usageToday[pkg] = best
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error sync UsageStats for $pkg: ${e.message}")
        }
    }

    private fun loadTodayUsageFromSystem() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val stats = usm.queryAndAggregateUsageStats(dayStartMillis(), System.currentTimeMillis())

            for ((pkg, limit) in limitsByPackage) {
                val usageStats = stats[pkg] ?: continue
                val usedSeconds = (usageStats.totalTimeInForeground / 1000).toInt()
                if (usedSeconds > 0) {
                    usageToday[pkg] = usedSeconds
                    if (usedSeconds >= limit.maxSecondsPerDay) {
                        blockApp(pkg, "quota")
                    }
                }
            }
            Log.i(TAG, "📊 Initial system usage loaded")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error loading UsageStats: ${e.message}")
        }
    }

    private fun enforceLimit() {
        if (!startupComplete) return

        checkNuclearExpiration()
        checkNuclearCancelReady()
        enforceNuclearDnd()

        val calReset = Calendar.getInstance()
        calReset.add(Calendar.HOUR_OF_DAY, -2)
        val logicalToday = calReset.get(Calendar.DAY_OF_YEAR)
        if (logicalToday != trackingDay) {
            trackingDay = logicalToday
            usageToday.clear()
            Log.i(TAG, "📅 New day detected — resetting counters...")

            val appsToUnsuspend = suspendedApps.toList()
            suspendedApps.clear()
            AppWatcherService.blockedApps.clear()
            for (pkg in appsToUnsuspend) {
                DeviceOwnerHelper.unsuspendApp(this, pkg)
                ensureNotificationUnmuted(pkg)
            }

            val nuclear = nuclearState
            if (nuclear != null && nuclear.active && !NuclearManager.isExpired(nuclear)) {
                for (pkg in nuclear.blockedPackages) {
                    AppWatcherService.blockedApps.add(pkg)
                }
            }

            loadTodayUsageFromSystem()
        }

        var currentApp = AppWatcherService.currentForegroundApp
        val now = System.currentTimeMillis()
        val gap = now - lastEnforceTime
        lastEnforceTime = now

        // If service was asleep/killed for more than 5s, force immediate system sync
        val syncNeeded = if (gap > 5_000L) {
            Log.w(TAG, "⚠️ Gap detected: ${gap / 1000}s since last enforce — forcing system sync")
            true
        } else {
            now - lastSystemQueryTime > SYSTEM_SYNC_INTERVAL_MS
        }

        if (syncNeeded) {
            lastSystemQueryTime = now
            for ((pkg, _) in limitsByPackage) {
                syncUsageFromSystem(pkg)
            }
        }

        // Enforce curfew notification muting for ALL packages in period block rules
        enforceCurfewNotificationMuting()

        // Check if any suspended apps should be unblocked (curfew ended, allowed hours started, etc.)
        checkAndUnblockApps()

        if (currentApp != "unknown") {
            if (isCurrentlyPeriodBlocked(currentApp)) {
                Log.w(TAG, "🌙 BLOCK REASON: curfew period | $currentApp")
                blockApp(currentApp, "curfew")
                return
            }
        }

        val limit = limitsByPackage[currentApp] ?: return

        val dayOfWeek = calReset.get(Calendar.DAY_OF_WEEK) - 1
        if (dayOfWeek !in limit.allowedDays) {
            blockApp(currentApp, "day")
            return
        }

        if (!limit.allDay) {
            val cal = Calendar.getInstance()
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (nowMinutes < limit.allowedHoursStart || nowMinutes >= limit.allowedHoursEnd) {
                blockApp(currentApp, "hour")
                return
            }
        }

        val used = (usageToday[currentApp] ?: 0) + 1
        usageToday[currentApp] = used

        val remaining = limit.maxSecondsPerDay - used

        if (remaining <= 0) {
            Log.w(TAG, "🚫 BLOCK REASON: limit reached | $currentApp | used=${used}s / max=${limit.maxSecondsPerDay}s")
            blockApp(currentApp, "quota")
        }

        if (remaining > 0) {
            val remainMin = remaining / 60
            val remainSec = remaining % 60
            updateNotification("$currentApp : ${remainMin}m${remainSec}s remaining")
        }
    }

    /**
     * Curfew: wall-clock minutes; overrides daily quota (checked before usage increment).
     */
    private fun isCurrentlyPeriodBlocked(packageName: String): Boolean {
        if (periodBlockRules.isEmpty()) return false
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (rule in periodBlockRules) {
            if (packageName !in rule.packages) continue
            if (dayOfWeek !in rule.allowedDays) continue
            if (ConfigManager.isInBlockedWindow(nowMin, rule.blockedStartMinutes, rule.blockedEndMinutes)) {
                return true
            }
        }
        return false
    }

    private fun shouldStayBlockedForNonCurfewReasons(packageName: String): Boolean {
        val nuclear = nuclearState
        if (nuclear != null && nuclear.active && !NuclearManager.isExpired(nuclear) &&
            packageName in nuclear.blockedPackages
        ) {
            return true
        }
        val limit = limitsByPackage[packageName] ?: return false
        val calDw = Calendar.getInstance()
        calDw.add(Calendar.HOUR_OF_DAY, -2)
        val dayOfWeek = calDw.get(Calendar.DAY_OF_WEEK) - 1
        if (dayOfWeek !in limit.allowedDays) return true
        if (!limit.allDay) {
            val cal = Calendar.getInstance()
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (nowMinutes < limit.allowedHoursStart || nowMinutes >= limit.allowedHoursEnd) {
                return true
            }
        }
        val used = usageToday[packageName] ?: 0
        if (used >= limit.maxSecondsPerDay) return true
        return false
    }

    /**
     * For each period block rule with muteNotifications=true, mute notifications
     * for all packages while the curfew is active, and unmute when it ends.
     */
    private fun enforceCurfewNotificationMuting() {
        if (periodBlockRules.isEmpty()) {
            // No curfew rules — unmute any lingering curfew-muted apps
            for (pkg in curfewMutedApps.toList()) {
                ensureNotificationUnmuted(pkg)
                curfewMutedApps.remove(pkg)
            }
            return
        }

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // Collect all packages that should be muted right now by curfew
        val shouldBeMuted = mutableSetOf<String>()
        for (rule in periodBlockRules) {
            if (!rule.muteNotifications) continue
            if (dayOfWeek !in rule.allowedDays) continue
            if (!ConfigManager.isInBlockedWindow(nowMin, rule.blockedStartMinutes, rule.blockedEndMinutes)) continue
            shouldBeMuted.addAll(rule.packages)
        }

        // Mute newly curfew-blocked apps
        for (pkg in shouldBeMuted) {
            if (pkg !in curfewMutedApps) {
                ensureNotificationMuted(pkg)
                curfewMutedApps.add(pkg)
                Log.i(TAG, "Curfew: muted notifications for $pkg")
            }
        }

        // Unmute apps whose curfew ended
        for (pkg in curfewMutedApps.toList()) {
            if (pkg !in shouldBeMuted) {
                ensureNotificationUnmuted(pkg)
                curfewMutedApps.remove(pkg)
                Log.i(TAG, "Curfew ended: unmuted notifications for $pkg")
            }
        }
    }

    /**
     * Check all currently suspended apps and unblock any that no longer need blocking.
     * This handles: curfew ended, allowed hours started, day-of-week changed, etc.
     */
    private fun checkAndUnblockApps() {
        for (pkg in suspendedApps.toList()) {
            // Skip nuclear-blocked apps
            val nuclear = nuclearState
            if (nuclear != null && nuclear.active && !NuclearManager.isExpired(nuclear)
                && pkg in nuclear.blockedPackages) continue

            // Still curfew-blocked?
            if (isCurrentlyPeriodBlocked(pkg)) continue

            // Still blocked by non-curfew reasons?
            if (shouldStayBlockedForNonCurfewReasons(pkg)) continue

            // No reason to keep blocking
            suspendedApps.remove(pkg)
            AppWatcherService.blockedApps.remove(pkg)
            DeviceOwnerHelper.unsuspendApp(this, pkg)
            ensureNotificationUnmuted(pkg)
            Log.i(TAG, "🔓 Unblocked $pkg (no longer restricted)")
        }
    }

    private fun blockApp(packageName: String, reason: String) {
        val alreadyTracked = packageName in suspendedApps
        suspendedApps.add(packageName)
        AppWatcherService.blockedApps.add(packageName)
        // OS-level suspension via Device Owner — clean, instant, no UI flash.
        // No-op when not DO; A11Y HOME spam acts as fallback.
        DeviceOwnerHelper.suspendApp(this, packageName)
        if (!alreadyTracked) {
            updateNotification("🚫 $packageName blocked")
            handleBlockedNotificationPolicyOnBlock(packageName, reason)
        }
        // Force immediate HOME if this is the current foreground app
        // (don't wait for the next AccessibilityEvent window change)
        if (packageName == AppWatcherService.currentForegroundApp) {
            AppWatcherService.forceHomeIfBlocked()
        }
    }

    private fun loadNuclearState() {
        val state = NuclearManager.loadState(this) ?: return
        if (state.active && !NuclearManager.isExpired(state)) {
            nuclearState = state
            for (pkg in state.blockedPackages) {
                AppWatcherService.blockedApps.add(pkg)
            }
            applyNuclearDndIfPossible()
            Log.i(TAG, "☢️ Nuclear mode restored: ${state.blockedPackages.size} apps blocked (DND active)")
        } else if (state.active) {
            NuclearManager.clearState(this)
        }
    }

    private fun checkNuclearExpiration() {
        val state = nuclearState ?: return
        if (NuclearManager.isExpired(state)) {
            for (pkg in state.blockedPackages) {
                if (pkg !in suspendedApps) {
                    AppWatcherService.blockedApps.remove(pkg)
                }
            }
            nuclearState = null
            NuclearManager.clearState(this)
            restoreDndAfterNuclear()
            updateNotification("Nuclear mode finished")
        }
    }

    private fun checkNuclearCancelReady() {
        val state = nuclearState ?: return
        if (!NuclearManager.isCancelReady(state)) return
        Log.i(TAG, "☢️ Nuclear cancel delay elapsed, ending nuclear mode")
        for (pkg in state.blockedPackages) {
            if (pkg !in suspendedApps) {
                AppWatcherService.blockedApps.remove(pkg)
            }
        }
        nuclearState = null
        NuclearManager.clearState(this)
        restoreDndAfterNuclear()
        updateNotification("Nuclear mode cancelled (delay complete)")
    }

    private fun activateNuclearMode(
        packages: List<String>,
        durationMs: Long
    ) {
        val endTime = System.currentTimeMillis() + durationMs
        val state = NuclearManager.NuclearState(
            active = true,
            endTimestamp = endTime,
            blockedPackages = packages
        )
        nuclearState = state
        NuclearManager.saveState(this, state)

        for (pkg in packages) {
            AppWatcherService.blockedApps.add(pkg)
        }
        // DND handles all notification suppression during Nuclear Mode
        applyNuclearDndIfPossible()
        val minutes = durationMs / 60_000
        updateNotification("☢️ Nuclear: ${packages.size} apps blocked (${minutes}min)")
    }

    /**
     * During Nuclear mode we enable Android "priority-only" interruptions.
     * Calls and alarms are always allowed.
     */
    private fun applyNuclearDndIfPossible() {
        if (nuclearDndApplied) return
        forceApplyNuclearDnd()
    }

    /**
     * Actually sets DND to priority mode. Called both on first apply and
     * when we detect the user toggled DND off via quick settings.
     */
    private fun forceApplyNuclearDnd() {
        try {
            if (!PermissionHelper.hasNotificationPolicyPermission(this)) {
                Log.w(TAG, "☢️ Nuclear: DND permission missing, cannot enable priority mode")
                return
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            try {
                val categories = Policy.PRIORITY_CATEGORY_CALLS or
                    Policy.PRIORITY_CATEGORY_MESSAGES or
                    Policy.PRIORITY_CATEGORY_ALARMS or
                    Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
                nm.notificationPolicy = Policy(
                    categories,
                    Policy.PRIORITY_SENDERS_ANY,
                    Policy.PRIORITY_SENDERS_ANY
                )
            } catch (e: Exception) {
                Log.w(TAG, "☢️ Nuclear: could not set fine DND policy: ${e.message}")
            }
            nuclearDndApplied = true
            Log.i(TAG, "☢️ Nuclear: DND priority enabled (calls + alarms allowed)")
        } catch (e: Exception) {
            Log.e(TAG, "☢️ Nuclear: Failed to enable DND priority: ${e.message}")
        }
    }

    /**
     * Checks if DND was manually disabled (via quick settings tile, etc.)
     * and re-enables it if Nuclear mode is still active.
     */
    private fun enforceNuclearDnd() {
        val state = nuclearState ?: return
        if (!state.active || NuclearManager.isExpired(state)) return
        if (!nuclearDndApplied) return // wasn't applied in the first place (no permission)

        try {
            val nm = getSystemService(NotificationManager::class.java)
            val currentFilter = nm.currentInterruptionFilter
            if (currentFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                Log.w(TAG, "☢️ Nuclear: DND was disabled externally (filter=$currentFilter), re-applying")
                forceApplyNuclearDnd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "☢️ Nuclear: Error checking DND state: ${e.message}")
        }
    }

    private fun restoreDndAfterNuclear() {
        if (!nuclearDndApplied) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            // Best-effort restore: go back to "all interruptions"
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Log.i(TAG, "☢️ Nuclear: DND restored")
        } catch (e: Exception) {
            Log.e(TAG, "☢️ Nuclear: Failed to restore DND: ${e.message}")
        } finally {
            nuclearDndApplied = false
        }
    }

    /**
     * Periodically (every 30s) re-write the secure setting that lists enabled accessibility
     * services so AppWatcherService stays bound even if the user toggles it off in Settings.
     * No-op when the app is not Device Owner.
     */
    private fun checkA11YReEnable() {
        val now = System.currentTimeMillis()
        if (now - lastA11YReEnableTime < A11Y_REENABLE_INTERVAL_MS) return
        lastA11YReEnableTime = now
        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            DeviceOwnerHelper.enforceA11YReEnable(this)
        }
    }

    private fun checkConfigReload() {
        try {
            val newModified = ConfigManager.getConfigLastModified(this)
            if (newModified <= configLastModified) return

            val newConfig = ConfigManager.loadConfig(this)
            configLastModified = newModified

            val newPackages = newConfig.limits.map { it.packageName }.toSet()
            val oldPackages = limitsByPackage.keys.toSet()

            for (pkg in oldPackages - newPackages) {
                limitsByPackage.remove(pkg)
                usageToday.remove(pkg)
                if (pkg in suspendedApps) {
                    suspendedApps.remove(pkg)
                    AppWatcherService.blockedApps.remove(pkg)
                    DeviceOwnerHelper.unsuspendApp(this, pkg)
                    ensureNotificationUnmuted(pkg)
                    Log.i(TAG, "🔓 Config reload — $pkg removed, unblocked")
                }
            }

            for (limit in newConfig.limits) {
                limitsByPackage[limit.packageName] = limit
            }
            config = newConfig
            periodBlockRules.clear()
            periodBlockRules.addAll(newConfig.periodBlocks)

            updateNotification("Config reloaded — ${newPackages.size} app(s)")
            Log.i(TAG, "🔄 Config reloaded")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error reloading config: ${e.message}")
        }
    }

    private fun requestCancelNuclearModeInternal() {
        val state = nuclearState ?: return
        if (state.cancelExecuteAt > 0L) return
        val delaySec = DelayManager.getCurrentEffectiveDelaySeconds(this)
        val now = System.currentTimeMillis()
        val updated = NuclearManager.withCancelRequested(state, now, now + delaySec * 1000L)
        nuclearState = updated
        NuclearManager.saveState(this, updated)
        updateNotification("Nuclear cancel requested (${delaySec}s delay)")
    }

    private fun cancelPendingNuclearCancelInternal() {
        val state = nuclearState ?: return
        if (state.cancelExecuteAt <= 0L) return
        val updated = NuclearManager.withCancelCleared(state)
        nuclearState = updated
        NuclearManager.saveState(this, updated)
        updateNotification("Nuclear cancel request removed")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        checkLimitsAndDelayHandler.removeCallbacks(enforceRunnable)
        Log.i(TAG, "💀 LimitService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val choiceChannel = NotificationChannel(
            CHOICE_CHANNEL_ID,
            "SelfControl choices",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Action prompts when an app becomes blocked"
            setShowBadge(false)
        }
        nm.createNotificationChannel(choiceChannel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SelfControl")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun handleBlockedNotificationPolicyOnBlock(packageName: String, reason: String) {
        val pref = BlockedNotificationManager.getMuteWhenBlockedPreference(this, packageName)
        if (pref == null) {
            maybePromptBlockedNotificationChoice(packageName, reason)
            return
        }
        if (pref) {
            ensureNotificationMuted(packageName)
        } else {
            ensureNotificationUnmuted(packageName)
        }
    }

    private fun maybePromptBlockedNotificationChoice(packageName: String, reason: String) {
        if (packageName in promptedBlockedNotificationChoice) return
        promptedBlockedNotificationChoice.add(packageName)

        val appName = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { packageName }

        val muteIntent = Intent(this, BlockedNotificationChoiceReceiver::class.java).apply {
            action = BlockedNotificationChoiceReceiver.ACTION_BLOCKED_NOTIF_MUTE
            putExtra(BlockedNotificationChoiceReceiver.EXTRA_PACKAGE, packageName)
        }
        val keepIntent = Intent(this, BlockedNotificationChoiceReceiver::class.java).apply {
            action = BlockedNotificationChoiceReceiver.ACTION_BLOCKED_NOTIF_KEEP
            putExtra(BlockedNotificationChoiceReceiver.EXTRA_PACKAGE, packageName)
        }

        val mutePi = PendingIntent.getBroadcast(
            this,
            (packageName + "_mute").hashCode(),
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val keepPi = PendingIntent.getBroadcast(
            this,
            (packageName + "_keep").hashCode(),
            keepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = getSystemService(NotificationManager::class.java)

        // Check that the channel exists and notifications are enabled
        val channel = nm.getNotificationChannel(CHOICE_CHANNEL_ID)
        Log.i(TAG, "Prompting blocked notification choice for $appName ($packageName) reason=$reason")
        Log.i(TAG, "  Choice channel exists=${channel != null}, importance=${channel?.importance}")
        Log.i(TAG, "  areNotificationsEnabled=${nm.areNotificationsEnabled()}")

        val n = NotificationCompat.Builder(this, CHOICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$appName is now blocked")
            .setContentText("Mute notifications from $appName while blocked?")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$appName has been blocked ($reason). Do you want to mute its notifications until it is unblocked?"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "Mute notifications", mutePi)
            .addAction(0, "Keep notifications", keepPi)
            .build()

        nm.notify(100000 + packageName.hashCode(), n)
    }

    private fun ensureNotificationMuted(packageName: String) {
        if (packageName in currentlyMutedBySelfControl) return
        val ok = BlockedNotificationManager.applyNotificationMute(this, packageName, true)
        if (ok) {
            currentlyMutedBySelfControl.add(packageName)
            BlockedNotificationManager.markCurrentlyMutedBySelfControl(this, packageName, true)
        }
    }

    private fun ensureNotificationUnmuted(packageName: String) {
        if (packageName !in currentlyMutedBySelfControl) return
        val ok = BlockedNotificationManager.applyNotificationMute(this, packageName, false)
        if (ok) {
            currentlyMutedBySelfControl.remove(packageName)
            BlockedNotificationManager.markCurrentlyMutedBySelfControl(this, packageName, false)
        }
    }

    private fun onBlockedNotificationChoiceApplied(packageName: String, mute: Boolean) {
        // Dismiss the choice notification
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(100000 + packageName.hashCode())

        if (packageName !in suspendedApps) return
        if (mute) {
            ensureNotificationMuted(packageName)
            updateNotification("$packageName blocked + notifications muted")
        } else {
            ensureNotificationUnmuted(packageName)
            updateNotification("$packageName blocked (notifications kept)")
        }
    }
}
