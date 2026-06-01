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
 * Dev — temporarily allow APK installs (re-applied at next app start):
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
 *
 * Dev — re-apply install restrictions immediately:
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
 *
 * Recovery — force-unsuspend every package suspended by our DO admin.
 * Useful when an app is stuck "App paused" after a daily reset bug or service kill.
 *   adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.UNSUSPEND_ALL
 */
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.jo.selfcontrol.ultimate.STATUS" -> handleStatus(context)
            "com.jo.selfcontrol.ultimate.REMOVE_OWNER" -> handleRemoveOwner(context)
            "com.jo.selfcontrol.ultimate.ALLOW_INSTALL" -> handleAllowInstall(context)
            "com.jo.selfcontrol.ultimate.BLOCK_INSTALL" -> handleBlockInstall(context)
            "com.jo.selfcontrol.ultimate.UNSUSPEND_ALL" -> handleUnsuspendAll(context)
            "com.jo.selfcontrol.ultimate.EXPORT_LOG" -> handleExportLog(context)
            "com.jo.selfcontrol.ultimate.CLEAR_LOG" -> handleClearLog(context)
        }
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

    private fun handleUnsuspendAll(context: Context) {
        Log.w("SelfControl.Cmd", "=== UNSUSPEND_ALL (manual) ===")
        DeviceOwnerHelper.clearAllStuckSuspensions(context)
    }

    private fun handleAllowInstall(context: Context) {
        Log.w("SelfControl.Cmd", "=== ALLOW_INSTALL (dev) ===")
        DeviceOwnerHelper.setInstallRestrictions(context, blocked = false)
    }

    private fun handleBlockInstall(context: Context) {
        Log.w("SelfControl.Cmd", "=== BLOCK_INSTALL (dev) ===")
        DeviceOwnerHelper.setInstallRestrictions(context, blocked = true)
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
