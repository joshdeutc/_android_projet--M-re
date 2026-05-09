package com.jo.selfcontrol.ultimate

import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only automation hooks to enable fully automated end-to-end tests via adb.
 *
 * Examples:
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.TEST_SET_CURFEW_MUTE --es pkg com.mand.notitest
 *   adb shell am broadcast -a com.jo.selfcontrol.ultimate.TEST_CLEAR_CURFEW --es pkg com.mand.notitest
 */
class TestAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            Log.w(TAG, "Ignoring test automation command in non-debuggable build.")
            return
        }

        val pkg = intent.getStringExtra(EXTRA_PKG)?.trim().orEmpty()
        if (pkg.isBlank()) {
            Log.w(TAG, "Missing extra '$EXTRA_PKG'")
            return
        }

        when (intent.action) {
            ACTION_SET_CURFEW_MUTE -> setCurfewMuteRule(context, pkg)
            ACTION_CLEAR_CURFEW -> clearCurfewRulesForPackage(context, pkg)
            ACTION_SET_APP_LIMIT -> setAppLimit(
                context,
                pkg,
                intent.getIntExtra(EXTRA_MAX_SECONDS, 0)
            )
            ACTION_CLEAR_APP_LIMIT -> clearAppLimit(context, pkg)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun setCurfewMuteRule(context: Context, pkg: String) {
        val cur = ConfigManager.loadConfig(context)
        val cleaned = cur.periodBlocks.filterNot { pkg in it.packages }

        val rule = ConfigManager.PeriodBlockRule(
            packages = listOf(pkg),
            blockedStartMinutes = 0,
            blockedEndMinutes = 24 * 60, // 24:00 (covers entire day in half-open range)
            allowedDays = (0..6).toList(),
            muteNotifications = true
        )

        val updated = ConfigManager.Config(cur.limits, cleaned + rule)
        ConfigManager.saveConfig(context, updated)
        Log.i(TAG, "✅ Test curfew mute enabled for $pkg")
    }

    private fun clearCurfewRulesForPackage(context: Context, pkg: String) {
        val cur = ConfigManager.loadConfig(context)
        val cleaned = cur.periodBlocks.filterNot { pkg in it.packages }
        val updated = ConfigManager.Config(cur.limits, cleaned)
        ConfigManager.saveConfig(context, updated)
        Log.i(TAG, "✅ Test curfew cleared for $pkg")
    }

    private fun setAppLimit(context: Context, pkg: String, maxSeconds: Int) {
        // Force-mute-on-block for this package so blocked-state prompt doesn't appear
        BlockedNotificationManager.setMuteWhenBlockedPreference(context, pkg, true)

        val cur = ConfigManager.loadConfig(context)
        val cleaned = cur.limits.filterNot { it.packageName == pkg }

        val limit = ConfigManager.AppLimit(
            packageName = pkg,
            maxMinutesPerDay = maxSeconds / 60,
            maxSecondsPerDay = maxSeconds,
            allowedDays = (0..6).toList(),
            allowedHoursStart = 0,
            allowedHoursEnd = 24 * 60,
            allDay = true
        )

        val updated = ConfigManager.Config(cleaned + limit, cur.periodBlocks)
        ConfigManager.saveConfig(context, updated)
        Log.i(TAG, "✅ Test app limit set: $pkg → ${maxSeconds}s/day (mute-on-block=true)")

        // If limit is 0, immediately force-block (can't wait for real foreground usage)
        if (maxSeconds == 0) {
            // Give LimitService time to pick up the new config
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                LimitService.forceBlockForTest(pkg)
                Log.i(TAG, "✅ Test: forced quota block for $pkg")
            }, 1500)
        }
    }

    private fun clearAppLimit(context: Context, pkg: String) {
        val cur = ConfigManager.loadConfig(context)
        val cleaned = cur.limits.filterNot { it.packageName == pkg }
        val updated = ConfigManager.Config(cleaned, cur.periodBlocks)
        ConfigManager.saveConfig(context, updated)
        Log.i(TAG, "✅ Test app limit cleared for $pkg")
    }

    companion object {
        private const val TAG = "SelfControl.TestAuto"

        const val ACTION_SET_CURFEW_MUTE = "com.jo.selfcontrol.ultimate.TEST_SET_CURFEW_MUTE"
        const val ACTION_CLEAR_CURFEW = "com.jo.selfcontrol.ultimate.TEST_CLEAR_CURFEW"
        const val ACTION_SET_APP_LIMIT = "com.jo.selfcontrol.ultimate.TEST_SET_APP_LIMIT"
        const val ACTION_CLEAR_APP_LIMIT = "com.jo.selfcontrol.ultimate.TEST_CLEAR_APP_LIMIT"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_MAX_SECONDS = "max_seconds"
    }
}

