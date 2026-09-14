package com.jo.selfcontrol.ultimate

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives adb commands.
 *
 * Status:
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.STATUS
 *
 * Emergency backdoor:
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.REMOVE_OWNER
 *
 * Open a time-boxed install window. Defaults to InstallWindowManager.DEFAULT_MINUTES; the window
 * closes itself on the device's own clock, so adb dying in the meantime is a non-event:
 *   adb shell am broadcast -p com.jo.selfcontrol.ultimate \
 *     -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
 *   adb shell am broadcast -p com.jo.selfcontrol.ultimate \
 *     -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL --ei minutes 30
 *
 * Close the window early. A hardening, so it always applies immediately:
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
 *
 * Recovery — force-unsuspend every package suspended by our DO admin.
 * Useful when an app is stuck "App paused" after a daily reset bug or service kill.
 *   adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.UNSUSPEND_ALL
 *
 * Import an install blocklist CSV (group,package) pushed to the device.
 * Add-only merge, so it is a hardening and applies immediately — no delay gate.
 *
 * The file MUST land in our own external files dir. Scoped storage (Android 11+) denies us
 * /sdcard with EACCES since we hold no storage permission, while `adb push` can still write
 * there — so this is the one path that works for both sides:
 *   adb push blocklist.csv /sdcard/Android/data/com.jo.selfcontrol.ultimate/files/blocklist.csv
 *   adb shell am broadcast -p com.jo.selfcontrol.ultimate \
 *     -a com.jo.selfcontrol.ultimate.IMPORT_INSTALL_BLOCKS \
 *     --es path /sdcard/Android/data/com.jo.selfcontrol.ultimate/files/blocklist.csv
 */
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.jo.selfcontrol.ultimate.STATUS" -> handleStatus(context)
            "com.jo.selfcontrol.ultimate.REMOVE_OWNER" -> handleRemoveOwner(context)
            "com.jo.selfcontrol.ultimate.ALLOW_INSTALL" -> handleAllowInstall(context, intent)
            "com.jo.selfcontrol.ultimate.BLOCK_INSTALL" -> handleBlockInstall(context)
            "com.jo.selfcontrol.ultimate.UNSUSPEND_ALL" -> handleUnsuspendAll(context)
            "com.jo.selfcontrol.ultimate.LEARN_SCREEN" -> handleLearnScreen(
                intent.getStringExtra("pkg"), intent.getStringExtra("name")
            )
            "com.jo.selfcontrol.ultimate.REMOVE_SCREEN_RULE" ->
                handleRemoveScreenRule(context, intent.getStringExtra("name"))
            "com.jo.selfcontrol.ultimate.LIST_SCREEN_RULES" -> handleListScreenRules(context)
            "com.jo.selfcontrol.ultimate.EXPORT_LOG" -> handleExportLog(context)
            "com.jo.selfcontrol.ultimate.CLEAR_LOG" -> handleClearLog(context)
            "com.jo.selfcontrol.ultimate.IMPORT_INSTALL_BLOCKS" ->
                handleImportInstallBlocks(context, intent.getStringExtra("path"))
        }
    }

    private fun handleImportInstallBlocks(context: Context, path: String?) {
        if (path.isNullOrBlank()) {
            Log.e("SelfControl.Cmd", "IMPORT_INSTALL_BLOCKS: missing --es path")
            return
        }
        val text = InstallBlockManager.readCsvFile(path)
        if (text == null) {
            Log.e("SelfControl.Cmd", "IMPORT_INSTALL_BLOCKS: cannot read $path")
            return
        }
        val result = InstallBlockManager.parseCsv(text)
        if (result.entries.isEmpty()) {
            Log.e("SelfControl.Cmd", "IMPORT_INSTALL_BLOCKS: no valid row in $path")
            return
        }
        val current = ConfigManager.loadConfig(context)
        val merged = InstallBlockManager.mergeIntoConfig(current, result)
        ConfigManager.saveConfig(context, merged)
        val summary = "${result.packageCount} package(s) / ${result.groupCount} group(s)" +
            if (result.rejected.isEmpty()) "" else ", ${result.rejected.size} line(s) rejected"
        Log.w("SelfControl.Cmd", "=== IMPORT_INSTALL_BLOCKS → $summary ===")
        EventLog.log(context, "CMD", "IMPORT_INSTALL_BLOCKS $path → $summary")
    }

    private fun handleExportLog(context: Context) {
        val path = EventLog.exportToExternal(context)
        Log.w("SelfControl.Cmd", "=== EXPORT_LOG → $path ===")
        EventLog.log(context, "CMD", "EXPORT_LOG → $path")
    }

    private fun handleClearLog(context: Context) {
        Log.w("SelfControl.Cmd", "=== CLEAR_LOG ===")
        EventLog.clear(context)
        EventLog.log(context, "CMD", "log cleared")
    }

    /**
     * Start a guided screen-learning session. Driven from adb for now; the same entry point is what a
     * button in MainActivity would call, since everything after this happens on the floating overlay.
     */
    private fun handleLearnScreen(pkg: String?, name: String?) {
        if (pkg.isNullOrBlank()) {
            Log.e("SelfControl.Cmd", "LEARN_SCREEN: missing --es pkg")
            return
        }
        val svc = AppWatcherService.serviceInstance
        if (svc == null) {
            Log.e("SelfControl.Cmd", "LEARN_SCREEN: accessibility service not connected")
            return
        }
        Log.w("SelfControl.Cmd", "=== LEARN_SCREEN pkg=$pkg ===")
        ScreenLearnSession.start(svc, pkg, name?.takeIf { it.isNotBlank() } ?: "Rule $pkg")
    }

    /**
     * Developer escape hatch: remove a screen rule by name, bypassing the `me`-flavor permanence.
     * Reachable only over adb, which is the whole point — the phone UI keeps rules permanent while a
     * PC can still lift a bad one, without hand-editing the rules file.
     *   adb shell am broadcast -p com.jo.selfcontrol.ultimate \
     *     -a com.jo.selfcontrol.ultimate.REMOVE_SCREEN_RULE --es name "WhatsApp_Actus"
     */
    private fun handleRemoveScreenRule(context: Context, name: String?) {
        if (name.isNullOrBlank()) {
            Log.e("SelfControl.Cmd", "REMOVE_SCREEN_RULE: missing --es name")
            return
        }
        val removed = ScreenRuleManager.removeImmediate(context, name)
        Log.w("SelfControl.Cmd", "=== REMOVE_SCREEN_RULE '$name' → ${if (removed) "removed" else "not found"} ===")
    }

    /** List current screen rules to logcat, so the exact name for REMOVE_SCREEN_RULE is discoverable. */
    private fun handleListScreenRules(context: Context) {
        val rules = ScreenRuleManager.load(context)
        Log.w("SelfControl.Cmd", "=== SCREEN RULES (${rules.size}) ===")
        for (r in rules) {
            Log.w(
                "SelfControl.Cmd",
                "  '${r.name}' pkg=${r.packageName} markers=${r.blockedIds.size} " +
                    "ok=${r.successes} fail=${r.failures}"
            )
        }
    }

    private fun handleUnsuspendAll(context: Context) {
        Log.w("SelfControl.Cmd", "=== UNSUSPEND_ALL (manual) ===")
        DeviceOwnerHelper.clearAllStuckSuspensions(context)
    }

    private fun handleAllowInstall(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra("minutes", InstallWindowManager.DEFAULT_MINUTES)
        Log.w("SelfControl.Cmd", "=== ALLOW_INSTALL ($minutes min requested) ===")
        InstallWindowManager.open(context, minutes)
    }

    private fun handleBlockInstall(context: Context) {
        Log.w("SelfControl.Cmd", "=== BLOCK_INSTALL ===")
        InstallWindowManager.close(context, "BLOCK_INSTALL")
    }

    private fun handleRemoveOwner(context: Context) {
        Log.w("SelfControl.Cmd", "=== EMERGENCY BACKDOOR TRIGGERED ===")
        // 1. Force unlock the settings immediately (bypasses any delay)
        val state = DelayManager.loadState(context)
        // Setting unlock time to 1 forces it to be unlocked immediately, preventing UI loops
        val file = java.io.File(context.filesDir, "delay_config.json")
        try {
            val json = org.json.JSONObject().apply {
                put("global_delay_seconds", state.globalDelaySeconds)
                put("unlock_time", 1L) // Instant unlock
                put("pending_configs", org.json.JSONArray())
                put("pending_delay_execute_at", 0L)
            }
            file.writeText(json.toString(2))
        } catch(e: Exception) {}

        Log.w("SelfControl.Cmd", "Settings protection unlocked.")

        // 2. Clear Device Owner / Device Admin so uninstall can proceed
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, AdminReceiver::class.java)
            if (DeviceOwnerHelper.isDeviceOwner(context)) {
                DeviceOwnerHelper.clearDeviceOwner(context)
            } else if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                Log.w("SelfControl.Cmd", "Device Admin disabled.")
            } else {
                Log.w("SelfControl.Cmd", "No device admin / owner active for this app.")
            }
        } catch (e: Exception) {
            Log.e("SelfControl.Cmd", "Failed to clear policy: ${e.message}", e)
        }
    }

    private fun handleStatus(context: Context) {
        Log.i("SelfControl.Cmd", "=== STATUS REPORT ===")
        val fgsRunning = LimitService.isRunning
        Log.i("SelfControl.Cmd", "LimitService running: $fgsRunning")
        
        val a11yBound = AppWatcherService.isBound
        Log.i("SelfControl.Cmd", "AccessibilityService bound: $a11yBound")

        val currentForeground = AppWatcherService.currentForegroundApp
        Log.i("SelfControl.Cmd", "Current App: $currentForeground")

        val limits = LimitService.getLimits()
        Log.i("SelfControl.Cmd", "Monitored apps: ${limits.size}")
        
        val usage = LimitService.getUsageData()
        Log.i("SelfControl.Cmd", "Usage tracking details: ${usage.size} apps tracked today")
        
        val blocked = LimitService.getBlockedApps()
        Log.i("SelfControl.Cmd", "Blocked apps: $blocked")

        val nuclear = LimitService.getNuclearState()
        if (nuclear != null && nuclear.active) {
            Log.i("SelfControl.Cmd", "NUCLEAR MODE ACTIVE until ${nuclear.endTimestamp}")
        }
        Log.i("SelfControl.Cmd", "======================")
    }
}
